-- ============================================================================
-- V22: Plain-text notification titles
--
-- Notification titles used to carry emoji ("📦 New Order Received",
-- "Sale Completed ✓", ...). V7, V8 (P6) and V11 are already recorded in
-- schema_migrations, so editing those files does not reach existing databases.
-- This migration:
--   1. Re-creates the three notification trigger functions with plain titles
--      (bodies are identical to their latest versions apart from the titles).
--   2. Strips the emoji from titles of notifications that already exist.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1a. Order events (latest definition: migration_notifications.sql)
-- ----------------------------------------------------------------------------
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
                    'New Order Received',
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
                        'Payment Secured in Escrow',
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
                        'Meetup Scheduled',
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
                        'Ready for Handoff',
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
                        'Order Completed',
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
                        'Sale Completed',
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
                        'Order Under Review',
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
                        'Order Under Review',
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
                        'Refund Completed',
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

-- ----------------------------------------------------------------------------
-- 1b. Seller KYC events (latest definition: migration_p6_admin_backdoor_hardening.sql)
-- ----------------------------------------------------------------------------
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
                    'New Seller Application',
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
                    'Seller Verification Approved!',
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

-- ----------------------------------------------------------------------------
-- 1c. New review (latest definition: migration_review_notifications.sql)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.fn_notify_seller_on_review()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_product_name TEXT := 'your item';
BEGIN
    BEGIN
        -- Defensive guards. seller_id is NOT NULL by schema, but a self-review
        -- must never notify the same account.
        IF NEW.seller_id IS NULL OR NEW.seller_id = NEW.buyer_id THEN
            RETURN NEW;
        END IF;

        -- product_reviews.product_id is TEXT while products.product_id is UUID,
        -- so compare as text instead of risking an invalid UUID cast.
        SELECT COALESCE(NULLIF(BTRIM(p.name), ''), 'your item')
          INTO v_product_name
          FROM public.products p
         WHERE p.product_id::TEXT = NEW.product_id
         LIMIT 1;

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
            NEW.seller_id,
            'New Review Received',
            'A buyer rated ' || v_product_name || ' ' || NEW.rating::TEXT ||
                ' out of 5. Tap to view and reply.',
            'REVIEW',
            CASE WHEN NEW.order_id IS NULL THEN NULL ELSE NEW.order_id::TEXT END,
            CASE WHEN NEW.order_id IS NULL THEN NULL ELSE 'ORDER' END,
            'review:' || NEW.review_id::TEXT || ':seller',
            ARRAY['IN_APP'],
            'NOT_APPLICABLE',
            'NOT_APPLICABLE'
        )
        ON CONFLICT (user_id, dedupe_key) DO NOTHING;

    EXCEPTION WHEN OTHERS THEN
        -- Never abort the buyer's review insert because a notification failed.
        RAISE WARNING 'Seller review notification failed for review %: %',
            NEW.review_id, SQLERRM;
    END;

    RETURN NEW;
END;
$$;

-- ----------------------------------------------------------------------------
-- 2. Clean up titles of notifications that already exist
-- ----------------------------------------------------------------------------
UPDATE public.notifications
SET title = regexp_replace(
                regexp_replace(title, '^(📦|💰|📅|🤝|⚠️|⚠|📋|🎉|⭐)\s*', ''),
                '\s*✓$', '')
WHERE title ~ '^(📦|💰|📅|🤝|⚠️|⚠|📋|🎉|⭐)' OR title ~ '✓$';
