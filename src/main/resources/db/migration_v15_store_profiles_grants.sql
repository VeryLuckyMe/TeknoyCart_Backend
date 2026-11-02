-- ============================================================================
-- Migration V15: store_profiles Write Grants & Cleanup
-- Description:
--   1. Grant INSERT, UPDATE on public.store_profiles to authenticated & service_role.
--      This restores seller onboarding and store management write capability
--      while preserving RLS protection (auth.uid() = seller_id).
--   2. Clean up orphaned test user 38b02b31-97cd-4c9d-9d7f-c84b8c45acae that was left
--      in a failed signup state, freeing up student ID 19-3234-203.
-- ============================================================================

-- 1. Grant INSERT and UPDATE privileges to authenticated and service_role
GRANT INSERT, UPDATE ON public.store_profiles TO authenticated, service_role;

-- 2. Clean up orphaned registration test account if it exists without active transactions
DO $$
DECLARE
    v_orphan_id UUID := '38b02b31-97cd-4c9d-9d7f-c84b8c45acae';
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.users
        WHERE user_id = v_orphan_id AND email = 'mikeljoshx@gmail.com'
    ) THEN
        -- Only delete if user has no orders or products
        IF NOT EXISTS (SELECT 1 FROM public.orders WHERE buyer_id = v_orphan_id OR seller_id = v_orphan_id)
           AND NOT EXISTS (SELECT 1 FROM public.products WHERE seller_id = v_orphan_id) THEN
            DELETE FROM public.users WHERE user_id = v_orphan_id;
            DELETE FROM auth.users WHERE id = v_orphan_id;
        END IF;
    END IF;
END $$;

-- 3. PostgREST schema cache refresh
NOTIFY pgrst, 'reload schema';
