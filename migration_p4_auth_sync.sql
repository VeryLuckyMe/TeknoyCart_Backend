-- ==============================================================================
-- Migration P4: Synchronize Account Lockout to Supabase Auth (GoTrue)
-- Description: Sets auth.users.banned_until whenever public.users.is_locked/lock_until
--              is set, closing any direct bypass attacks against Supabase Auth.
-- ==============================================================================

CREATE OR REPLACE FUNCTION public.sync_user_lockout_to_auth()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
BEGIN
    IF NEW.is_locked = true AND NEW.lock_until IS NOT NULL AND NEW.lock_until > NOW() THEN
        UPDATE auth.users
        SET banned_until = NEW.lock_until
        WHERE id = NEW.user_id;
    ELSIF (OLD.is_locked = true AND NEW.is_locked = false) 
       OR (NEW.lock_until IS NULL) 
       OR (NEW.lock_until <= NOW()) THEN
        UPDATE auth.users
        SET banned_until = NULL
        WHERE id = NEW.user_id;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sync_user_lockout_to_auth ON public.users;
CREATE TRIGGER trg_sync_user_lockout_to_auth
    AFTER UPDATE OF is_locked, lock_until ON public.users
    FOR EACH ROW
    EXECUTE FUNCTION public.sync_user_lockout_to_auth();

-- Synchronize role and verification status to auth.users raw_user_meta_data
CREATE OR REPLACE FUNCTION public.sync_user_role_to_auth()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
BEGIN
    UPDATE auth.users
    SET raw_user_meta_data = 
        COALESCE(raw_user_meta_data, '{}'::jsonb) || 
        jsonb_build_object(
            'role', NEW.role,
            'is_seller_verified', NEW.is_seller_verified
        )
    WHERE id = NEW.user_id;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sync_user_role_to_auth ON public.users;
CREATE TRIGGER trg_sync_user_role_to_auth
    AFTER UPDATE OF role, is_seller_verified ON public.users
    FOR EACH ROW
    EXECUTE FUNCTION public.sync_user_role_to_auth();

