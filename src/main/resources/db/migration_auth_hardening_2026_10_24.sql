-- ============================================================================
-- TeknoyCart W1 Auth Hardening
-- Date: 2026-10-24
-- Apply: Supabase SQL editor as postgres. Forward-only.
-- Verify: src/main/resources/db/diagnostics/auth_w1_verify_2026_10_24.sql
-- ============================================================================
-- Scope:
--   1. Close the trigger escalation (SECURITY INVOKER + retained role guard).
--   2. Make signup authoritative: BUYER/SELLER only; JWT email; safe flags.
--   3. Replace broad users grants with public-column grants and own-PII views.
--   4. Remove the legacy KYC ALL/USING(true) policy.
--   5. Require a verified seller for product writes.
--   6. Add the student-ID availability RPC and the V9 ledger row.
--
-- The migration does not touch the Realtime publication (already correct) and
-- does not disable RLS. The app-side changes must ship with this SQL:
-- signup pre-check/insert, store upsert on seller_id, getEnrichedProfile ->
-- my_profile, email dialog, order-history contact and reserved-orders email
-- fallbacks, web Login/App boot -> my_profile + is_verified, web Moderation ->
-- admin_kyc_queue.
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 1. KYC: remove the legacy permissive ALL policy
-- ----------------------------------------------------------------------------
-- W0 C04 found "Admins can manage all verifications" as ALL/PUBLIC/USING(true).
-- Permissive policies OR together, so it bypassed the user PENDING/REJECTED
-- policies and allowed any client to self-approve. The remaining admin policy
-- ("Admins can view and manage all verifications") carries the ADMIN predicate.
DROP POLICY IF EXISTS "Admins can manage all verifications"
    ON public.seller_verifications;

-- ----------------------------------------------------------------------------
-- 2. users: replace table grants with public-column grants
-- ----------------------------------------------------------------------------
REVOKE ALL PRIVILEGES ON TABLE public.users FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE public.users FROM PUBLIC;

-- A table-level GRANT can appear as a column privilege in the catalog. Revoke
-- any explicit column grants too, so a later safe-column grant is the only
-- remaining path.
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

-- ----------------------------------------------------------------------------
-- 3. Own-PII and public-profile views (owner-run; not security_invoker)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW public.my_profile
    WITH (security_invoker = false)
AS
SELECT user_id,
       full_name,
       email,
       role,
       is_verified,
       is_seller_verified,
       student_id,
       contact,
       gcash_number,
       seller_type,
       last_seen_at,
       created_at
FROM public.users
WHERE user_id = auth.uid();

-- Supabase default privileges grant anon SELECT on newly created public views.
-- Revoke that inherited access explicitly; this view is authenticated-only.
REVOKE ALL PRIVILEGES ON public.my_profile FROM anon, PUBLIC;
GRANT SELECT ON public.my_profile TO authenticated;

CREATE OR REPLACE VIEW public.public_profiles
    WITH (security_invoker = false)
AS
SELECT u.user_id,
       u.full_name,
       au.raw_user_meta_data ->> 'avatar_url' AS avatar_url,
       u.is_seller_verified,
       u.last_seen_at
FROM public.users u
LEFT JOIN auth.users au ON au.id = u.user_id;

GRANT SELECT ON public.public_profiles TO anon, authenticated;

CREATE OR REPLACE VIEW public.admin_kyc_queue
    WITH (security_invoker = false)
AS
SELECT u.user_id,
       u.full_name,
       u.email,
       u.student_id,
       u.role,
       u.is_verified,
       u.is_seller_verified,
       u.created_at,
       sv.id AS verification_id,
       sv.status,
       sv.seller_type,
       sv.rejection_reason,
       sv.updated_at AS verification_updated_at,
       sv.created_at AS submitted_at
FROM public.users u
LEFT JOIN LATERAL (
    SELECT s.id,
           s.status,
           s.seller_type,
           s.rejection_reason,
           s.created_at,
           s.updated_at
    FROM public.seller_verifications s
    WHERE s.user_id = u.user_id
    ORDER BY s.created_at DESC
    LIMIT 1
) sv ON true
WHERE EXISTS (
    SELECT 1
    FROM public.users admin
    WHERE admin.user_id = auth.uid()
      AND admin.role = 'ADMIN'
      AND admin.is_verified = true
      AND admin.is_locked = false
);

-- Supabase default privileges grant anon SELECT on newly created public views.
-- Revoke that inherited access explicitly; this view is authenticated-only.
REVOKE ALL PRIVILEGES ON public.admin_kyc_queue FROM anon, PUBLIC;
GRANT SELECT ON public.admin_kyc_queue TO authenticated;

-- ----------------------------------------------------------------------------
-- 4. Store profiles: safe public columns, own PII via views
-- ----------------------------------------------------------------------------
REVOKE ALL PRIVILEGES ON TABLE public.store_profiles FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE public.store_profiles FROM PUBLIC;

DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT column_name
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'store_profiles'
    LOOP
        EXECUTE format(
            'REVOKE SELECT (%1$I), INSERT (%1$I), UPDATE (%1$I), REFERENCES (%1$I) '
            'ON TABLE public.store_profiles FROM anon, authenticated, PUBLIC',
            r.column_name);
    END LOOP;
END $$;

-- Keep the existing PostgREST joins and public storefront working with
-- column-level SELECT. contact_number is deliberately excluded.
DO $$
DECLARE
    v_safe text;
    v_insert text;
    v_update text;
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

    SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position)
      INTO v_insert
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name = 'store_profiles'
       AND column_name IN ('seller_id', 'store_name', 'description',
                           'banner_url', 'contact_number',
                           'usual_active_hours', 'preferred_meetup_spots',
                           'welcome_message_template');

    IF v_insert IS NOT NULL THEN
        EXECUTE format(
            'GRANT INSERT (%s) ON public.store_profiles TO authenticated',
            v_insert);
    END IF;

    SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position)
      INTO v_update
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name = 'store_profiles'
       AND column_name IN ('seller_id', 'store_name', 'description', 'banner_url',
                           'contact_number', 'usual_active_hours',
                           'preferred_meetup_spots', 'welcome_message_template');

    IF v_update IS NOT NULL THEN
        EXECUTE format(
            'GRANT UPDATE (%s) ON public.store_profiles TO authenticated',
            v_update);
    END IF;
END $$;

-- Own-row full row (including contact_number); authenticated only.
CREATE OR REPLACE VIEW public.my_store_profile
    WITH (security_invoker = false)
AS
SELECT *
FROM public.store_profiles
WHERE seller_id = auth.uid();

-- Supabase default privileges grant anon SELECT on newly created public views.
-- Revoke that inherited access explicitly; this view is authenticated-only.
REVOKE ALL PRIVILEGES ON public.my_store_profile FROM anon, PUBLIC;
GRANT SELECT ON public.my_store_profile TO authenticated;

-- Public storefront view. Built from the columns that actually exist, so the
-- migration does not fail if an optional store column was never added.
DO $$
DECLARE
    v_cols text;
BEGIN
    SELECT string_agg('sp.' || quote_ident(column_name), ', ' ORDER BY ordinal_position)
      INTO v_cols
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name = 'store_profiles'
       AND column_name IN ('store_id', 'seller_id', 'store_name', 'description',
                           'banner_url', 'usual_active_hours',
                           'preferred_meetup_spots', 'welcome_message_template',
                           'created_at');

    IF v_cols IS NOT NULL THEN
        EXECUTE format(
            'CREATE OR REPLACE VIEW public.public_store_profiles ' ||
            'WITH (security_invoker = false) AS ' ||
            'SELECT %s, u.full_name AS owner_name, u.is_seller_verified ' ||
            'FROM public.store_profiles sp ' ||
            'JOIN public.users u ON u.user_id = sp.seller_id',
            v_cols);
    END IF;
END $$;

GRANT SELECT ON public.public_store_profiles TO anon, authenticated;

-- ----------------------------------------------------------------------------
-- 5. Student-ID availability RPC (no anon SELECT on users.student_id)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.is_student_id_available(p_student_id text)
RETURNS boolean
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT NOT EXISTS (
        SELECT 1
        FROM public.users
        WHERE student_id = p_student_id
    );
$$;

REVOKE ALL ON FUNCTION public.is_student_id_available(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.is_student_id_available(text)
    TO anon, authenticated;

-- ----------------------------------------------------------------------------
-- 6. Trigger functions: INVOKER + real-caller guard
-- ----------------------------------------------------------------------------
-- C1 was DEFINER + guard: under DEFINER every caller looked like postgres, so
-- the guard always returned. Under INVOKER the guard sees the real caller;
-- JDBC and SECURITY DEFINER RPCs still see postgres and are allowed.
CREATE OR REPLACE FUNCTION public.protect_user_security_columns()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $function$
DECLARE
    jwt_role text := current_setting('request.jwt.claim.role', true);
BEGIN
    IF jwt_role = 'service_role'
       OR current_user LIKE 'postgres%'
       OR session_user LIKE 'postgres%' THEN
        RETURN NEW;
    END IF;

    IF NEW.role IS DISTINCT FROM OLD.role THEN
        RAISE EXCEPTION 'Modifying user role directly is forbidden.';
    END IF;
    IF NEW.is_verified IS DISTINCT FROM OLD.is_verified THEN
        RAISE EXCEPTION 'Modifying is_verified directly is forbidden.';
    END IF;
    IF NEW.is_seller_verified IS DISTINCT FROM OLD.is_seller_verified THEN
        RAISE EXCEPTION 'Modifying is_seller_verified directly is forbidden.';
    END IF;
    IF NEW.is_locked IS DISTINCT FROM OLD.is_locked
       OR NEW.lock_until IS DISTINCT FROM OLD.lock_until THEN
        RAISE EXCEPTION 'Modifying account lockout parameters directly is forbidden.';
    END IF;
    IF NEW.failed_attempts IS DISTINCT FROM OLD.failed_attempts THEN
        RAISE EXCEPTION 'Modifying failed_attempts directly is forbidden.';
    END IF;
    IF NEW.email IS DISTINCT FROM OLD.email THEN
        RAISE EXCEPTION 'Modifying email directly is forbidden.';
    END IF;
    IF NEW.password_hash IS DISTINCT FROM OLD.password_hash THEN
        RAISE EXCEPTION 'Modifying password_hash directly is forbidden.';
    END IF;
    IF NEW.verification_token IS DISTINCT FROM OLD.verification_token THEN
        RAISE EXCEPTION 'Modifying verification_token directly is forbidden.';
    END IF;
    IF NEW.token_expires_at IS DISTINCT FROM OLD.token_expires_at THEN
        RAISE EXCEPTION 'Modifying token_expires_at directly is forbidden.';
    END IF;

    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION public.enforce_default_role_on_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $function$
DECLARE
    jwt_role text := current_setting('request.jwt.claim.role', true);
    v_email text;
BEGIN
    IF jwt_role = 'service_role'
       OR current_user LIKE 'postgres%'
       OR session_user LIKE 'postgres%' THEN
        RETURN NEW;
    END IF;

    v_email := COALESCE(auth.jwt() ->> 'email', '');
    IF v_email = '' THEN
        RAISE EXCEPTION 'A verified Supabase session email is required.';
    END IF;
    IF NEW.email IS NOT NULL AND NEW.email <> v_email THEN
        RAISE EXCEPTION 'The email must match the authenticated session.';
    END IF;

    IF NEW.role IS NULL THEN
        NEW.role := 'BUYER';
    ELSIF NEW.role::text NOT IN ('BUYER', 'SELLER') THEN
        RAISE EXCEPTION 'Only BUYER or SELLER registration is allowed.';
    END IF;

    NEW.email := v_email;
    NEW.password_hash := 'SUPABASE_AUTH_MANAGED';
    NEW.is_verified := false;
    NEW.is_seller_verified := false;
    NEW.is_locked := false;
    NEW.failed_attempts := 0;
    NEW.lock_until := NULL;
    NEW.verification_token := NULL;
    NEW.token_expires_at := NULL;

    RETURN NEW;
END;
$function$;

-- ----------------------------------------------------------------------------
-- 7. Product writes require an approved seller
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS products_insert_seller ON public.products;
CREATE POLICY products_insert_seller
    ON public.products
    FOR INSERT
    TO authenticated
    WITH CHECK (
        auth.uid() = seller_id
        AND EXISTS (
            SELECT 1
            FROM public.users u
            WHERE u.user_id = auth.uid()
              AND u.role = 'SELLER'
              AND u.is_seller_verified = true
        )
    );

DROP POLICY IF EXISTS products_update_seller ON public.products;
CREATE POLICY products_update_seller
    ON public.products
    FOR UPDATE
    TO authenticated
    USING (
        auth.uid() = seller_id
        AND EXISTS (
            SELECT 1
            FROM public.users u
            WHERE u.user_id = auth.uid()
              AND u.role = 'SELLER'
              AND u.is_seller_verified = true
        )
    )
    WITH CHECK (
        auth.uid() = seller_id
        AND EXISTS (
            SELECT 1
            FROM public.users u
            WHERE u.user_id = auth.uid()
              AND u.role = 'SELLER'
              AND u.is_seller_verified = true
        )
    );

-- ----------------------------------------------------------------------------
-- 8. Keep RLS on; do not touch the Realtime publication
-- ----------------------------------------------------------------------------
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chats ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.inquiries ENABLE ROW LEVEL SECURITY;

-- ----------------------------------------------------------------------------
-- 9. Ledger
-- ----------------------------------------------------------------------------
INSERT INTO public.schema_migrations (version, applied_at)
VALUES ('V9__auth_hardening', now())
ON CONFLICT (version) DO NOTHING;

NOTIFY pgrst, 'reload schema';

COMMIT;
