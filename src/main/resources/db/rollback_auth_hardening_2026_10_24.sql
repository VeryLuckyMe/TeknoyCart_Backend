-- ============================================================================
-- TeknoyCart W1 Auth Hardening - emergency rollback
-- Date: 2026-10-24
-- Apply: Supabase SQL editor as postgres, inside the transaction below.
-- WARNING: This restores the pre-W1 broad grants and the legacy KYC policy.
--          It is only for a failed migration / demo rescue. Roll back the
--          matching app/web commits too, then restore the W0 export if needed.
-- ============================================================================

BEGIN;

-- 1. Restore the legacy permissive KYC policy
DROP POLICY IF EXISTS "Admins can manage all verifications"
    ON public.seller_verifications;
CREATE POLICY "Admins can manage all verifications"
    ON public.seller_verifications
    FOR ALL
    TO public
    USING (true);

-- 2. Drop W1 views and the student-ID RPC
DROP VIEW IF EXISTS public.my_profile;
DROP VIEW IF EXISTS public.public_profiles;
DROP VIEW IF EXISTS public.admin_kyc_queue;
DROP VIEW IF EXISTS public.my_store_profile;
DROP VIEW IF EXISTS public.public_store_profiles;
DROP FUNCTION IF EXISTS public.is_student_id_available(text);

-- 3. Restore the pre-W1 broad table grants
GRANT ALL PRIVILEGES ON TABLE public.users TO anon, authenticated;
GRANT ALL PRIVILEGES ON TABLE public.store_profiles TO anon, authenticated;

-- 4. Restore the pre-W1 product policies
DROP POLICY IF EXISTS products_insert_seller ON public.products;
CREATE POLICY products_insert_seller
    ON public.products
    FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = seller_id);

DROP POLICY IF EXISTS products_update_seller ON public.products;
CREATE POLICY products_update_seller
    ON public.products
    FOR UPDATE
    TO authenticated
    USING (auth.uid() = seller_id)
    WITH CHECK (auth.uid() = seller_id);

-- 5. Restore the pre-W1 trigger bodies exactly as captured in W0 E02
CREATE OR REPLACE FUNCTION public.protect_user_security_columns()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
AS $function$
DECLARE
    jwt_role text := current_setting('request.jwt.claim.role', true);
BEGIN
    -- Allow trusted callers: direct backend/JDBC poolers and service_role
    IF jwt_role IS NULL
       OR jwt_role = 'service_role'
       OR current_user LIKE 'postgres%'
       OR session_user LIKE 'postgres%' THEN
        RETURN NEW;
    END IF;

    -- Block authenticated and anonymous client-side tampering
    IF NEW.role IS DISTINCT FROM OLD.role THEN
        RAISE EXCEPTION 'Modifying user role directly is forbidden.';
    END IF;
    IF NEW.is_verified IS DISTINCT FROM OLD.is_verified THEN
        RAISE EXCEPTION 'Modifying is_verified directly is forbidden.';
    END IF;
    IF NEW.is_seller_verified IS DISTINCT FROM OLD.is_seller_verified THEN
        RAISE EXCEPTION 'Modifying is_seller_verified directly is forbidden.';
    END IF;
    IF NEW.is_locked IS DISTINCT FROM OLD.is_locked OR NEW.lock_until IS DISTINCT FROM OLD.lock_until THEN
        RAISE EXCEPTION 'Modifying account lockout parameters directly is forbidden.';
    END IF;
    IF NEW.failed_attempts IS DISTINCT FROM OLD.failed_attempts THEN
        RAISE EXCEPTION 'Modifying failed_attempts directly is forbidden.';
    END IF;
    IF NEW.email IS DISTINCT FROM OLD.email THEN
        RAISE EXCEPTION 'Modifying email directly is forbidden.';
    END IF;
    IF NEW.verification_token IS DISTINCT FROM OLD.verification_token THEN
        RAISE EXCEPTION 'Modifying verification_token directly is forbidden.';
    END IF;

    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION public.enforce_default_role_on_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
AS $function$
DECLARE
    jwt_role text := current_setting('request.jwt.claim.role', true);
BEGIN
    -- Allow trusted callers: direct backend/JDBC poolers and service_role
    IF jwt_role IS NULL
       OR jwt_role = 'service_role'
       OR current_user LIKE 'postgres%'
       OR session_user LIKE 'postgres%' THEN
        RETURN NEW;
    END IF;

    -- Force all client-side inserts (anon/authenticated) to BUYER with unverified seller status
    NEW.role := 'BUYER';
    NEW.is_seller_verified := false;

    RETURN NEW;
END;
$function$;

-- 6. Remove the W1 ledger row
DELETE FROM public.schema_migrations
 WHERE version = 'V9__auth_hardening';

NOTIFY pgrst, 'reload schema';

COMMIT;
