-- ==============================================================================
-- Migration: Admin Vendor Verification RPC (v1)
-- ==============================================================================
-- Fixes: When an admin approves or declines a vendor from the Web Admin dashboard,
-- the trigger `trg_protect_user_security_columns` and the RLS policy on `public.users`
-- block direct client-side updates to `is_seller_verified` and `role`.
--
-- This function runs with SECURITY DEFINER privileges to safely update the seller
-- status while verifying that the caller has ADMIN privileges.
-- ==============================================================================

CREATE OR REPLACE FUNCTION public.admin_verify_seller(target_user_id UUID, is_approved BOOLEAN)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    caller_id UUID;
    caller_role TEXT;
BEGIN
    caller_id := auth.uid();

    -- Allow service_role or verify that caller is an authenticated user with ADMIN role
    IF current_setting('request.jwt.claim.role', true) != 'service_role' THEN
        IF caller_id IS NULL THEN
            RAISE EXCEPTION 'Authentication required to moderate vendors.';
        END IF;

        SELECT role INTO caller_role FROM public.users WHERE user_id = caller_id;
        IF caller_role != 'ADMIN' THEN
            RAISE EXCEPTION 'Access denied: Only administrators can approve or revoke vendor access.';
        END IF;
    END IF;

    -- Update the seller status
    IF is_approved THEN
        UPDATE public.users
        SET is_seller_verified = true,
            role = 'SELLER'
        WHERE user_id = target_user_id;
    ELSE
        UPDATE public.users
        SET is_seller_verified = false,
            role = 'BUYER'
        WHERE user_id = target_user_id;
    END IF;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'User not found with ID: %', target_user_id;
    END IF;

    RETURN jsonb_build_object(
        'success', true, 
        'user_id', target_user_id, 
        'role', CASE WHEN is_approved THEN 'SELLER' ELSE 'BUYER' END,
        'is_seller_verified', is_approved
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.admin_verify_seller(UUID, BOOLEAN) TO authenticated, service_role;
