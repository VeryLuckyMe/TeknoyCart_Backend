-- ============================================================================
-- TeknoyCart public_profiles hardening + store_profiles grant repair
-- Date: 2026-10-31
-- Apply: Supabase SQL editor as postgres. Forward-only.
-- Verify: src/main/resources/db/diagnostics/verify_public_profiles_hardening_2026_10_31.sql
-- ============================================================================
-- Why:
--   Supabase Security Advisor flagged public.public_profiles twice:
--     1. security_definer_view: the view ran as its owner and bypassed RLS.
--     2. auth_users_exposed: the view joined auth.users for avatar metadata
--        and was readable by anon.
--   It also exposed every user's full_name, is_seller_verified and
--   last_seen_at to anonymous callers.
--
-- What changes:
--   1. Rebuild public_profiles as security_invoker = true so public.users RLS
--      and column grants apply to the caller.
--   2. Remove the auth.users join. avatar_url is retained as NULL to preserve
--      the existing column contract; no current app screen selects it.
--   3. Revoke anon/PUBLIC SELECT. All current public_profiles reads happen
--      after sign-in: order history, inbox, storefront and presence.
--   4. Repair store_profiles: the Spring Boot runner used to grant table-level
--      SELECT on every boot, which overrides W1's column-level grants and
--      exposes contact_number. Revoke the table-level grant and restore only
--      the safe columns.
--
-- The private PII views (my_profile, admin_kyc_queue, my_order_codes) and the
-- public storefront view (public_store_profiles) remain SECURITY DEFINER by
-- design. See README-auth-hardening.md before changing them.
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 1. public_profiles: RLS-respecting, authenticated-only, no auth.users
-- ----------------------------------------------------------------------------
-- CREATE OR REPLACE keeps the view OID and column order. The column list must
-- stay identical: user_id, full_name, avatar_url, is_seller_verified,
-- last_seen_at. avatar_url is a constant because public.users has no avatar
-- column and the app reads own avatars from Supabase Auth metadata instead.
CREATE OR REPLACE VIEW public.public_profiles
    WITH (security_invoker = true)
AS
SELECT u.user_id,
       u.full_name,
       NULL::text AS avatar_url,
       u.is_seller_verified,
       u.last_seen_at
FROM public.users u;

-- Supabase default privileges may have granted anon/PUBLIC on creation.
REVOKE ALL PRIVILEGES ON public.public_profiles FROM anon, PUBLIC;
GRANT SELECT ON public.public_profiles TO authenticated;

COMMENT ON VIEW public.public_profiles IS
    'RLS-respecting public profile card for signed-in users only. No auth.users join and no PII. avatar_url is intentionally NULL for column compatibility.';

-- ----------------------------------------------------------------------------
-- 2. store_profiles: remove the table-level grant that re-exposed PII
-- ----------------------------------------------------------------------------
-- has_table_privilege() is false when only column-level grants exist, which is
-- the W1 contract. The backend runner no longer grants table-level SELECT.
REVOKE ALL PRIVILEGES ON TABLE public.store_profiles FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE public.store_profiles FROM PUBLIC;

DO $$
DECLARE
    v_safe text;
BEGIN
    SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position)
      INTO v_safe
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name = 'store_profiles'
       AND column_name IN ('store_id', 'seller_id', 'store_name', 'description',
                           'banner_url', 'usual_active_hours',
                           'preferred_meetup_spots', 'welcome_message_template',
                           'created_at');

    IF v_safe IS NOT NULL THEN
        EXECUTE format(
            'GRANT SELECT (%s) ON public.store_profiles TO anon, authenticated',
            v_safe);
    END IF;
END $$;

COMMIT;

-- Ask PostgREST to reload its schema/privilege cache immediately.
NOTIFY pgrst, 'reload schema';
