-- ============================================================================
-- TeknoyCart: restore W1 column-level grants on public.users
-- Apply manually in the Supabase SQL editor. Safe to re-run.
-- ============================================================================
--
-- Why this exists
-- ---------------
-- auth_w1_verify_2026_10_24.sql reported 35 grant failures, every one
-- "expected false, actual true". That was not 35 problems, it was one:
--
--   has_table_privilege('anon',          'public.users', 'SELECT') = true
--   has_table_privilege('authenticated', 'public.users', 'SELECT') = true
--   has_table_privilege('authenticated', 'public.users', 'INSERT') = true
--   has_table_privilege('authenticated', 'public.users', 'UPDATE') = true
--
-- A table-level grant implies every column, so G14-G23, G30-G32, G45-G52 and
-- G64-G73 all read true. The signature - anon SELECT only, authenticated
-- SELECT/INSERT/UPDATE only, no DELETE for anyone - is exactly what
-- fix_users_select_grant.sql produced.
--
-- That exposure is real because "users_select_authenticated" is
-- `FOR SELECT TO authenticated USING (true)`. RLS filters no rows, so column
-- grants are the only boundary. With the table-level grant in place, any
-- signed-in student could read every user's email, student_id, contact,
-- gcash_number - and password_hash - through PostgREST.
--
-- The 42501 error that fix_users_select_grant.sql was written for is already
-- solved properly: getEnrichedProfile now reads own PII from
-- public.my_profile (security definer, WHERE user_id = auth.uid()), and no app
-- query selects student_id, contact or gcash_number from the base table.
-- Every base-table users query stays inside this allowlist, including the
-- embedded `users(full_name, is_seller_verified)` selects in the feed.
--
-- This patch reproduces section 2 of migration_auth_hardening_2026_10_24.sql
-- exactly, so it is idempotent and safe to re-run.
-- ============================================================================

BEGIN;

REVOKE ALL PRIVILEGES ON TABLE public.users FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE public.users FROM PUBLIC;

-- A table-level GRANT also shows up as column privileges in the catalog.
-- Clear every explicit column grant before re-granting the safe set.
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT column_name
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'users'
    LOOP
        EXECUTE format(
            'REVOKE SELECT (%1$I), INSERT (%1$I), UPDATE (%1$I), REFERENCES (%1$I) '
            'ON TABLE public.users FROM anon, authenticated, PUBLIC',
            r.column_name);
    END LOOP;
END $$;

-- Public profile columns only. No email, student ID, contact, GCash, password,
-- verification or lockout columns on the base table.
GRANT SELECT (user_id, full_name, role, is_seller_verified, last_seen_at)
    ON public.users TO authenticated;

-- Signup payload: the trigger supplies email/password_hash/flags/token.
GRANT INSERT (user_id, full_name, role, student_id, seller_type)
    ON public.users TO authenticated;

-- Own-row profile edits; security columns are blocked by the UPDATE trigger.
GRANT UPDATE (full_name, contact, gcash_number, last_seen_at)
    ON public.users TO authenticated;

COMMIT;

-- Ask PostgREST to reload its schema/privilege cache immediately.
NOTIFY pgrst, 'reload schema';
