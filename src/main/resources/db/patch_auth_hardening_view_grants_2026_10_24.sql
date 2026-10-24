-- ============================================================================
-- W1 live repair: revoke anon SELECT from authenticated-only views
-- ============================================================================
-- Supabase's default privileges grant anon SELECT on newly created public
-- views. The original W1 migration created my_profile, admin_kyc_queue, and
-- my_store_profile and granted authenticated SELECT, but did not revoke the
-- inherited anon/PUBLIC grants.
--
-- This patch is idempotent and safe to run after the W1 migration. It does not
-- touch public_profiles or public_store_profiles, which are intentionally
-- readable by anon.
--
-- Expected result after rerunning auth_w1_verify_2026_10_24.sql:
--   147 checks, 0 failures.
-- ============================================================================

BEGIN;

REVOKE ALL PRIVILEGES ON public.my_profile FROM anon, PUBLIC;
REVOKE ALL PRIVILEGES ON public.admin_kyc_queue FROM anon, PUBLIC;
REVOKE ALL PRIVILEGES ON public.my_store_profile FROM anon, PUBLIC;

GRANT SELECT ON public.my_profile TO authenticated;
GRANT SELECT ON public.admin_kyc_queue TO authenticated;
GRANT SELECT ON public.my_store_profile TO authenticated;

COMMIT;

-- Ask PostgREST to reload its schema/privilege cache immediately.
NOTIFY pgrst, 'reload schema';
