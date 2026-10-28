-- ============================================================================
-- TeknoyCart P0 Security Hardening (V10)
-- ============================================================================
-- Closes the confirmed order/marketplace integrity holes:
--   1. Inventory had permissive INSERT/UPDATE policies for every authenticated
--      user, so any account could rewrite any seller's stock.
--   2. Orders were client-writable as whole rows and clients could INSERT their
--      own price/status. Checkout must go through place_orders_atomic.
--   3. OTP columns were readable by both parties through orders.
--   4. confirm_receipt_as_buyer accepted any status; schedule_meetup_as_seller
--      let either party schedule; verify_handoff_as_seller had no expiry or
--      attempt lockout.
--   5. Reviews could be inserted without a completed order, and sellers/buyers
--      could rewrite each other's columns.
--   6. delete_user_account referenced nonexistent tables/columns and was
--      executable by anon.
--
-- Apply with the backend (DatabaseMigrationRunner) or run once in the Supabase
-- SQL editor as postgres. Idempotent: every policy and RPC is dropped and
-- recreated, so a partial earlier hotfix cannot leave a weaker duplicate.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 0. Schema assumptions: normalize legacy enum columns before the RPCs below
--    bind text literals to them. Mirrors migration_accept_order_fix.sql.
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'orders'
          AND column_name = 'payment_method' AND data_type = 'USER-DEFINED'
    ) THEN
        ALTER TABLE public.orders
            ALTER COLUMN payment_method TYPE VARCHAR USING payment_method::TEXT;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'orders'
          AND column_name = 'status' AND data_type = 'USER-DEFINED'
    ) THEN
        ALTER TABLE public.orders ALTER COLUMN status DROP DEFAULT;
        ALTER TABLE public.orders
            ALTER COLUMN status TYPE VARCHAR(50) USING status::TEXT;
        ALTER TABLE public.orders ALTER COLUMN status SET DEFAULT 'PLACED';
    END IF;
END $$;

-- confirm_receipt_as_buyer writes this column, but no earlier script creates it
-- on a database built from supabase_schema.sql.
ALTER TABLE public.orders ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;

-- Drop every overload of the client-callable RPCs. Leaving an older overload
-- behind is how PGRST203 ambiguity and weaker duplicate code paths appear.
DO $$
DECLARE
    v_name TEXT;
    r RECORD;
BEGIN
    FOR v_name IN
        SELECT unnest(ARRAY[
            'place_orders_atomic',
            'schedule_meetup_as_seller',
            'verify_handoff_as_seller',
            'confirm_receipt_as_buyer',
            'reconcile_inventory_holds',
            'delete_user_account'
        ])
    LOOP
        FOR r IN
            SELECT p.proname,
                   pg_get_function_identity_arguments(p.oid) AS args
            FROM pg_proc p
            WHERE p.pronamespace = 'public'::regnamespace
              AND p.proname = v_name
        LOOP
            EXECUTE format('DROP FUNCTION public.%I(%s)', r.proname, r.args);
        END LOOP;
    END LOOP;
END $$;

-- ----------------------------------------------------------------------------
-- 1. Inventory: only the verified owner (or an admin) may write stock
-- ----------------------------------------------------------------------------
ALTER TABLE public.inventory ENABLE ROW LEVEL SECURITY;

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT policyname FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'inventory'
    LOOP
        EXECUTE format('DROP POLICY %I ON public.inventory', r.policyname);
    END LOOP;
END $$;

CREATE POLICY inventory_select_public
    ON public.inventory FOR SELECT
    TO public
    USING (true);

CREATE POLICY inventory_insert_seller
    ON public.inventory FOR INSERT
    TO authenticated
    WITH CHECK (
        EXISTS (
            SELECT 1
            FROM public.product_variants pv
            JOIN public.products p ON p.product_id = pv.product_id
            JOIN public.users u ON u.user_id = auth.uid()
            WHERE pv.variant_id = inventory.variant_id
              AND (
                  (p.seller_id = auth.uid()
                   AND u.role = 'SELLER'
                   AND u.is_seller_verified IS TRUE)
                  OR u.role = 'ADMIN'
              )
        )
    );

CREATE POLICY inventory_update_seller
    ON public.inventory FOR UPDATE
    TO authenticated
    USING (
        EXISTS (
            SELECT 1
            FROM public.product_variants pv
            JOIN public.products p ON p.product_id = pv.product_id
            JOIN public.users u ON u.user_id = auth.uid()
            WHERE pv.variant_id = inventory.variant_id
              AND (
                  (p.seller_id = auth.uid()
                   AND u.role = 'SELLER'
                   AND u.is_seller_verified IS TRUE)
                  OR u.role = 'ADMIN'
              )
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1
            FROM public.product_variants pv
            JOIN public.products p ON p.product_id = pv.product_id
            JOIN public.users u ON u.user_id = auth.uid()
            WHERE pv.variant_id = inventory.variant_id
              AND (
                  (p.seller_id = auth.uid()
                   AND u.role = 'SELLER'
                   AND u.is_seller_verified IS TRUE)
                  OR u.role = 'ADMIN'
              )
        )
    );

REVOKE ALL PRIVILEGES ON TABLE public.inventory FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.inventory TO anon, authenticated;
GRANT INSERT, UPDATE ON public.inventory TO authenticated;

-- ----------------------------------------------------------------------------
-- 2. Orders: clients cannot INSERT/DELETE, and can only UPDATE admin fields.
--    Scheduling/acceptance/handoff stay behind the SECURITY DEFINER RPCs.
-- ----------------------------------------------------------------------------
ALTER TABLE public.orders ENABLE ROW LEVEL SECURITY;

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT policyname FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'orders'
    LOOP
        EXECUTE format('DROP POLICY %I ON public.orders', r.policyname);
    END LOOP;
END $$;

CREATE POLICY orders_select_parties
    ON public.orders FOR SELECT
    TO authenticated
    USING (auth.uid() = buyer_id OR auth.uid() = seller_id);

CREATE POLICY orders_select_admin
    ON public.orders FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.users u
            WHERE u.user_id = auth.uid() AND u.role = 'ADMIN'
        )
    );

CREATE POLICY orders_update_admin
    ON public.orders FOR UPDATE
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.users u
            WHERE u.user_id = auth.uid() AND u.role = 'ADMIN'
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.users u
            WHERE u.user_id = auth.uid() AND u.role = 'ADMIN'
        )
    );

REVOKE ALL PRIVILEGES ON TABLE public.orders FROM PUBLIC, anon, authenticated;

-- Remove any column grants left by earlier experimental scripts. A table-level
-- REVOKE does not clear column-level privileges in PostgreSQL.
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT column_name
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'orders'
    LOOP
        EXECUTE format(
            'REVOKE SELECT (%1$I), INSERT (%1$I), UPDATE (%1$I), REFERENCES (%1$I) '
            'ON TABLE public.orders FROM PUBLIC, anon, authenticated',
            r.column_name);
    END LOOP;
END $$;

-- Admin console still updates status/refund reference directly. No other role
-- can use these column grants because no seller/buyer UPDATE policy remains.
GRANT UPDATE (status, refund_reference) ON public.orders TO authenticated;

-- OTP columns are server-owned. Grant every other order column so existing
-- order history/detail/report queries keep working.
DO $$
DECLARE
    v_cols TEXT;
BEGIN
    SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position)
      INTO v_cols
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name = 'orders'
       AND column_name NOT IN (
           'handoff_otp',
           'return_otp',
           'return_otp_created_at',
           'return_otp_failed_attempts'
       );

    IF v_cols IS NOT NULL THEN
        EXECUTE format('GRANT SELECT (%s) ON public.orders TO authenticated', v_cols);
    END IF;
END $$;

-- If a previous hotfix published orders to Realtime, subscribers receive the
-- full row from the WAL, which would expose the OTP columns regardless of the
-- grants above. Keep the secrets off the wire.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime'
          AND schemaname = 'public'
          AND tablename = 'orders'
    ) THEN
        ALTER PUBLICATION supabase_realtime DROP TABLE public.orders;
    END IF;
END $$;

-- Recompute every inserted order from the live listing. Even a future service
-- path cannot store a client-supplied price or another seller's identity.
CREATE OR REPLACE FUNCTION public.enforce_order_insert_integrity()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_seller UUID;
    v_unit_price NUMERIC;
BEGIN
    IF NEW.variant_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_VARIANT';
    END IF;
    IF NEW.quantity IS NULL OR NEW.quantity <= 0 THEN
        RAISE EXCEPTION 'INVALID_QUANTITY';
    END IF;

    SELECT p.seller_id, p.base_price + COALESCE(pv.additional_price, 0)
      INTO v_seller, v_unit_price
      FROM public.products p
      JOIN public.product_variants pv ON pv.product_id = p.product_id
     WHERE pv.variant_id = NEW.variant_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'INVALID_VARIANT';
    END IF;

    NEW.seller_id := v_seller;
    NEW.unit_price := v_unit_price;
    NEW.total_amount := round(v_unit_price * NEW.quantity, 2);
    NEW.handoff_otp := NULL;
    NEW.return_otp := NULL;
    NEW.seller_handed_off := false;
    NEW.buyer_confirmed_receipt := false;

    IF NEW.status IS NULL OR btrim(NEW.status::text) = '' THEN
        NEW.status := 'PLACED';
    END IF;
    IF NEW.status::text NOT IN ('PLACED', 'INQUIRY_SENT') THEN
        RAISE EXCEPTION 'ORDERS_MUST_START_UNPAID';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_enforce_order_insert_integrity ON public.orders;
CREATE TRIGGER trg_enforce_order_insert_integrity
    BEFORE INSERT ON public.orders
    FOR EACH ROW
    EXECUTE FUNCTION public.enforce_order_insert_integrity();

-- ----------------------------------------------------------------------------
-- 3. Checkout: no phantom stock, no client price, lock the inventory row
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.place_orders_atomic(
    p_lines JSONB,
    p_payment_method TEXT
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
    v_quantity INTEGER;
    v_unit_price NUMERIC;
    v_total NUMERIC;
    v_seller UUID;
    v_preorder_enabled BOOLEAN;
    v_product_status TEXT;
    v_stock INTEGER;
    v_reserved INTEGER;
    v_is_preorder BOOLEAN;
    v_inquiry UUID;
    v_order UUID;
    v_orders JSONB := '[]'::JSONB;
    v_lines_count INTEGER;
BEGIN
    IF v_buyer IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'UNAUTHORIZED');
    END IF;
    IF p_lines IS NULL
       OR jsonb_typeof(p_lines) <> 'array'
       OR jsonb_array_length(p_lines) NOT BETWEEN 1 AND 50 THEN
        RETURN jsonb_build_object('success', false, 'error', 'INVALID_LINES');
    END IF;
    IF p_payment_method IS NULL
       OR p_payment_method NOT IN ('GCASH', 'CASH_ON_PICKUP') THEN
        RETURN jsonb_build_object('success', false, 'error', 'INVALID_PAYMENT_METHOD');
    END IF;

    FOR v_line, v_idx IN
        SELECT line, ordinality
        FROM jsonb_array_elements(p_lines) WITH ORDINALITY AS x(line, ordinality)
        ORDER BY (line->>'variant_id')
    LOOP
        v_product_id := (v_line->>'product_id')::uuid;
        v_variant_id := (v_line->>'variant_id')::uuid;
        v_quantity := (v_line->>'quantity')::integer;
        IF v_quantity IS NULL OR v_quantity <= 0 THEN
            RAISE EXCEPTION 'INVALID_QUANTITY';
        END IF;

        -- Inventory must already exist. Never invent stock to make a checkout pass.
        SELECT p.seller_id, p.status::text, COALESCE(p.is_preorder_enabled, false),
               p.base_price + COALESCE(pv.additional_price, 0),
               inv.stock_qty, inv.reserved_qty
          INTO v_seller, v_product_status, v_preorder_enabled,
               v_unit_price, v_stock, v_reserved
          FROM public.products p
          JOIN public.product_variants pv ON pv.product_id = p.product_id
          JOIN public.inventory inv ON inv.variant_id = pv.variant_id
          JOIN public.users u ON u.user_id = p.seller_id
         WHERE p.product_id = v_product_id
           AND pv.variant_id = v_variant_id
           AND u.role = 'SELLER'
           AND u.is_seller_verified IS TRUE
         FOR UPDATE OF inv;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'INVALID_LISTING';
        END IF;
        IF v_product_status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'PRODUCT_NOT_ACTIVE';
        END IF;
        IF v_seller = v_buyer THEN
            RAISE EXCEPTION 'CANNOT_PURCHASE_OWN_PRODUCT';
        END IF;
        IF COALESCE((v_line->>'is_preorder')::boolean, false) IS TRUE
           AND v_preorder_enabled IS NOT TRUE THEN
            RAISE EXCEPTION 'PREORDER_NOT_ALLOWED';
        END IF;

        -- The app never supplies the authoritative price.
        IF (v_line->>'unit_price')::numeric IS DISTINCT FROM v_unit_price THEN
            RAISE EXCEPTION 'PRICE_MISMATCH';
        END IF;

        v_total := round(v_unit_price * v_quantity, 2);
        v_is_preorder := false;

        IF v_stock - v_reserved >= v_quantity
           AND COALESCE((v_line->>'is_preorder')::boolean, false) IS NOT TRUE THEN
            UPDATE public.inventory
               SET reserved_qty = reserved_qty + v_quantity,
                   last_updated = now()
             WHERE variant_id = v_variant_id;
        ELSIF v_preorder_enabled IS TRUE THEN
            v_is_preorder := true;
        ELSE
            RAISE EXCEPTION 'INSUFFICIENT_STOCK';
        END IF;

        INSERT INTO public.inquiries (buyer_id, product_id, variant_id, quantity, inquiry_type, message)
        VALUES (v_buyer, v_product_id, v_variant_id, v_quantity, 'AVAILABILITY', 'Checkout order')
        RETURNING inquiry_id INTO v_inquiry;

        INSERT INTO public.orders (
            inquiry_id, buyer_id, seller_id, variant_id, quantity, unit_price, total_amount,
            payment_method, status, version, pickup_location, pickup_day, pickup_time,
            is_preorder, reservation_expires_at
        ) VALUES (
            v_inquiry, v_buyer, v_seller, v_variant_id, v_quantity, v_unit_price, v_total,
            p_payment_method, 'PLACED', 0,
            v_line->>'pickup_location', v_line->>'pickup_day', v_line->>'pickup_time',
            v_is_preorder, CASE WHEN v_is_preorder THEN NULL ELSE now() + interval '24 hours' END
        ) RETURNING order_id INTO v_order;

        v_orders := v_orders || jsonb_build_array(jsonb_build_object(
            'line_index', v_idx - 1,
            'order_id', v_order,
            'total_amount', v_total
        ));
    END LOOP;

    SELECT count(*) INTO v_lines_count FROM jsonb_array_elements(p_lines);
    IF jsonb_array_length(v_orders) <> v_lines_count THEN
        RAISE EXCEPTION 'CHECKOUT_INCOMPLETE';
    END IF;

    RETURN jsonb_build_object('success', true, 'orders', v_orders);
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

REVOKE ALL ON FUNCTION public.place_orders_atomic(JSONB, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.place_orders_atomic(JSONB, TEXT) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 4. Order lifecycle RPCs: seller-only scheduling, guarded receipt, OTP expiry
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.schedule_meetup_as_seller(
    p_order_id UUID,
    p_day TEXT DEFAULT 'Next Business Day',
    p_time TEXT DEFAULT '12:00 PM - 01:30 PM',
    p_location TEXT DEFAULT 'Canteen Area'
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_order RECORD;
    v_caller_id UUID := auth.uid();
    v_otp TEXT;
BEGIN
    SELECT * INTO v_order
      FROM public.orders
     WHERE order_id = p_order_id
     FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Order not found');
    END IF;

    IF v_caller_id IS NULL OR v_order.seller_id != v_caller_id THEN
        RETURN jsonb_build_object('success', false, 'error', 'Only the seller can schedule this meetup');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM public.users u
        WHERE u.user_id = v_caller_id
          AND u.role = 'SELLER'
          AND u.is_seller_verified IS TRUE
    ) THEN
        RETURN jsonb_build_object('success', false, 'error', 'A verified seller account is required');
    END IF;

    IF v_order.status NOT IN ('ACCEPTED', 'READY_FOR_PICKUP', 'PAYMENT_VERIFIED',
                              'ESCROWED', 'MEETUP_SCHEDULED', 'NEEDS_REVIEW') THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Meetup cannot be scheduled from state: ' || v_order.status);
    END IF;

    v_otp := lpad((floor(random() * 900000) + 100000)::text, 6, '0');

    UPDATE public.orders
       SET status = 'MEETUP_SCHEDULED',
           pickup_day = COALESCE(NULLIF(p_day, ''), pickup_day, 'Next Business Day'),
           pickup_time = COALESCE(NULLIF(p_time, ''), pickup_time, '12:00 PM - 01:30 PM'),
           pickup_location = COALESCE(NULLIF(p_location, ''), pickup_location, 'Canteen Area'),
           handoff_otp = v_otp,
           otp_created_at = now(),
           otp_failed_attempts = 0,
           seller_handed_off = false,
           version = COALESCE(version, 0) + 1
     WHERE order_id = p_order_id;

    -- The code is delivered to the buyer through my_order_codes, not returned
    -- to the seller who is supposed to verify it.
    RETURN jsonb_build_object(
        'success', true,
        'order_id', p_order_id,
        'status', 'MEETUP_SCHEDULED'
    );
END;
$$;

REVOKE ALL ON FUNCTION public.schedule_meetup_as_seller(UUID, TEXT, TEXT, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.schedule_meetup_as_seller(UUID, TEXT, TEXT, TEXT) TO authenticated, service_role;

CREATE FUNCTION public.verify_handoff_as_seller(
    p_order_id UUID,
    p_otp TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_order RECORD;
    v_caller_id UUID := auth.uid();
    v_attempts INTEGER;
BEGIN
    SELECT * INTO v_order
      FROM public.orders
     WHERE order_id = p_order_id
     FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Order not found');
    END IF;

    IF v_caller_id IS NULL OR v_order.seller_id != v_caller_id THEN
        RETURN jsonb_build_object('success', false, 'error', 'Only the seller can verify handoff');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM public.users u
        WHERE u.user_id = v_caller_id
          AND u.role = 'SELLER'
          AND u.is_seller_verified IS TRUE
    ) THEN
        RETURN jsonb_build_object('success', false, 'error', 'A verified seller account is required');
    END IF;

    IF v_order.status != 'MEETUP_SCHEDULED' THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Order is not in MEETUP_SCHEDULED state (current: ' || v_order.status || ')');
    END IF;

    v_attempts := COALESCE(v_order.otp_failed_attempts, 0);
    IF v_attempts >= 5 THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Too many failed attempts. Reschedule the meetup to generate a new code.');
    END IF;

    IF v_order.handoff_otp IS NULL
       OR v_order.otp_created_at IS NULL
       OR v_order.otp_created_at < now() - interval '48 hours' THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Verification code expired. Reschedule the meetup to generate a new code.');
    END IF;

    IF trim(v_order.handoff_otp) != trim(COALESCE(p_otp, '')) THEN
        v_attempts := v_attempts + 1;

        IF v_attempts >= 5 THEN
            UPDATE public.orders
               SET status = 'NEEDS_REVIEW',
                   otp_failed_attempts = v_attempts,
                   handoff_otp = NULL,
                   otp_created_at = NULL,
                   version = COALESCE(version, 0) + 1
             WHERE order_id = p_order_id;
            RETURN jsonb_build_object('success', false, 'error',
                'Too many failed attempts. Reschedule the meetup to generate a new code.');
        END IF;

        UPDATE public.orders
           SET otp_failed_attempts = v_attempts
         WHERE order_id = p_order_id;
        RETURN jsonb_build_object('success', false, 'error',
            'Invalid verification code. ' || (5 - v_attempts) || ' attempt(s) remaining.');
    END IF;

    UPDATE public.orders
       SET status = 'HANDOFF_PENDING',
           seller_handed_off = true,
           handoff_completed_at = now(),
           handoff_otp = NULL,
           otp_created_at = NULL,
           otp_failed_attempts = 0,
           version = COALESCE(version, 0) + 1
     WHERE order_id = p_order_id;

    RETURN jsonb_build_object(
        'success', true,
        'order_id', p_order_id,
        'status', 'HANDOFF_PENDING'
    );
END;
$$;

REVOKE ALL ON FUNCTION public.verify_handoff_as_seller(UUID, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.verify_handoff_as_seller(UUID, TEXT) TO authenticated, service_role;

CREATE FUNCTION public.confirm_receipt_as_buyer(
    p_order_id UUID
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_order RECORD;
    v_caller_id UUID := auth.uid();
BEGIN
    SELECT * INTO v_order
      FROM public.orders
     WHERE order_id = p_order_id
     FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Order not found');
    END IF;

    IF v_caller_id IS NULL OR v_order.buyer_id != v_caller_id THEN
        RETURN jsonb_build_object('success', false, 'error', 'Only the buyer can confirm receipt');
    END IF;

    IF v_order.status = 'COMPLETED' THEN
        RETURN jsonb_build_object('success', true, 'order_id', p_order_id, 'status', 'COMPLETED');
    END IF;

    IF v_order.status != 'HANDOFF_PENDING' THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Receipt can only be confirmed after the seller verifies the handoff code');
    END IF;

    UPDATE public.orders
       SET status = 'COMPLETED',
           buyer_confirmed_receipt = true,
           completed_at = now(),
           version = COALESCE(version, 0) + 1
     WHERE order_id = p_order_id;

    RETURN jsonb_build_object('success', true, 'order_id', p_order_id, 'status', 'COMPLETED');
END;
$$;

REVOKE ALL ON FUNCTION public.confirm_receipt_as_buyer(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.confirm_receipt_as_buyer(UUID) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 5. Buyer-only OTP view (orders no longer exposes the secret columns)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW public.my_order_codes
    WITH (security_invoker = false)
AS
SELECT order_id,
       handoff_otp,
       otp_created_at,
       otp_failed_attempts,
       return_otp,
       return_otp_created_at,
       return_otp_failed_attempts
FROM public.orders
WHERE buyer_id = auth.uid();

REVOKE ALL PRIVILEGES ON public.my_order_codes FROM PUBLIC, anon;
GRANT SELECT ON public.my_order_codes TO authenticated;

-- ----------------------------------------------------------------------------
-- 6. Reviews: completed-order-only inserts, column-scoped updates
-- ----------------------------------------------------------------------------
-- product_reviews is created by migration_product_reviews.sql, which the
-- backend runner does not apply. P0 owns this table's security, so create it
-- when the manual script was skipped instead of aborting the whole transaction.
CREATE TABLE IF NOT EXISTS public.product_reviews (
    review_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NULL REFERENCES public.orders(order_id) ON DELETE SET NULL,
    product_id TEXT NOT NULL,
    buyer_id UUID NOT NULL REFERENCES public.users(user_id) ON DELETE CASCADE,
    buyer_name VARCHAR(100) NOT NULL DEFAULT 'CIT Student',
    seller_id UUID NOT NULL REFERENCES public.users(user_id) ON DELETE CASCADE,
    rating INTEGER NOT NULL CHECK (rating >= 1 AND rating <= 5),
    comment TEXT,
    tags TEXT[] DEFAULT '{}',
    image_urls TEXT[] DEFAULT '{}',
    variant_name VARCHAR(100),
    seller_reply TEXT,
    seller_replied_at TIMESTAMP WITH TIME ZONE,
    is_anonymous BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_order_buyer_review UNIQUE (order_id, buyer_id)
);

ALTER TABLE public.product_reviews ENABLE ROW LEVEL SECURITY;

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT policyname FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'product_reviews'
    LOOP
        EXECUTE format('DROP POLICY %I ON public.product_reviews', r.policyname);
    END LOOP;
END $$;

CREATE POLICY reviews_select_all
    ON public.product_reviews FOR SELECT
    TO public
    USING (true);

CREATE POLICY reviews_insert_buyer
    ON public.product_reviews FOR INSERT
    TO authenticated
    WITH CHECK (
        auth.uid() = buyer_id
        AND order_id IS NOT NULL
        AND seller_reply IS NULL
        AND seller_replied_at IS NULL
        AND EXISTS (
            SELECT 1
            FROM public.orders o
            JOIN public.product_variants pv ON pv.variant_id = o.variant_id
            WHERE o.order_id = product_reviews.order_id
              AND o.buyer_id = auth.uid()
              AND o.status = 'COMPLETED'
              AND o.seller_id = product_reviews.seller_id
              AND pv.product_id::text = product_reviews.product_id
        )
    );

CREATE POLICY reviews_update_party
    ON public.product_reviews FOR UPDATE
    TO authenticated
    USING (auth.uid() = buyer_id OR auth.uid() = seller_id)
    WITH CHECK (auth.uid() = buyer_id OR auth.uid() = seller_id);

CREATE POLICY reviews_delete_buyer
    ON public.product_reviews FOR DELETE
    TO authenticated
    USING (auth.uid() = buyer_id);

REVOKE ALL PRIVILEGES ON TABLE public.product_reviews FROM PUBLIC, anon, authenticated;

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT column_name
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'product_reviews'
    LOOP
        EXECUTE format(
            'REVOKE SELECT (%1$I), INSERT (%1$I), UPDATE (%1$I), REFERENCES (%1$I) '
            'ON TABLE public.product_reviews FROM PUBLIC, anon, authenticated',
            r.column_name);
    END LOOP;
END $$;

GRANT SELECT ON public.product_reviews TO anon, authenticated;
GRANT INSERT (order_id, product_id, buyer_id, buyer_name, seller_id,
              rating, comment, tags, image_urls, variant_name, is_anonymous, created_at)
    ON public.product_reviews TO authenticated;
GRANT UPDATE (rating, comment, tags, image_urls, is_anonymous, updated_at,
              seller_reply, seller_replied_at)
    ON public.product_reviews TO authenticated;
GRANT DELETE ON public.product_reviews TO authenticated;

CREATE OR REPLACE FUNCTION public.protect_review_columns()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
DECLARE
    jwt_role TEXT := current_setting('request.jwt.claim.role', true);
BEGIN
    -- Trusted server connections have no JWT uid. anon cannot UPDATE because it
    -- has neither the grant nor an UPDATE policy, so this cannot be abused.
    IF auth.uid() IS NULL
       OR jwt_role = 'service_role'
       OR current_user LIKE 'postgres%'
       OR session_user LIKE 'postgres%' THEN
        RETURN NEW;
    END IF;

    IF auth.uid() = OLD.buyer_id THEN
        IF NEW.order_id IS DISTINCT FROM OLD.order_id
           OR NEW.product_id IS DISTINCT FROM OLD.product_id
           OR NEW.buyer_id IS DISTINCT FROM OLD.buyer_id
           OR NEW.buyer_name IS DISTINCT FROM OLD.buyer_name
           OR NEW.seller_id IS DISTINCT FROM OLD.seller_id
           OR NEW.seller_reply IS DISTINCT FROM OLD.seller_reply
           OR NEW.seller_replied_at IS DISTINCT FROM OLD.seller_replied_at
           OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'Buyers may only edit rating, comment, tags, images, and anonymity.';
        END IF;
    ELSIF auth.uid() = OLD.seller_id THEN
        IF NEW.order_id IS DISTINCT FROM OLD.order_id
           OR NEW.product_id IS DISTINCT FROM OLD.product_id
           OR NEW.buyer_id IS DISTINCT FROM OLD.buyer_id
           OR NEW.buyer_name IS DISTINCT FROM OLD.buyer_name
           OR NEW.seller_id IS DISTINCT FROM OLD.seller_id
           OR NEW.rating IS DISTINCT FROM OLD.rating
           OR NEW.comment IS DISTINCT FROM OLD.comment
           OR NEW.tags IS DISTINCT FROM OLD.tags
           OR NEW.image_urls IS DISTINCT FROM OLD.image_urls
           OR NEW.variant_name IS DISTINCT FROM OLD.variant_name
           OR NEW.is_anonymous IS DISTINCT FROM OLD.is_anonymous
           OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'Sellers may only edit the seller reply.';
        END IF;
    ELSE
        RAISE EXCEPTION 'Only the review buyer or seller may update this review.';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_protect_review_columns ON public.product_reviews;
CREATE TRIGGER trg_protect_review_columns
    BEFORE UPDATE ON public.product_reviews
    FOR EACH ROW
    EXECUTE FUNCTION public.protect_review_columns();

-- ----------------------------------------------------------------------------
-- 7. Account deletion: correct tables/columns, authenticated callers only
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.delete_user_account()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_uid UUID := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'User not authenticated';
    END IF;

    -- An admin who reviewed KYC rows is referenced without ON DELETE CASCADE.
    IF to_regclass('public.seller_verifications') IS NOT NULL THEN
        UPDATE public.seller_verifications
           SET reviewed_by = NULL
         WHERE reviewed_by = v_uid;
    END IF;

    -- public.users owns the app data and cascades to orders, listings, reviews,
    -- chats, notifications, and KYC rows.
    DELETE FROM public.users WHERE user_id = v_uid;
    DELETE FROM auth.users WHERE id = v_uid;
END;
$$;

REVOKE ALL ON FUNCTION public.delete_user_account() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.delete_user_account() TO authenticated;

-- ----------------------------------------------------------------------------
-- 8. Inventory reconciliation: seller-only when called by a client
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.reconcile_inventory_holds(p_variant_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_actual_reserved INTEGER;
    v_stock INTEGER;
    v_new_reserved INTEGER;
BEGIN
    IF auth.uid() IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM public.product_variants pv
        JOIN public.products p ON p.product_id = pv.product_id
        JOIN public.users u ON u.user_id = auth.uid()
        WHERE pv.variant_id = p_variant_id
          AND p.seller_id = auth.uid()
          AND u.role = 'SELLER'
          AND u.is_seller_verified IS TRUE
    ) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not your inventory');
    END IF;

    SELECT COALESCE(SUM(quantity), 0)
      INTO v_actual_reserved
      FROM public.orders
     WHERE variant_id = p_variant_id
       AND status IN ('PLACED', 'ACCEPTED', 'PAYMENT_SUBMITTED', 'PAYMENT_VERIFIED',
                      'MEETUP_SCHEDULED', 'NEEDS_REVIEW', 'REFUND_REQUESTED');

    UPDATE public.inventory
       SET reserved_qty = v_actual_reserved,
           last_updated = now()
     WHERE variant_id = p_variant_id
     RETURNING stock_qty, reserved_qty INTO v_stock, v_new_reserved;

    RETURN jsonb_build_object(
        'success', true,
        'variant_id', p_variant_id,
        'stock_qty', v_stock,
        'reserved_qty', v_new_reserved,
        'available_qty', GREATEST(0, v_stock - v_new_reserved)
    );
END;
$$;

REVOKE ALL ON FUNCTION public.reconcile_inventory_holds(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.reconcile_inventory_holds(UUID) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 9. Self-check: fail the migration if a known hole is still open
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    IF has_column_privilege('authenticated', 'public.orders', 'handoff_otp', 'SELECT') IS TRUE
       OR has_column_privilege('authenticated', 'public.orders', 'return_otp', 'SELECT') IS TRUE THEN
        RAISE EXCEPTION 'P0 verification failed: OTP columns are still readable by authenticated';
    END IF;

    IF has_table_privilege('authenticated', 'public.orders', 'INSERT') IS TRUE THEN
        RAISE EXCEPTION 'P0 verification failed: authenticated can still INSERT orders';
    END IF;

    IF has_function_privilege('anon', 'public.delete_user_account()', 'EXECUTE') IS TRUE THEN
        RAISE EXCEPTION 'P0 verification failed: anon can still delete accounts';
    END IF;

    IF has_function_privilege('anon', 'public.place_orders_atomic(jsonb,text)', 'EXECUTE') IS TRUE THEN
        RAISE EXCEPTION 'P0 verification failed: anon can still place orders';
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'inventory'
          AND policyname NOT IN ('inventory_select_public', 'inventory_insert_seller', 'inventory_update_seller')
    ) THEN
        RAISE EXCEPTION 'P0 verification failed: unexpected inventory policy remains';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'product_reviews'
          AND policyname = 'reviews_insert_buyer'
    ) THEN
        RAISE EXCEPTION 'P0 verification failed: review insert policy missing';
    END IF;
END $$;

NOTIFY pgrst, 'reload schema';
