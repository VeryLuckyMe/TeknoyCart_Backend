-- ============================================================================
-- TeknoyCart: Admin Dispute & Order Verification Hardening (V13)
-- ============================================================================
-- Problem:
-- 1. Orders with status DISPUTED or NEEDS_REVIEW (such as reported no-shows)
--    were invisible or unresolvable in the Admin Order Verification Hub.
-- 2. PostgREST relational queries from the admin portal joining public.users
--    for buyer full_name/email failed if column-level SELECT on email was missing.
-- 3. Direct admin updates for dispute adjudication rulings required column UPDATE
--    grant on dispute_ruling on public.orders.
--
-- Solution:
-- 1. Ensure authenticated users have SELECT on public profile attributes
--    (user_id, full_name, email, role, is_seller_verified, is_verified, created_at)
--    while keeping sensitive columns (password_hash, verification_token,
--    lock_until, failed_attempts) strictly revoked.
-- 2. Grant UPDATE (status, refund_reference, dispute_ruling) on public.orders
--    to authenticated. PostgreSQL RLS policy `orders_update_admin` strictly
--    enforces that ONLY users where `role = 'ADMIN'` can execute UPDATE.
-- 3. Reload PostgREST schema cache.
-- ============================================================================

-- 1. Ensure SELECT privileges on non-sensitive columns of public.users
GRANT SELECT (
    user_id,
    full_name,
    email,
    role,
    is_seller_verified,
    is_verified,
    created_at
) ON TABLE public.users TO authenticated;

-- 2. Grant admin-updatable columns on public.orders
GRANT UPDATE (
    status,
    refund_reference,
    dispute_ruling
) ON TABLE public.orders TO authenticated;

-- 3. Reload schema cache for PostgREST
NOTIFY pgrst, 'reload schema';
