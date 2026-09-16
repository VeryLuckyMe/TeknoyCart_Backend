-- ==============================================================================
-- P4 Migration: Security Hardening — Role INSERT Trigger + UPDATE Trigger Fix (v2)
-- ==============================================================================
-- Fixes trigger bypass check to support Supabase connection poolers where
-- current_user is 'postgres.project_ref' and direct JDBC has null jwt_role.
-- Run this in the Supabase SQL Editor.
-- ==============================================================================

-- 1. INSERT trigger: Force role = 'BUYER' on every new user inserted by
--    authenticated/anon clients. Prevents registering as ADMIN/SELLER via
--    direct Supabase client INSERT.
CREATE OR REPLACE FUNCTION public.enforce_default_role_on_insert()
RETURNS TRIGGER AS $$
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
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS trg_enforce_default_role_on_insert ON public.users;
CREATE TRIGGER trg_enforce_default_role_on_insert
    BEFORE INSERT ON public.users
    FOR EACH ROW
    EXECUTE FUNCTION public.enforce_default_role_on_insert();

-- 2. Extend the existing UPDATE trigger to protect is_seller_verified,
--    role, is_verified, lockout, and failed_attempts from client-side tampering.
CREATE OR REPLACE FUNCTION public.protect_user_security_columns()
RETURNS TRIGGER AS $$
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

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Re-create the UPDATE trigger (idempotent)
DROP TRIGGER IF EXISTS trg_protect_user_security_columns ON public.users;
CREATE TRIGGER trg_protect_user_security_columns
    BEFORE UPDATE ON public.users
    FOR EACH ROW
    EXECUTE FUNCTION public.protect_user_security_columns();
