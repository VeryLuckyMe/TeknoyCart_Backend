-- ============================================================================
-- Migration V14: Account Deletion Fix & Active Order Guard
-- Description:
--   1. Make public.order_returns.requested_by NULLABLE and set FK to ON DELETE SET NULL.
--   2. Update delete_user_account() RPC:
--      - Guards against deletion when the user has active/in-flight orders.
--      - Nullifies seller_verifications.reviewed_by.
--      - Nullifies order_returns.requested_by.
--      - Safely removes public.users and auth.users.
--      - Ensures authenticated role has execute privilege.
-- ============================================================================

-- 1. Ensure order_returns allows NULL requested_by and cascades as SET NULL
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'order_returns' AND column_name = 'requested_by'
    ) THEN
        ALTER TABLE public.order_returns ALTER COLUMN requested_by DROP NOT NULL;

        ALTER TABLE public.order_returns DROP CONSTRAINT IF EXISTS order_returns_requested_by_fkey;

        ALTER TABLE public.order_returns
            ADD CONSTRAINT order_returns_requested_by_fkey
            FOREIGN KEY (requested_by)
            REFERENCES public.users(user_id)
            ON DELETE SET NULL;
    END IF;
END $$;

-- 2. Hardened delete_user_account() RPC
CREATE OR REPLACE FUNCTION public.delete_user_account()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_active_orders_count INTEGER := 0;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'User not authenticated';
    END IF;

    -- 2a. Guard against active/in-flight orders (as either buyer or seller)
    SELECT COUNT(*)
      INTO v_active_orders_count
      FROM public.orders
     WHERE (buyer_id = v_uid OR seller_id = v_uid)
       AND status NOT IN ('COMPLETED', 'CANCELLED', 'REJECTED', 'RETURN_COMPLETED');

    IF v_active_orders_count > 0 THEN
        RAISE EXCEPTION 'CANNOT_DELETE_ACTIVE_ORDERS: Cannot delete account while you have active transactions or pending handoffs.';
    END IF;

    -- 2b. Nullify admin review references on seller_verifications
    IF to_regclass('public.seller_verifications') IS NOT NULL THEN
        UPDATE public.seller_verifications
           SET reviewed_by = NULL
         WHERE reviewed_by = v_uid;
    END IF;

    -- 2c. Nullify requested_by on order_returns
    IF to_regclass('public.order_returns') IS NOT NULL THEN
        UPDATE public.order_returns
           SET requested_by = NULL
         WHERE requested_by = v_uid;
    END IF;

    -- 2d. Delete public.users (cascades cleanly to terminal orders, listings, reviews, chats)
    DELETE FROM public.users WHERE user_id = v_uid;

    -- 2e. Permanently delete the Supabase Auth user record
    DELETE FROM auth.users WHERE id = v_uid;
END;
$$;

-- 3. Privileges & PostgREST schema refresh
REVOKE ALL ON FUNCTION public.delete_user_account() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.delete_user_account() TO authenticated, service_role;

NOTIFY pgrst, 'reload schema';

