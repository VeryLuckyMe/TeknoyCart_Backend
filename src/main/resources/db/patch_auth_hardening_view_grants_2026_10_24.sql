-- ============================================================================
-- W1 live repair: revoke anon SELECT from authenticated-only views
-- ============================================================================
-- Supabase's default privileges grant anon SELECT on newly created public
-- views. The original W1 migration created my_profile, admin_kyc_queue, and
-- my_store_profile and granted authenticated SELECT, but did not revoke the
-- inherited anon/PUBLIC grants.
--
-- This patch is idempotent and safe to run after the W1 migration. It does not
-- touch public_profiles. public_store_profiles and my_store_profile were
-- dropped on 2026-11-01 by patch_drop_unused_definer_views_2026_11_01.sql;
-- the my_store_profile grant below is guarded so this file stays runnable
-- whether or not the drop has been applied yet.
--
-- Expected result after rerunning auth_w1_verify_2026_10_24.sql:
--   0 failures.
-- ============================================================================

BEGIN;

REVOKE ALL PRIVILEGES ON public.my_profile FROM anon, PUBLIC;
REVOKE ALL PRIVILEGES ON public.admin_kyc_queue FROM anon, PUBLIC;

GRANT SELECT ON public.my_profile TO authenticated;
GRANT SELECT ON public.admin_kyc_queue TO authenticated;

DO $$
BEGIN
    IF to_regclass('public.my_store_profile') IS NOT NULL THEN
        EXECUTE 'REVOKE ALL PRIVILEGES ON public.my_store_profile FROM anon, PUBLIC';
        EXECUTE 'GRANT SELECT ON public.my_store_profile TO authenticated';
    END IF;
END $$;

COMMIT;

-- Ask PostgREST to reload its schema/privilege cache immediately.
NOTIFY pgrst, 'reload schema';
