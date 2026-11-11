-- ============================================================================
-- V17: Checkout idempotency + seller_type visibility  (Defense sprint, Day 1)
-- ============================================================================
-- 1. Idempotent checkout. A retried place_orders_atomic call (double tap,
--    timeout followed by retry) with the same client_request_id returns the
--    orders created by the first call instead of creating duplicates and
--    reserving stock twice.
--
--    Implemented as a 3-argument wrapper around the hardened 2-argument
--    function from migration_p0_security_hardening.sql, so the stock/price/
--    seller checks have exactly one definition. The 2-argument function stays
--    callable for installed app builds that predate this migration.
--
-- 2. seller_type becomes readable by signed-in users (column grant only).
--    The app hides "Make Offer" for ORG (official store / council) sellers,
--    which sell at fixed prices. seller_type is not personal data.
--
-- Idempotent: safe to run more than once.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1a. Columns + uniqueness
-- ----------------------------------------------------------------------------
ALTER TABLE public.orders ADD COLUMN IF NOT EXISTS client_request_id UUID;
ALTER TABLE public.orders ADD COLUMN IF NOT EXISTS line_index INTEGER;

CREATE UNIQUE INDEX IF NOT EXISTS orders_client_request_line_uidx
    ON public.orders (buyer_id, client_request_id, line_index)
    WHERE client_request_id IS NOT NULL;

-- ----------------------------------------------------------------------------
-- 1b. 3-argument idempotent wrapper
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.place_orders_atomic(
    p_lines JSONB,
    p_payment_method TEXT,
    p_client_request_id UUID
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_buyer    UUID := auth.uid();
    v_existing JSONB;
    v_result   JSONB;
    v_order    JSONB;
BEGIN
    IF v_buyer IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'UNAUTHORIZED');
    END IF;

    IF p_client_request_id IS NULL THEN
        RETURN public.place_orders_atomic(p_lines, p_payment_method);
    END IF;

    -- Serialise concurrent retries of the same request (double tap arrives
    -- as two parallel requests). Lock is released at transaction end.
    PERFORM pg_advisory_xact_lock(
        hashtextextended(v_buyer::text || ':' || p_client_request_id::text, 0)
    );

    SELECT jsonb_agg(
               jsonb_build_object(
                   'line_index',   o.line_index,
                   'order_id',     o.order_id,
                   'total_amount', o.total_amount
               ) ORDER BY o.line_index)
      INTO v_existing
      FROM public.orders o
     WHERE o.buyer_id = v_buyer
       AND o.client_request_id = p_client_request_id;

    IF v_existing IS NOT NULL THEN
        RETURN jsonb_build_object('success', true, 'orders', v_existing, 'replayed', true);
    END IF;

    v_result := public.place_orders_atomic(p_lines, p_payment_method);

    IF COALESCE((v_result->>'success')::boolean, false) THEN
        FOR v_order IN SELECT * FROM jsonb_array_elements(v_result->'orders')
        LOOP
            UPDATE public.orders
               SET client_request_id = p_client_request_id,
                   line_index        = (v_order->>'line_index')::integer
             WHERE order_id = (v_order->>'order_id')::uuid
               AND buyer_id = v_buyer;
        END LOOP;
    END IF;

    RETURN v_result;
END;
$$;

REVOKE ALL ON FUNCTION public.place_orders_atomic(JSONB, TEXT, UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.place_orders_atomic(JSONB, TEXT, UUID) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 2. seller_type readable by signed-in users (column-level grant only)
-- ----------------------------------------------------------------------------
GRANT SELECT (seller_type) ON public.users TO authenticated;

-- Ask PostgREST to pick up the new overload immediately.
NOTIFY pgrst, 'reload schema';
