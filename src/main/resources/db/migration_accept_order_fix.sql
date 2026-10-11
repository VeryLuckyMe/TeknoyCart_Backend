-- ====================================================================
-- TeknoyCart: Accept Order Fix & Order Lifecycle Resilience Migration
-- Platform: Supabase / PostgreSQL
-- ====================================================================

-- 1. Convert payment_method and status from custom ENUM to VARCHAR
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' AND table_name = 'orders' AND column_name = 'payment_method'
        AND data_type = 'USER-DEFINED'
    ) THEN
        ALTER TABLE public.orders ALTER COLUMN payment_method TYPE VARCHAR USING payment_method::TEXT;
    END IF;

    -- Drop default on status if present and convert to VARCHAR
    ALTER TABLE public.orders ALTER COLUMN status DROP DEFAULT;
    ALTER TABLE public.orders ALTER COLUMN status TYPE VARCHAR(50) USING status::TEXT;
    ALTER TABLE public.orders ALTER COLUMN status SET DEFAULT 'PLACED';
EXCEPTION WHEN OTHERS THEN
    NULL;
END $$;

-- 1b. Drop any legacy check constraints on status
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT conname 
        FROM pg_constraint 
        WHERE conrelid = 'public.orders'::regclass 
          AND contype = 'c' 
          AND pg_get_constraintdef(oid) LIKE '%status%'
    ) LOOP
        EXECUTE 'ALTER TABLE public.orders DROP CONSTRAINT IF EXISTS ' || quote_ident(r.conname);
    END LOOP;
END $$;

-- 1c. Drop foreign key constraint on order_audit_logs.actor_id and widen method to TEXT
ALTER TABLE public.order_audit_logs DROP CONSTRAINT IF EXISTS order_audit_logs_actor_id_fkey;
ALTER TABLE public.order_audit_logs ALTER COLUMN method TYPE TEXT;
ALTER TABLE public.order_audit_logs ALTER COLUMN previous_status TYPE VARCHAR(100);
ALTER TABLE public.order_audit_logs ALTER COLUMN new_status TYPE VARCHAR(100);

-- 2. Ensure orders.version has DEFAULT 0 and backfill any NULL rows
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' AND table_name = 'orders' AND column_name = 'version'
    ) THEN
        ALTER TABLE public.orders ALTER COLUMN version SET DEFAULT 0;
        UPDATE public.orders SET version = 0 WHERE version IS NULL;
    ELSE
        ALTER TABLE public.orders ADD COLUMN version BIGINT DEFAULT 0;
    END IF;
END $$;

-- 2b. Ensure PayMongo GCash Payment Hold columns exist
ALTER TABLE public.orders
  ADD COLUMN IF NOT EXISTS paymongo_checkout_session_id TEXT,
  ADD COLUMN IF NOT EXISTS paymongo_checkout_url TEXT,
  ADD COLUMN IF NOT EXISTS payment_expires_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS amount_centavos BIGINT,
  ADD COLUMN IF NOT EXISTS payout_released BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS public.payment_events (
  event_id TEXT PRIMARY KEY,
  order_id UUID REFERENCES public.orders(order_id) ON DELETE SET NULL,
  type TEXT NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3. accept_order_as_seller RPC
-- Mirroring the robust pattern established by cancel_order_as_seller:
-- Provides high-speed, zero-cold-start atomic order acceptance with strict
-- seller authorization and state guards.
CREATE OR REPLACE FUNCTION accept_order_as_seller(p_order_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_order RECORD;
    v_caller_id UUID := auth.uid();
BEGIN
    SELECT * INTO v_order
    FROM public.orders
    WHERE order_id = p_order_id;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Order not found');
    END IF;

    -- Security Guard: Caller must be the seller of this order (or backend service / superuser)
    IF v_caller_id IS NOT NULL AND v_order.seller_id != v_caller_id THEN
        RETURN jsonb_build_object('success', false, 'error', 'Only the seller can accept this order');
    END IF;

    -- State Guard: Order can only be accepted from initial placement states
    IF v_order.status NOT IN ('PLACED', 'PAYMENT_SUBMITTED', 'INQUIRY_SENT', 'PENDING_SELLER_ACCEPT') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Order cannot be accepted from state: ' || v_order.status);
    END IF;

    -- Update order status and increment version
    UPDATE public.orders
    SET status = 'ACCEPTED',
        version = COALESCE(version, 0) + 1
    WHERE order_id = p_order_id;

    -- Insert into order_audit_logs if table exists
    BEGIN
        INSERT INTO public.order_audit_logs (order_id, actor_id, previous_status, new_status, method)
        VALUES (p_order_id, v_caller_id, v_order.status, 'ACCEPTED', 'RPC_SELLER');
    EXCEPTION WHEN OTHERS THEN
        -- Non-blocking: audit failure does not revert status transition
    END;

    RETURN jsonb_build_object(
        'success', true, 
        'order_id', p_order_id, 
        'status', 'ACCEPTED',
        'previous_status', v_order.status
    );
END;
$$;

-- Grant execution permission to authenticated users
GRANT EXECUTE ON FUNCTION accept_order_as_seller(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION accept_order_as_seller(UUID) TO service_role;
