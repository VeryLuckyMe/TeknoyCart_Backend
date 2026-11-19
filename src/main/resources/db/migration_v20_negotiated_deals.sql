-- ============================================================================
-- V20: Seller-accepted Tawad (negotiated) prices at checkout
-- ============================================================================
-- Problem: the chat UI lets a seller accept a buyer's offer, but acceptance
-- only existed as a text message. Checkout therefore had no trustworthy price
-- to charge and always fell back to the catalog price.
--
-- This migration adds:
--   1. `negotiated_deals` — one ACTIVE seller-accepted price per chat/product.
--      RLS lets only the two participants read it; writes go through the RPC.
--   2. `accept_negotiated_deal(...)` — seller-only acceptance with ownership
--      and verified-seller checks.
--   3. A 4-argument `place_orders_atomic(..., p_chat_id)` overload that:
--        * validates the chat belongs to the buyer,
--        * requires the submitted unit price to equal the ACTIVE deal price,
--        * inserts the order at catalog price so the existing integrity trigger
--          still owns INSERT pricing, then applies the accepted price in the
--          same transaction and consumes the deal.
--      The 2- and 3-argument functions stay untouched for catalog purchases.
--   4. A conservative backfill for chats where the seller already tapped
--      "Accept Offer" before this migration shipped.
--
-- Idempotent: safe to run more than once.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Structured accepted-price record
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.negotiated_deals (
    deal_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    chat_id UUID NOT NULL REFERENCES public.chats(chat_id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES public.products(product_id) ON DELETE CASCADE,
    buyer_id UUID NOT NULL REFERENCES public.users(user_id) ON DELETE CASCADE,
    seller_id UUID NOT NULL REFERENCES public.users(user_id) ON DELETE CASCADE,
    agreed_price NUMERIC(10, 2) NOT NULL CHECK (agreed_price > 0),
    status TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'CONSUMED', 'CANCELLED')),
    order_id UUID NULL REFERENCES public.orders(order_id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT negotiated_deals_parties_differ CHECK (buyer_id <> seller_id)
);

-- At most one live price per buyer/product/chat. A newer acceptance cancels
-- the previous one inside the RPC before inserting.
CREATE UNIQUE INDEX IF NOT EXISTS negotiated_deals_one_active_uidx
    ON public.negotiated_deals (chat_id, product_id, buyer_id)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS negotiated_deals_checkout_lookup_idx
    ON public.negotiated_deals (buyer_id, product_id, seller_id, status);

ALTER TABLE public.negotiated_deals ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.negotiated_deals FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.negotiated_deals TO authenticated;

DROP POLICY IF EXISTS negotiated_deals_select_participants ON public.negotiated_deals;
CREATE POLICY negotiated_deals_select_participants
    ON public.negotiated_deals FOR SELECT
    TO authenticated
    USING (auth.uid() = buyer_id OR auth.uid() = seller_id);

-- ----------------------------------------------------------------------------
-- 2. Seller acceptance RPC
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.accept_negotiated_deal(
    p_chat_id UUID,
    p_product_id UUID,
    p_agreed_price NUMERIC
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_seller UUID := auth.uid();
    v_buyer UUID;
    v_list_price NUMERIC;
    v_deal_id UUID;
BEGIN
    IF v_seller IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'UNAUTHORIZED');
    END IF;
    IF p_chat_id IS NULL OR p_product_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'INVALID_DEAL');
    END IF;
    IF p_agreed_price IS NULL OR p_agreed_price <= 0 THEN
        RETURN jsonb_build_object('success', false, 'error', 'INVALID_PRICE');
    END IF;

    -- Only the seller in this exact chat can accept a price.
    SELECT c.buyer_id
      INTO v_buyer
      FROM public.chats c
     WHERE c.chat_id = p_chat_id
       AND c.seller_id = v_seller;

    IF v_buyer IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'NOT_CHAT_SELLER');
    END IF;

    -- The accepted product must belong to that seller, and only active
    -- verified sellers can take orders (kept in sync with place_orders_atomic).
    SELECT p.base_price
      INTO v_list_price
      FROM public.products p
      JOIN public.users u ON u.user_id = p.seller_id
     WHERE p.product_id = p_product_id
       AND p.seller_id = v_seller
       AND u.role = 'SELLER'
       AND u.is_seller_verified IS TRUE;

    IF v_list_price IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'INVALID_LISTING');
    END IF;
    IF p_agreed_price > v_list_price THEN
        RETURN jsonb_build_object('success', false, 'error', 'INVALID_PRICE');
    END IF;

    -- Serialise double taps on Accept so only one ACTIVE deal can exist.
    PERFORM pg_advisory_xact_lock(
        hashtextextended(p_chat_id::text || ':' || p_product_id::text, 0)
    );

    UPDATE public.negotiated_deals
       SET status = 'CANCELLED',
           updated_at = now()
     WHERE chat_id = p_chat_id
       AND product_id = p_product_id
       AND buyer_id = v_buyer
       AND status = 'ACTIVE';

    INSERT INTO public.negotiated_deals (
        chat_id, product_id, buyer_id, seller_id, agreed_price
    ) VALUES (
        p_chat_id, p_product_id, v_buyer, v_seller, p_agreed_price
    )
    RETURNING deal_id INTO v_deal_id;

    RETURN jsonb_build_object(
        'success', true,
        'deal_id', v_deal_id,
        'agreed_price', p_agreed_price
    );
EXCEPTION
    WHEN unique_violation THEN
        RETURN jsonb_build_object('success', false, 'error', 'DEAL_CONFLICT');
END;
$$;

REVOKE ALL ON FUNCTION public.accept_negotiated_deal(UUID, UUID, NUMERIC)
    FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.accept_negotiated_deal(UUID, UUID, NUMERIC)
    TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 3. Backfill already-accepted chats (best effort, single-product rooms)
-- ----------------------------------------------------------------------------
-- The seller's Accept button writes exactly "Offer Accepted!" and the buyer's
-- offer is exactly "Can we agree on ₱<amount>? Deal?". Only backfill the
-- latest offer when a seller acceptance follows it, the price is not above
-- list, no decline follows, and the buyer has not already checked out.
WITH latest_offer AS (
    SELECT DISTINCT ON (m.chat_id)
           m.chat_id,
           (regexp_match(
               m.content,
               '^Can we agree on ₱([0-9]+(\.[0-9]+)?)\? Deal\?$'
           ))[1]::numeric AS price,
           m.sent_at
      FROM public.messages m
      JOIN public.chats c ON c.chat_id = m.chat_id
     WHERE m.sender_id = c.buyer_id
       AND m.content ~ '^Can we agree on ₱[0-9]+(\.[0-9]+)?\? Deal\?$'
     ORDER BY m.chat_id, m.sent_at DESC
),
accepted_offer AS (
    SELECT o.chat_id,
           o.price,
           (SELECT max(a.sent_at)
              FROM public.messages a
              JOIN public.chats c2 ON c2.chat_id = a.chat_id
             WHERE a.chat_id = o.chat_id
               AND a.sender_id = c2.seller_id
               AND a.content = 'Offer Accepted!'
               AND a.sent_at > o.sent_at) AS accepted_at
      FROM latest_offer o
)
INSERT INTO public.negotiated_deals (
    chat_id, product_id, buyer_id, seller_id, agreed_price, created_at
)
SELECT a.chat_id,
       i.product_id,
       c.buyer_id,
       c.seller_id,
       a.price,
       a.accepted_at
  FROM accepted_offer a
  JOIN public.chats c ON c.chat_id = a.chat_id
  JOIN public.inquiries i ON i.inquiry_id = c.inquiry_id
  JOIN public.products p ON p.product_id = i.product_id
 WHERE a.accepted_at IS NOT NULL
   AND a.price > 0
   AND a.price <= p.base_price
   AND p.seller_id = c.seller_id
   AND NOT EXISTS (
       SELECT 1
         FROM public.messages d
        WHERE d.chat_id = a.chat_id
          AND d.content = 'Offer Declined.'
          AND d.sent_at > a.accepted_at
   )
   AND NOT EXISTS (
       SELECT 1
         FROM public.messages h
        WHERE h.chat_id = a.chat_id
          AND h.sent_at > a.accepted_at
          AND h.content LIKE '🤝 Handshake Deal Confirmed!%'
   )
ON CONFLICT DO NOTHING;

-- ----------------------------------------------------------------------------
-- 4. Verified negotiated checkout (4-argument overload)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.place_orders_atomic(
    p_lines JSONB,
    p_payment_method TEXT,
    p_client_request_id UUID,
    p_chat_id UUID
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_buyer UUID := auth.uid();
    v_line JSONB;
    v_idx BIGINT;
    v_product_id UUID;
    v_variant_id UUID;
    v_seller UUID;
    v_quantity INTEGER;
    v_claimed NUMERIC;
    v_catalog NUMERIC;
    v_deal_id UUID;
    v_deal_price NUMERIC;
    v_deal JSONB;
    -- line_index -> {"deal_id": ..., "agreed_price": ...} for verified lines.
    -- Captured before the order insert so consuming a deal on one line cannot
    -- make a duplicate line for the same product fall back to catalog price.
    v_line_deals JSONB := '{}'::JSONB;
    -- Lines claiming something other than catalog price without a matching
    -- live deal. If the core then reports PRICE_MISMATCH, this is really a
    -- missing/expired deal, not a listing that changed price.
    v_unverified INTEGER := 0;
    v_catalog_lines JSONB := '[]'::JSONB;
    v_result JSONB;
    v_order JSONB;
    v_orders JSONB := '[]'::JSONB;
    v_order_id UUID;
BEGIN
    -- Catalog checkout keeps using the existing 3-argument path.
    IF p_chat_id IS NULL THEN
        RETURN public.place_orders_atomic(
            p_lines, p_payment_method, p_client_request_id
        );
    END IF;

    IF v_buyer IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'UNAUTHORIZED');
    END IF;
    IF p_lines IS NULL
       OR jsonb_typeof(p_lines) <> 'array'
       OR jsonb_array_length(p_lines) NOT BETWEEN 1 AND 50 THEN
        RETURN jsonb_build_object('success', false, 'error', 'INVALID_LINES');
    END IF;

    -- The caller must be the buyer in this chat.
    IF NOT EXISTS (
        SELECT 1
          FROM public.chats c
         WHERE c.chat_id = p_chat_id
           AND c.buyer_id = v_buyer
    ) THEN
        RETURN jsonb_build_object('success', false, 'error', 'INVALID_CHAT');
    END IF;

    -- Reward only lines whose claimed price matches a live seller acceptance.
    -- Everything else is passed through untouched so the core function can
    -- reject a client-invented price with PRICE_MISMATCH.
    FOR v_line, v_idx IN
        SELECT line, ordinality
          FROM jsonb_array_elements(p_lines)
               WITH ORDINALITY AS x(line, ordinality)
    LOOP
        v_product_id := (v_line->>'product_id')::uuid;
        v_variant_id := (v_line->>'variant_id')::uuid;
        v_claimed := (v_line->>'unit_price')::numeric;

        SELECT p.base_price + COALESCE(pv.additional_price, 0), p.seller_id
          INTO v_catalog, v_seller
          FROM public.products p
          JOIN public.product_variants pv
            ON pv.product_id = p.product_id
         WHERE p.product_id = v_product_id
           AND pv.variant_id = v_variant_id;

        IF v_catalog IS NOT NULL AND v_claimed IS NOT NULL THEN
            SELECT d.deal_id, d.agreed_price
              INTO v_deal_id, v_deal_price
              FROM public.negotiated_deals d
             WHERE d.chat_id = p_chat_id
               AND d.product_id = v_product_id
               AND d.buyer_id = v_buyer
               AND d.seller_id = v_seller
               AND d.status = 'ACTIVE'
             ORDER BY d.created_at DESC
             LIMIT 1;

            IF v_deal_id IS NOT NULL AND v_claimed = v_deal_price THEN
                v_line_deals := jsonb_set(
                    v_line_deals,
                    ARRAY[(v_idx - 1)::text],
                    jsonb_build_object(
                        'deal_id', v_deal_id,
                        'agreed_price', v_deal_price
                    ),
                    true
                );
                -- Insert at catalog price so the BEFORE INSERT integrity
                -- trigger keeps enforcing "INSERT pricing is always catalog".
                v_line := jsonb_set(
                    v_line, '{unit_price}', to_jsonb(v_catalog), true
                );
            ELSIF v_claimed IS DISTINCT FROM v_catalog THEN
                v_unverified := v_unverified + 1;
            END IF;
        END IF;

        v_catalog_lines := v_catalog_lines || jsonb_build_array(v_line);
    END LOOP;

    v_result := public.place_orders_atomic(
        v_catalog_lines, p_payment_method, p_client_request_id
    );
    IF NOT COALESCE((v_result->>'success')::boolean, false) THEN
        IF v_unverified > 0 AND v_result->>'error' = 'PRICE_MISMATCH' THEN
            RETURN jsonb_build_object('success', false, 'error', 'DEAL_NOT_FOUND');
        END IF;
        RETURN v_result;
    END IF;

    -- Apply the accepted price only after the order exists, then consume the
    -- deal. Both writes commit atomically with the order insert.
    FOR v_order IN SELECT * FROM jsonb_array_elements(v_result->'orders')
    LOOP
        v_idx := (v_order->>'line_index')::bigint;
        v_deal := v_line_deals -> v_idx::text;
        v_order_id := (v_order->>'order_id')::uuid;

        IF v_deal IS NOT NULL THEN
            v_line := p_lines -> v_idx::integer;
            v_quantity := (v_line->>'quantity')::integer;
            v_deal_id := (v_deal->>'deal_id')::uuid;
            v_deal_price := (v_deal->>'agreed_price')::numeric;

            UPDATE public.orders
               SET unit_price = v_deal_price,
                   total_amount = round(v_deal_price * v_quantity, 2)
             WHERE order_id = v_order_id
               AND buyer_id = v_buyer;

            UPDATE public.negotiated_deals
               SET status = 'CONSUMED',
                   order_id = v_order_id,
                   updated_at = now()
             WHERE deal_id = v_deal_id
               AND status = 'ACTIVE';

            v_order := jsonb_set(
                v_order,
                '{total_amount}',
                to_jsonb(round(v_deal_price * v_quantity, 2)),
                true
            );
        END IF;

        v_orders := v_orders || jsonb_build_array(v_order);
    END LOOP;

    RETURN jsonb_set(v_result, '{orders}', v_orders, true);
EXCEPTION
    WHEN SQLSTATE 'P0001' THEN
        IF SQLERRM IN ('INVALID_QUANTITY', 'INVALID_LISTING', 'PRODUCT_NOT_ACTIVE',
                       'CANNOT_PURCHASE_OWN_PRODUCT', 'PREORDER_NOT_ALLOWED',
                       'INSUFFICIENT_STOCK', 'PRICE_MISMATCH') THEN
            RETURN jsonb_build_object('success', false, 'error', SQLERRM);
        END IF;
        RAISE;
END;
$$;

REVOKE ALL ON FUNCTION public.place_orders_atomic(JSONB, TEXT, UUID, UUID)
    FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.place_orders_atomic(JSONB, TEXT, UUID, UUID)
    TO authenticated, service_role;

-- Ask PostgREST to expose the new overload immediately.
NOTIFY pgrst, 'reload schema';
