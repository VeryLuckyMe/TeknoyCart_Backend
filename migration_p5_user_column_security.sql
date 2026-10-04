-- ==============================================================================
-- P5 Security Migration: Column-Level Access Control on public.users
-- ==============================================================================
-- Problem: RLS policies on public.users allow authenticated users to SELECT rows,
-- but without column-level restrictions, any authenticated user can dump:
--   - password_hash (BCrypt hashes for seed/backend accounts)
--   - verification_token (email verification hijack tokens)
--   - lock_until & failed_attempts (lockout state enumeration)
--
-- Solution:
-- 1. Revoke broad table SELECT from anon and authenticated.
-- 2. Grant SELECT only on safe, public profile attributes.
-- 3. Ensure backend/JDBC direct connections and service_role retain full access.
-- ==============================================================================

-- 1. Revoke raw table-wide SELECT on users from PostgREST client roles
REVOKE SELECT ON TABLE public.users FROM anon, authenticated;

-- 2. Grant SELECT only on non-sensitive public profile columns
GRANT SELECT (
    user_id,
    full_name,
    email,
    role,
    is_seller_verified,
    is_verified,
    created_at
) ON TABLE public.users TO authenticated;

GRANT SELECT (
    user_id,
    full_name,
    role,
    is_seller_verified
) ON TABLE public.users TO anon;

-- 3. Notify PostgREST to reload schema cache
NOTIFY pgrst, 'reload schema';
