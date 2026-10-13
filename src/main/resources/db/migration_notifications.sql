-- ==============================================================================
-- TeknoyCart: In-App Notification Center & Outbox Dispatch Migration
-- Version: V2 (Fully Hardened with Outbox, Error Logging & Column Security)
-- ==============================================================================
-- Architecture & Invariants:
-- 1. Single Source of Truth & Outbox Pattern:
--    All notification events (order transitions, KYC, offers) write a row here.
--    Push (FCM) and Email (Brevo) fan out from this table via an asynchronous
--    outbox poller using `FOR UPDATE SKIP LOCKED`. Direct SQL / Admin Console changes
--    are fully covered with zero channel desynchronization.
-- 2. Zero Client Spoofing & Column-Level Privilege:
--    - NO INSERT policy for authenticated clients.
--    - UPDATE permission is strictly restricted to `(is_read)` column via GRANT.
--    - Functions run SECURITY DEFINER with `SET search_path = public`.
-- 3. Failure Isolation & Error Observability:
--    - All triggers wrapped in BEGIN ... EXCEPTION WHEN OTHERS.
--    - Failures are logged to `public.notification_trigger_errors`.
--    - Order transitions will NEVER abort due to notification failures.
-- 4. Idempotency & Deduplication:
--    - dedupe_key column with UNIQUE (user_id, dedupe_key).
--    - Format: order:{order_id}:{status}:{recipient_role}
--    - Re-entering a status is intentionally suppressed via ON CONFLICT DO NOTHING.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. TRIGGER ERROR OBSERVABILITY TABLE
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.notification_trigger_errors (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trigger_name    VARCHAR(100) NOT NULL,
    reference_id    VARCHAR(100),
    error_message   TEXT NOT NULL,
    sql_state       VARCHAR(10),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notif_errors_created
    ON public.notification_trigger_errors (created_at DESC);

-- ------------------------------------------------------------------------------
-- 2. NOTIFICATIONS TABLE (Single Source of Truth + Outbox Fields)
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.notifications (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES public.users(user_id) ON DELETE CASCADE,
    title               VARCHAR(150) NOT NULL,
    body                TEXT NOT NULL,
    type                VARCHAR(50) NOT NULL,
    reference_id        VARCHAR(100),
    reference_type      VARCHAR(50),
    dedupe_key          VARCHAR(150),
    is_read             BOOLEAN NOT NULL DEFAULT FALSE,

    -- Outbox Fan-out Delivery Columns
    delivery_channels   TEXT[] NOT NULL DEFAULT ARRAY['IN_APP'], -- e.g. ['IN_APP', 'PUSH', 'EMAIL']
    push_status         VARCHAR(20) NOT NULL DEFAULT 'NOT_APPLICABLE', -- 'NOT_APPLICABLE' | 'PENDING' | 'SENT' | 'FAILED'
    email_status        VARCHAR(20) NOT NULL DEFAULT 'NOT_APPLICABLE', -- 'NOT_APPLICABLE' | 'PENDING' | 'SENT' | 'FAILED'
    push_sent_at        TIMESTAMPTZ,
    email_sent_at       TIMESTAMPTZ,
    push_error          TEXT,
    email_error         TEXT,
    retry_count         INT NOT NULL DEFAULT 0,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Constraints
    CONSTRAINT uq_notifications_user_dedupe UNIQUE (user_id, dedupe_key),
    CONSTRAINT chk_notifications_type CHECK (type IN ('ORDER_STATUS', 'PAYMENT', 'KYC', 'OFFER', 'SYSTEM')),
    CONSTRAINT chk_notifications_ref_type CHECK (reference_type IS NULL OR reference_type IN ('ORDER', 'KYC', 'OFFER', 'SYSTEM')),
    CONSTRAINT chk_notifications_push_status CHECK (push_status IN ('NOT_APPLICABLE', 'PENDING', 'SENT', 'FAILED')),
    CONSTRAINT chk_notifications_email_status CHECK (email_status IN ('NOT_APPLICABLE', 'PENDING', 'SENT', 'FAILED'))
);

-- ------------------------------------------------------------------------------
-- 3. INDEXES
-- ------------------------------------------------------------------------------
-- Primary feed query: "Show user notifications, newest first"
CREATE INDEX IF NOT EXISTS idx_notifications_user_created
    ON public.notifications (user_id, created_at DESC);

-- Partial index: "Instant badge counter / fetch unread items"
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread
    ON public.notifications (user_id)
    WHERE is_read = FALSE;

-- Lookup by referenced domain entity (e.g. check notifications for an order)
CREATE INDEX IF NOT EXISTS idx_notifications_reference
    ON public.notifications (reference_type, reference_id);

-- Outbox Polling Queue Index (claims pending jobs fast with FOR UPDATE SKIP LOCKED)
CREATE INDEX IF NOT EXISTS idx_notifications_outbox_queue
    ON public.notifications (created_at ASC)
    WHERE (push_status = 'PENDING' OR email_status = 'PENDING') AND retry_count < 3;

-- ------------------------------------------------------------------------------
-- 4. ROW LEVEL SECURITY & COLUMN-LEVEL PRIVILEGE ENFORCEMENT
-- ------------------------------------------------------------------------------
ALTER TABLE public.notifications ENABLE ROW LEVEL SECURITY;

-- Clean up any prior policies
DROP POLICY IF EXISTS "Users view own notifications" ON public.notifications;
DROP POLICY IF EXISTS "Users mark own notifications read" ON public.notifications;
DROP POLICY IF EXISTS "Triggers insert notifications" ON public.notifications;
DROP POLICY IF EXISTS "Service inserts notifications" ON public.notifications;

-- Policy 1: Authenticated users can ONLY view their own notifications
CREATE POLICY "Users view own notifications"
    ON public.notifications FOR SELECT
    TO authenticated
    USING (auth.uid() = user_id);

-- Policy 2: Authenticated users can update ONLY their own rows
CREATE POLICY "Users mark own notifications read"
    ON public.notifications FOR UPDATE
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- COLUMN PRIVILEGE RESTRICTION:
-- RLS cannot limit which columns an authenticated user modifies.
-- Revoke table-level update and grant UPDATE strictly on `is_read`.
-- This guarantees users cannot modify title, body, type, or outbox status!
REVOKE UPDATE ON public.notifications FROM authenticated;
GRANT UPDATE (is_read) ON public.notifications TO authenticated;

-- NOTE: NO INSERT policy exists for authenticated role.
-- Clients CANNOT insert notifications. Only SECURITY DEFINER triggers and service_role can.

-- ------------------------------------------------------------------------------
-- 5. ORDER EVENT TRIGGER FUNCTION (Failure-Isolated, Outbox-Aware, Counterparty Only)
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.fn_notify_order_event()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_product_name   TEXT := 'your item';
    v_status_str     TEXT;
    v_buyer_id       UUID;
    v_seller_id      UUID;
    v_order_id       UUID;
    v_channels       TEXT[];
    v_push_status    VARCHAR(20);
    v_email_status   VARCHAR(20);
BEGIN
    BEGIN
        v_order_id   := NEW.order_id;
        v_buyer_id   := NEW.buyer_id;
        v_seller_id  := NEW.seller_id;
        v_status_str := COALESCE(NEW.status::TEXT, '');

        -- Skip if update but status did not change
        IF TG_OP = 'UPDATE' AND (OLD.status IS NOT DISTINCT FROM NEW.status) THEN
            RETURN NEW;
        END IF;

        -- Resolve product name defensively
        IF NEW.variant_id IS NOT NULL THEN
            BEGIN
                SELECT p.name INTO v_product_name
                FROM public.products p
                JOIN public.product_variants pv ON pv.product_id = p.product_id
                WHERE pv.variant_id = NEW.variant_id
                LIMIT 1;
                v_product_name := COALESCE(v_product_name, 'your item');
            EXCEPTION WHEN OTHERS THEN
                v_product_name := 'your item';
            END;
        END IF;

        -- ── SCENARIO A: NEW ORDER PLACED (INSERT) ──
        IF TG_OP = 'INSERT' AND v_status_str = 'PLACED' THEN
            IF v_seller_id IS NOT NULL THEN
                INSERT INTO public.notifications (
                    user_id, title, body, type, reference_id, reference_type,
                    dedupe_key, delivery_channels, push_status, email_status
                ) VALUES (
                    v_seller_id,
                    '📦 New Order Received',
                    'A buyer placed an order for ' || v_product_name || '. Please review and accept.',
                    'ORDER_STATUS',
                    v_order_id::TEXT,
                    'ORDER',
                    'order:' || v_order_id::TEXT || ':PLACED:seller',
                    ARRAY['IN_APP', 'PUSH'],
                    'PENDING',
                    'NOT_APPLICABLE'
                ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;
            END IF;
            RETURN NEW;
        END IF;

        -- ── SCENARIO B: STATUS MACHINE UPDATES (UPDATE) ──
        CASE v_status_str
            WHEN 'ACCEPTED' THEN
                -- Seller accepted -> Notify BUYER
                IF v_buyer_id IS NOT NULL THEN
                    INSERT INTO public.notifications (
                        user_id, title, body, type, reference_id, reference_type,
                        dedupe_key, delivery_channels, push_status, email_status
                    ) VALUES (
                        v_buyer_id,
                        'Order Accepted',
                        'The seller accepted your order for ' || v_product_name || '. Prepare for meetup or payment.',
                        'ORDER_STATUS',
                        v_order_id::TEXT,
                        'ORDER',
                        'order:' || v_order_id::TEXT || ':ACCEPTED:buyer',
                        ARRAY['IN_APP', 'PUSH'],
                        'PENDING',
                        'NOT_APPLICABLE'
                    ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;
                END IF;

            WHEN 'AWAITING_PAYMENT' THEN
                -- GCash checkout session active -> Notify BUYER
                IF v_buyer_id IS NOT NULL THEN
                    INSERT INTO public.notifications (
                        user_id, title, body, type, reference_id, reference_type,
                        dedupe_key, delivery_channels, push_status, email_status
                    ) VALUES (
                        v_buyer_id,
                        'Payment Required',
                        'Please complete GCash payment for ' || v_product_name || ' to secure escrow.',
                        'ORDER_STATUS',
                        v_order_id::TEXT,
                        'ORDER',
                        'order:' || v_order_id::TEXT || ':AWAITING_PAYMENT:buyer',
                        ARRAY['IN_APP', 'PUSH'],
                        'PENDING',
                        'NOT_APPLICABLE'
                    ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;
                END IF;

            WHEN 'ESCROWED' THEN
                -- Payment secured -> Notify SELLER (In-App + Push + Email)
                IF v_seller_id IS NOT NULL THEN
                    INSERT INTO public.notifications (
                        user_id, title, body, type, reference_id, reference_type,
                        dedupe_key, delivery_channels, push_status, email_status
                    ) VALUES (
                        v_seller_id,
                        '💰 Payment Secured in Escrow',
                        'Payment for ' || v_product_name || ' has been confirmed and secured in escrow. Prepare the item for meetup!',
                        'PAYMENT',
                        v_order_id::TEXT,
                        'ORDER',
                        'order:' || v_order_id::TEXT || ':ESCROWED:seller',
                        ARRAY['IN_APP', 'PUSH', 'EMAIL'],
                        'PENDING',
                        'PENDING'
                    ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;
                END IF;

            WHEN 'MEETUP_SCHEDULED' THEN
                -- Meetup arranged -> Notify BUYER
                IF v_buyer_id IS NOT NULL THEN
                    INSERT INTO public.notifications (
                        user_id, title, body, type, reference_id, reference_type,
                        dedupe_key, delivery_channels, push_status, email_status
                    ) VALUES (
                        v_buyer_id,
                        '📅 Meetup Scheduled',
                        'Meetup details confirmed for ' || v_product_name || ' at CIT-U.',
                        'ORDER_STATUS',
                        v_order_id::TEXT,
                        'ORDER',
                        'order:' || v_order_id::TEXT || ':MEETUP_SCHEDULED:buyer',
                        ARRAY['IN_APP', 'PUSH'],
                        'PENDING',
                        'NOT_APPLICABLE'
                    ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;
                END IF;

            WHEN 'HANDOFF_PENDING' THEN
                -- Item ready for verification -> Notify BUYER
                IF v_buyer_id IS NOT NULL THEN
                    INSERT INTO public.notifications (
                        user_id, title, body, type, reference_id, reference_type,
                        dedupe_key, delivery_channels, push_status, email_status
                    ) VALUES (
                        v_buyer_id,
                        '🤝 Ready for Handoff',
                        'Item ' || v_product_name || ' is ready. Exchange the OTP code with the seller at meetup.',
                        'ORDER_STATUS',
                        v_order_id::TEXT,
                        'ORDER',
                        'order:' || v_order_id::TEXT || ':HANDOFF_PENDING:buyer',
                        ARRAY['IN_APP', 'PUSH'],
                        'PENDING',
                        'NOT_APPLICABLE'
                    ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;
                END IF;

            WHEN 'COMPLETED' THEN
                -- Handoff confirmed -> Notify BUYER (In-App + Push + Email)
                IF v_buyer_id IS NOT NULL THEN
                    INSERT INTO public.notifications (
                        user_id, title, body, type, reference_id, reference_type,
                        dedupe_key, delivery_channels, push_status, email_status
                    ) VALUES (
                        v_buyer_id,
                        'Order Completed ✓',
                        'Your purchase of ' || v_product_name || ' is complete. Leave a review to help fellow Wildcats!',
                        'ORDER_STATUS',
                        v_order_id::TEXT,
                        'ORDER',
                        'order:' || v_order_id::TEXT || ':COMPLETED:buyer',
                        ARRAY['IN_APP', 'PUSH', 'EMAIL'],
                        'PENDING',
                        'PENDING'
                    ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;
                END IF;

                -- Payout released -> Notify SELLER (In-App + Push + Email)
                IF v_seller_id IS NOT NULL THEN
                    INSERT INTO public.notifications (
                        user_id, title, body, type, reference_id, reference_type,
                        dedupe_key, delivery_channels, push_status, email_status
                    ) VALUES (
                        v_seller_id,
                        'Sale Completed ✓',
                        'Handoff verified! Payout for ' || v_product_name || ' is being released. Great job!',
                        'ORDER_STATUS',
                        v_order_id::TEXT,
                        'ORDER',
                        'order:' || v_order_id::TEXT || ':COMPLETED:seller',
                        ARRAY['IN_APP', 'PUSH', 'EMAIL'],
                        'PENDING',
                        'PENDING'
                    ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;
                END IF;

            WHEN 'CANCELLED' THEN
                -- Notify both parties
                IF v_buyer_id IS NOT NULL THEN
                    INSERT INTO public.notifications (
                        user_id, title, body, type, reference_id, reference_type,
                        dedupe_key, delivery_channels, push_status, email_status
                    ) VALUES (
                        v_buyer_id,
                        'Order Cancelled',
                        'The order for ' || v_product_name || ' has been cancelled.',
                        'ORDER_STATUS',
                        v_order_id::TEXT,
                        'ORDER',
                        'order:' || v_order_id::TEXT || ':CANCELLED:buyer',
                        ARRAY['IN_APP', 'PUSH'],
                        'PENDING',
                        'NOT_APPLICABLE'
                    ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;
                END IF;

                IF v_seller_id IS NOT NULL THEN
                    INSERT INTO public.notifications (
                        user_id, title, body, type, reference_id, reference_type,
                        dedupe_key, delivery_channels, push_status, email_status
                    ) VALUES (
                        v_seller_id,
                        'Order Cancelled',
                        'The order for ' || v_product_name || ' has been cancelled.',
                        'ORDER_STATUS',
                        v_order_id::TEXT,
                        'ORDER',
                        'order:' || v_order_id::TEXT || ':CANCELLED:seller',
                        ARRAY['IN_APP', 'PUSH'],
                        'PENDING',
                        'NOT_APPLICABLE'
                    ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;
                END IF;

            WHEN 'DISPUTED' THEN
                -- Notify both parties
                IF v_buyer_id IS NOT NULL THEN
                    INSERT INTO public.notifications (
                        user_id, title, body, type, reference_id, reference_type,
                        dedupe_key, delivery_channels, push_status, email_status
                    ) VALUES (
                        v_buyer_id,
                        '⚠️ Order Under Review',
                        'A dispute was raised on ' || v_product_name || '. Our team is reviewing the case.',
                        'ORDER_STATUS',
                        v_order_id::TEXT,
                        'ORDER',
                        'order:' || v_order_id::TEXT || ':DISPUTED:buyer',
                        ARRAY['IN_APP', 'PUSH'],
                        'PENDING',
                        'NOT_APPLICABLE'
                    ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;
                END IF;

                IF v_seller_id IS NOT NULL THEN
                    INSERT INTO public.notifications (
                        user_id, title, body, type, reference_id, reference_type,
                        dedupe_key, delivery_channels, push_status, email_status
                    ) VALUES (
                        v_seller_id,
                        '⚠️ Order Under Review',
                        'A dispute was raised on ' || v_product_name || '. Payout is paused pending mediation.',
                        'ORDER_STATUS',
                        v_order_id::TEXT,
                        'ORDER',
                        'order:' || v_order_id::TEXT || ':DISPUTED:seller',
                        ARRAY['IN_APP', 'PUSH'],
                        'PENDING',
                        'NOT_APPLICABLE'
                    ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;
                END IF;

            WHEN 'REFUND_PENDING' THEN
                IF v_buyer_id IS NOT NULL THEN
                    INSERT INTO public.notifications (
                        user_id, title, body, type, reference_id, reference_type,
                        dedupe_key, delivery_channels, push_status, email_status
                    ) VALUES (
                        v_buyer_id,
                        'Refund Pending',
                        'Your refund for ' || v_product_name || ' is currently being processed.',
                        'PAYMENT',
                        v_order_id::TEXT,
                        'ORDER',
                        'order:' || v_order_id::TEXT || ':REFUND_PENDING:buyer',
                        ARRAY['IN_APP', 'PUSH'],
                        'PENDING',
                        'NOT_APPLICABLE'
                    ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;
                END IF;

            WHEN 'REFUND_COMPLETED' THEN
                IF v_buyer_id IS NOT NULL THEN
                    INSERT INTO public.notifications (
                        user_id, title, body, type, reference_id, reference_type,
                        dedupe_key, delivery_channels, push_status, email_status
                    ) VALUES (
                        v_buyer_id,
                        'Refund Completed ✓',
                        'Your refund for ' || v_product_name || ' has been completed.',
                        'PAYMENT',
                        v_order_id::TEXT,
                        'ORDER',
                        'order:' || v_order_id::TEXT || ':REFUND_COMPLETED:buyer',
                        ARRAY['IN_APP', 'PUSH'],
                        'PENDING',
                        'NOT_APPLICABLE'
                    ) ON CONFLICT (user_id, dedupe_key) DO NOTHING;
                END IF;

            ELSE
                NULL;
        END CASE;

    EXCEPTION WHEN OTHERS THEN
        -- Log to error table and log warning; NEVER roll back parent order transaction
        BEGIN
            INSERT INTO public.notification_trigger_errors (
                trigger_name, reference_id, error_message, sql_state
            ) VALUES (
                'trg_order_event_notify',
                COALESCE(v_order_id::TEXT, 'UNKNOWN'),
                SQLERRM,
                SQLSTATE
            );
        EXCEPTION WHEN OTHERS THEN NULL;
        END;

        RAISE WARNING 'fn_notify_order_event failed for order %: % (SQLSTATE %)',
            COALESCE(v_order_id::TEXT, 'UNKNOWN'), SQLERRM, SQLSTATE;
    END;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_order_event_notify ON public.orders;
CREATE TRIGGER trg_order_event_notify
    AFTER INSERT OR UPDATE OF status ON public.orders
    FOR EACH ROW
    EXECUTE FUNCTION public.fn_notify_order_event();

-- ------------------------------------------------------------------------------
-- 6. SELLER KYC STATUS & SUBMISSION TRIGGER (Admin Fan-out + Applicant Decisions)
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

            -- Fan-out notification to all campus administrators (including designated developer admin)
            FOR v_admin_record IN (
                SELECT user_id, email, full_name
                FROM public.users
                WHERE UPPER(role) = 'ADMIN' OR email = 'mikeljosh.nicer@cit.edu'
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
-- 7. PHASE 2: FCM DEVICE TOKEN TABLE (Shared Device Resilient: UNIQUE(token))
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.user_fcm_tokens (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES public.users(user_id) ON DELETE CASCADE,
    token      TEXT NOT NULL,
    platform   VARCHAR(20) DEFAULT 'ANDROID',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_fcm_tokens_token UNIQUE (token)
);

CREATE INDEX IF NOT EXISTS idx_user_fcm_tokens_user
    ON public.user_fcm_tokens (user_id);

ALTER TABLE public.user_fcm_tokens ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users manage own tokens" ON public.user_fcm_tokens;
CREATE POLICY "Users manage own tokens"
    ON public.user_fcm_tokens FOR ALL
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- ------------------------------------------------------------------------------
-- 8. REALTIME PUBLICATION
-- ------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime'
          AND schemaname = 'public'
          AND tablename = 'notifications'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.notifications;
    END IF;
END $$;

-- ------------------------------------------------------------------------------
-- 9. MAINTENANCE: RETENTION & CLEANUP (pg_cron Scheduled)
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.cleanup_old_notifications(
    p_days_read INTEGER DEFAULT 60,
    p_days_unread INTEGER DEFAULT 180
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_deleted_count INTEGER := 0;
BEGIN
    DELETE FROM public.notifications
    WHERE (is_read = TRUE AND created_at < NOW() - (p_days_read || ' days')::INTERVAL)
       OR (is_read = FALSE AND created_at < NOW() - (p_days_unread || ' days')::INTERVAL);

    GET DIAGNOSTICS v_deleted_count = ROW_COUNT;
    RETURN v_deleted_count;
END;
$$;

-- Schedule daily execution at 3:00 AM UTC via pg_cron if available
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN
        PERFORM cron.schedule(
            'cleanup-old-notifications',
            '0 3 * * *',
            'SELECT public.cleanup_old_notifications(60, 180);'
        );
    END IF;
EXCEPTION WHEN OTHERS THEN NULL;
END $$;

-- ------------------------------------------------------------------------------
-- 10. ADMIN VERIFY SELLER PROCEDURE (Atomic Verification & Status Transition)
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
    v_caller_email TEXT;
BEGIN
    -- Check caller privileges
    SELECT role, email INTO v_admin_role, v_caller_email
    FROM public.users
    WHERE user_id = auth.uid();

    IF current_setting('request.jwt.claim.role', true) != 'service_role' THEN
        IF v_admin_role IS NULL OR (UPPER(v_admin_role) != 'ADMIN' AND v_caller_email != 'mikeljosh.nicer@cit.edu') THEN
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

-- Notify schema reload
NOTIFY pgrst, 'reload schema';
