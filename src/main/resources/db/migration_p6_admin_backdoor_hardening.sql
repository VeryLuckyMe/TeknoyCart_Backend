-- ==============================================================================
-- P6 Migration: Admin Backdoor Removal + KYC Self-Approval Hardening
-- ==============================================================================
-- Why this migration exists:
--   1. `admin_verify_seller` and `fn_notify_kyc_event` contained a hardcoded
--      developer-email privilege bypass ('mikeljosh.nicer@cit.edu').
--   2. A duplicate 2-argument overload of `admin_verify_seller` (from
--      migration_p4_admin_vendor_verification.sql) caused PostgREST overload
--      ambiguity (PGRST203) and left a second, weaker code path in the database.
--   3. The `seller_verifications` policies let a user INSERT or UPDATE their own
--      row straight to APPROVED. The Flutter client treats the latest APPROVED
--      row as proof of seller verification (auth_service.getEnrichedProfile),
--      so this was a client-side privilege escalation, not a cosmetic issue.
--   4. `protect_user_security_columns` did not cover `email` (leveraged by the
--      removed dev-email checks) or `verification_token`.
--   5. Internal maintenance objects were reachable through PostgREST:
--      `notification_trigger_errors` (no RLS), `cleanup_old_notifications` and
--      `release_expired_reservations` (EXECUTE granted to authenticated).
--
-- Idempotent: safe to run repeatedly and on any database state (prod, staging,
-- or a database where only some of the earlier migrations were applied).
-- Rollback: see P6-ROLLBACK.sql in the delivery bundle (keeps backdoors removed).
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. REMOVE the legacy 2-arg overload (PGRST203 ambiguity source)
-- ------------------------------------------------------------------------------
DROP FUNCTION IF EXISTS public.admin_verify_seller(uuid, boolean);

-- ------------------------------------------------------------------------------
-- 2. ADMIN VERIFY SELLER — hardened 3-arg definition (no email bypass)
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.admin_verify_seller(
    target_user_id UUID,
    is_approved BOOLEAN,
    reason TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_role TEXT;
BEGIN
    -- Check caller privileges: service_role, or an ADMIN in public.users.
    -- There is NO developer-email bypass.
    SELECT role INTO v_admin_role
    FROM public.users
    WHERE user_id = auth.uid();

    IF current_setting('request.jwt.claim.role', true) != 'service_role' THEN
        IF v_admin_role IS NULL OR UPPER(v_admin_role) != 'ADMIN' THEN
            RAISE EXCEPTION 'Access denied. Administrator privileges required.';
        END IF;
    END IF;

    IF is_approved THEN
        UPDATE public.users
        SET role = 'SELLER', is_seller_verified = true
        WHERE user_id = target_user_id;

        UPDATE public.seller_verifications
        SET status = 'APPROVED',
            rejection_reason = NULL,
            reviewed_at = NOW()
        WHERE user_id = target_user_id;
    ELSE
        UPDATE public.users
        SET role = 'BUYER', is_seller_verified = false
        WHERE user_id = target_user_id;

        UPDATE public.seller_verifications
        SET status = 'REJECTED',
            rejection_reason = COALESCE(reason, 'Application declined. Please review your credentials and resubmit.'),
            reviewed_at = NOW()
        WHERE user_id = target_user_id;
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'user_id', target_user_id,
        'approved', is_approved
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.admin_verify_seller(UUID, BOOLEAN, TEXT) TO authenticated, service_role;

-- ------------------------------------------------------------------------------
-- 3. KYC FAN-OUT TRIGGER FUNCTION — admin fan-out no longer matches a
--    hardcoded developer email address. (Body otherwise identical to
--    db/migration_notifications.sql section 6.)
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.fn_notify_kyc_event()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_applicant_name TEXT;
    v_admin_record RECORD;
BEGIN
    BEGIN
        -- 1. NOTIFY ADMINS: When an application is submitted or resubmitted with PENDING status
        IF (TG_OP = 'INSERT' AND NEW.status = 'PENDING') OR
           (TG_OP = 'UPDATE' AND NEW.status = 'PENDING' AND OLD.status IS DISTINCT FROM NEW.status) THEN

            SELECT COALESCE(NULLIF(TRIM(full_name), ''), NULLIF(TRIM(email), ''), 'A student')
            INTO v_applicant_name
            FROM public.users
            WHERE user_id = NEW.user_id;

            IF v_applicant_name IS NULL THEN
                v_applicant_name := 'A student';
            END IF;

            -- Fan-out notification to all campus administrators
            FOR v_admin_record IN (
                SELECT user_id, email, full_name
                FROM public.users
                WHERE UPPER(COALESCE(role, '')) = 'ADMIN'
            ) LOOP
                INSERT INTO public.notifications (
                    user_id,
                    title,
                    body,
                    type,
                    reference_id,
                    reference_type,
                    dedupe_key,
                    delivery_channels,
                    push_status,
                    email_status
                ) VALUES (
                    v_admin_record.user_id,
                    '📋 New Seller Application',
                    v_applicant_name || ' submitted KYC verification (' || COALESCE(NEW.seller_type, 'STUDENT') || ') for administrative review.',
                    'KYC',
                    NEW.id::TEXT,
                    'KYC',
                    'kyc:' || NEW.id::TEXT || ':PENDING:admin:' || v_admin_record.user_id::TEXT,
                    ARRAY['IN_APP', 'PUSH', 'EMAIL'],
                    'PENDING',
                    'PENDING'
                ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;
            END LOOP;

            RETURN NEW;
        END IF;

        -- 2. NOTIFY APPLICANT: When status is APPROVED or REJECTED by admin
        IF TG_OP = 'UPDATE' THEN
            IF OLD.status IS NOT DISTINCT FROM NEW.status THEN
                RETURN NEW;
            END IF;

            IF NEW.status = 'APPROVED' THEN
                INSERT INTO public.notifications (
                    user_id, title, body, type, reference_id, reference_type,
                    dedupe_key, delivery_channels, push_status, email_status
                ) VALUES (
                    NEW.user_id,
                    '🎉 Seller Verification Approved!',
                    'You are now an authorized CIT-U vendor. You can now post listings and accept orders!',
                    'KYC',
                    NEW.id::TEXT,
                    'KYC',
                    'kyc:' || NEW.id::TEXT || ':APPROVED',
                    ARRAY['IN_APP', 'PUSH', 'EMAIL'],
                    'PENDING',
                    'PENDING'
                ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;

            ELSIF NEW.status = 'REJECTED' THEN
                INSERT INTO public.notifications (
                    user_id, title, body, type, reference_id, reference_type,
                    dedupe_key, delivery_channels, push_status, email_status
                ) VALUES (
                    NEW.user_id,
                    'Verification Needs Attention',
                    'Your seller verification was rejected: ' || COALESCE(NEW.rejection_reason, 'Please review your ID and resubmit.') || '.',
                    'KYC',
                    NEW.id::TEXT,
                    'KYC',
                    'kyc:' || NEW.id::TEXT || ':REJECTED',
                    ARRAY['IN_APP', 'PUSH', 'EMAIL'],
                    'PENDING',
                    'PENDING'
                ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;
            END IF;
        END IF;

    EXCEPTION WHEN OTHERS THEN
        BEGIN
            INSERT INTO public.notification_trigger_errors (
                trigger_name, reference_id, error_message, sql_state
            ) VALUES (
                'trg_kyc_status_notify',
                COALESCE(NEW.id::TEXT, 'UNKNOWN'),
                SQLERRM,
                SQLSTATE
            );
        EXCEPTION WHEN OTHERS THEN NULL;
        END;

        RAISE WARNING 'fn_notify_kyc_event failed for verification %: % (SQLSTATE %)',
            COALESCE(NEW.id::TEXT, 'UNKNOWN'), SQLERRM, SQLSTATE;
    END;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_kyc_status_notify ON public.seller_verifications;
CREATE TRIGGER trg_kyc_status_notify
    AFTER INSERT OR UPDATE OF status ON public.seller_verifications
    FOR EACH ROW
    EXECUTE FUNCTION public.fn_notify_kyc_event();

-- ------------------------------------------------------------------------------
-- 4. PROTECT USER SECURITY COLUMNS — superset of the P4 hardening, extended
--    with `email` and `verification_token` guards.
-- ------------------------------------------------------------------------------
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
    IF NEW.email IS DISTINCT FROM OLD.email THEN
        RAISE EXCEPTION 'Modifying email directly is forbidden.';
    END IF;
    IF NEW.verification_token IS DISTINCT FROM OLD.verification_token THEN
        RAISE EXCEPTION 'Modifying verification_token directly is forbidden.';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS trg_protect_user_security_columns ON public.users;
CREATE TRIGGER trg_protect_user_security_columns
    BEFORE UPDATE ON public.users
    FOR EACH ROW
    EXECUTE FUNCTION public.protect_user_security_columns();

-- ------------------------------------------------------------------------------
-- 5. SELLER VERIFICATION POLICIES — users can no longer self-approve
-- ------------------------------------------------------------------------------
-- UPDATE: only a user's own PENDING/REJECTED row, and the row must remain
-- PENDING/REJECTED (APPROVED is set exclusively by admin_verify_seller).
DROP POLICY IF EXISTS "Users can update own rejected verification" ON public.seller_verifications;
CREATE POLICY "Users can update own rejected verification"
    ON public.seller_verifications
    FOR UPDATE
    TO authenticated
    USING (auth.uid() = user_id AND status IN ('PENDING', 'REJECTED'))
    WITH CHECK (auth.uid() = user_id AND status IN ('PENDING', 'REJECTED'));

-- INSERT: a user may only open their own application in PENDING state.
DROP POLICY IF EXISTS "Users can submit own verification" ON public.seller_verifications;
CREATE POLICY "Users can submit own verification"
    ON public.seller_verifications
    FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = user_id AND status = 'PENDING');

-- ------------------------------------------------------------------------------
-- 6. POSTGREST REACHABILITY HARDENING (internal-only objects)
-- ------------------------------------------------------------------------------
-- 6a. Trigger error log: internal observability table — deny all client access.
ALTER TABLE public.notification_trigger_errors ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.notification_trigger_errors FROM anon, authenticated;

-- 6b. Retention maintenance RPC: service-side only (pg_cron runs as owner).
REVOKE EXECUTE ON FUNCTION public.cleanup_old_notifications(INTEGER, INTEGER) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.cleanup_old_notifications(INTEGER, INTEGER) TO service_role;

-- 6c. Global reservation sweep: invoked by the backend scheduler
--     (OrderTimeoutService via JDBC); no client-side caller exists.
REVOKE EXECUTE ON FUNCTION public.release_expired_reservations() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.release_expired_reservations() TO service_role;

-- NOTE: reconcile_inventory_holds(uuid) intentionally KEEPS its `authenticated`
-- grant — the seller "Reserved Orders" sheet calls it directly
-- (reserved_orders_sheet.dart `_reconcile`). Revisit in Phase 2 with an app-side
-- change; revoking it today would degrade the seller inventory inspector.

-- ------------------------------------------------------------------------------
-- 7. RELOAD POSTGREST SCHEMA CACHE
-- ------------------------------------------------------------------------------
NOTIFY pgrst, 'reload schema';
