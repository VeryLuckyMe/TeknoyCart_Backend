-- ============================================================================
-- Migration V16: Cascade Product Review Deletion, Store Profiles FK & Buyer @cit.edu Guard
-- Description:
--   1. Ensure public.store_profiles table and its FK to public.users(user_id)
--      exist in versioned SQL so PostgREST embedded users(...) joins succeed.
--   2. Enforce @cit.edu email domain server-side inside enforce_default_role_on_insert()
--      whenever role = 'BUYER' so direct Supabase signUp calls cannot bypass the client check.
--   3. Clean up any existing orphaned rows in public.product_reviews where
--      product_id does not correspond to a live row in public.products.
--   4. Add a SECURITY DEFINER trigger (trg_delete_product_reviews_on_product_delete)
--      so that deleting a product automatically deletes its associated
--      product_reviews rows even when RLS only grants direct DELETE to buyers.
-- ============================================================================

-- 1. Ensure public.store_profiles exists in versioned SQL with an explicit FK to public.users(user_id)
CREATE TABLE IF NOT EXISTS public.store_profiles (
    store_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id UUID NOT NULL UNIQUE,
    store_name TEXT NOT NULL DEFAULT '',
    description TEXT,
    banner_url TEXT,
    contact_number TEXT,
    usual_active_hours TEXT,
    preferred_meetup_spots TEXT,
    welcome_message_template TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'public.store_profiles'::regclass
           AND contype = 'f'
           AND confrelid = 'public.users'::regclass
    ) THEN
        -- Remove any dangling store_profiles rows without a matching public.users row before adding FK
        DELETE FROM public.store_profiles sp
         WHERE NOT EXISTS (
             SELECT 1 FROM public.users u WHERE u.user_id = sp.seller_id
         );

        ALTER TABLE public.store_profiles
            ADD CONSTRAINT store_profiles_seller_id_fkey
            FOREIGN KEY (seller_id)
            REFERENCES public.users(user_id)
            ON DELETE CASCADE;
    END IF;
END $$;

-- 2. Server-side @cit.edu domain enforcement for BUYER role in enforce_default_role_on_insert()
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

    -- Buyers must use an official @cit.edu institutional email address.
    -- Sellers (Departments/Orgs and Student Personal Stores) may register with external emails
    -- because they are gated by mandatory Seller KYC verification upon login.
    IF NEW.role::text = 'BUYER' AND lower(trim(v_email)) NOT LIKE '%@cit.edu' THEN
        RAISE EXCEPTION 'Strict Security Policy: Buyers must register with an official @cit.edu email address.';
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

-- 3. Clean up existing orphaned product_reviews rows whose product was already deleted
DO $$
BEGIN
    IF to_regclass('public.product_reviews') IS NOT NULL
       AND to_regclass('public.products') IS NOT NULL THEN
        DELETE FROM public.product_reviews pr
         WHERE NOT EXISTS (
             SELECT 1
               FROM public.products p
              WHERE p.product_id::text = pr.product_id
         );
    END IF;
END $$;

-- 4. Trigger function to cascade-delete product_reviews when a product is deleted
CREATE OR REPLACE FUNCTION public.cleanup_product_reviews_on_product_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF to_regclass('public.product_reviews') IS NOT NULL THEN
        DELETE FROM public.product_reviews
         WHERE product_id = OLD.product_id::text;
    END IF;
    RETURN OLD;
END;
$$;

DROP TRIGGER IF EXISTS trg_delete_product_reviews_on_product_delete ON public.products;
CREATE TRIGGER trg_delete_product_reviews_on_product_delete
    AFTER DELETE ON public.products
    FOR EACH ROW
    EXECUTE FUNCTION public.cleanup_product_reviews_on_product_delete();

-- 5. Resolve Supabase Security Advisor CRITICAL: auth_users_exposed on public.public_profiles
--    Rebuild public_profiles with security_invoker = true and NO auth.users join,
--    and drop unused SECURITY DEFINER views (my_store_profile, public_store_profiles).
CREATE OR REPLACE VIEW public.public_profiles
    WITH (security_invoker = true)
AS
SELECT u.user_id,
       u.full_name,
       NULL::text AS avatar_url,
       u.is_seller_verified,
       u.last_seen_at
FROM public.users u;

REVOKE ALL PRIVILEGES ON public.public_profiles FROM anon, PUBLIC;
GRANT SELECT ON public.public_profiles TO authenticated;

DROP VIEW IF EXISTS public.my_store_profile;
DROP VIEW IF EXISTS public.public_store_profiles;

-- 6. PostgREST schema cache refresh
NOTIFY pgrst, 'reload schema';
