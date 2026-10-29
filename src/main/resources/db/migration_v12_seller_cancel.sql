-- ============================================================================
-- TeknoyCart: Seller Cancellation Hardening (V12)
-- ============================================================================
-- Replaces the earlier cancel_order_as_seller RPC, which:
--   1. Skipped the seller guard whenever auth.uid() was NULL.
--   2. Forced every cancellation to CANCELLED, even for escrowed GCash orders
--      that must route to REFUND_PENDING.
--   3. Left EXECUTE granted to PUBLIC.
--
-- This migration is idempotent and is applied once by DatabaseMigrationRunner.
--
-- Invariants:
--   1. Authentication is required. There is no NULL-uid bypass.
--   2. Only the order's seller may call the RPC (PUBLIC/anon execution revoked).
--   3. Escrowed money never becomes an untracked CANCELLED order; it routes to
--      REFUND_PENDING. Submitted-but-unverified payment routes to
--      REFUND_REQUESTED, matching OrderService.cancelOrder.
--   4. The order row is locked with FOR UPDATE to serialize concurrent actions.
--   5. Inventory reservations are released by the status trigger after cancel.
-- ============================================================================

-- 1. Inventory sync trigger (idempotent; mirrors migration_atomic_inventory.sql)
--    Single source of truth: only this trigger mutates inventory on status changes.
CREATE OR REPLACE FUNCTION public.trg_order_status_inventory_sync()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    -- Physical handoff occurred (or direct completion): deduct physical stock
    -- and release the reservation lock.
    IF (NEW.status = 'HANDOFF_PENDING' AND (OLD.status IS NULL OR OLD.status != 'HANDOFF_PENDING'))
       OR (NEW.status = 'COMPLETED' AND (OLD.status IS NULL OR (OLD.status != 'COMPLETED' AND OLD.status != 'HANDOFF_PENDING' AND OLD.status NOT IN ('RETURN_REQUESTED', 'RETURN_APPROVED', 'DISPUTED')))) THEN
        UPDATE public.inventory
        SET stock_qty = GREATEST(0, stock_qty - COALESCE(NEW.quantity, 1)),
            reserved_qty = GREATEST(0, reserved_qty - COALESCE(NEW.quantity, 1)),
            last_updated = NOW()
        WHERE variant_id = NEW.variant_id;

    -- Physical return completed: replenish physical stock.
    ELSIF NEW.status = 'RETURN_COMPLETED' AND (OLD.status IS NULL OR OLD.status != 'RETURN_COMPLETED') THEN
        UPDATE public.inventory
        SET stock_qty = stock_qty + COALESCE(NEW.quantity, 1),
            last_updated = NOW()
        WHERE variant_id = NEW.variant_id;

    -- Pre-handoff cancellation/rejection/refund: item never left the seller, so
    -- release the reserved hold. If handoff already completed, stock_qty was
    -- deducted and reserved_qty already released at handoff.
    ELSIF (NEW.status IN ('CANCELLED', 'REJECTED', 'REFUND_COMPLETED'))
          AND (OLD.status IS NULL OR OLD.status NOT IN ('CANCELLED', 'REJECTED', 'REFUND_COMPLETED')) THEN
        IF NEW.handoff_completed_at IS NULL THEN
            UPDATE public.inventory
            SET reserved_qty = GREATEST(0, reserved_qty - COALESCE(NEW.quantity, 1)),
                last_updated = NOW()
            WHERE variant_id = NEW.variant_id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sync_order_status_to_inventory ON public.orders;
CREATE TRIGGER trg_sync_order_status_to_inventory
AFTER UPDATE OF status ON public.orders
FOR EACH ROW
EXECUTE FUNCTION public.trg_order_status_inventory_sync();

-- 2. Hardened seller cancellation RPC
CREATE OR REPLACE FUNCTION public.cancel_order_as_seller(
    p_order_id UUID,
    p_reason TEXT DEFAULT 'Cancelled by seller'
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_order public.orders%ROWTYPE;
    v_caller_id UUID := auth.uid();
    v_target_status TEXT;
    v_reason TEXT := COALESCE(NULLIF(btrim(p_reason), ''), 'Cancelled by seller');
    v_payment_attached BOOLEAN;
BEGIN
    -- Guard 1: authentication is mandatory. Never allow a NULL-uid bypass.
    IF v_caller_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Authentication required');
    END IF;

    -- Lock the row so two seller actions cannot race the status transition.
    SELECT * INTO v_order
    FROM public.orders
    WHERE order_id = p_order_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Order not found');
    END IF;

    -- Guard 2: caller must be this order's seller.
    IF v_order.seller_id IS DISTINCT FROM v_caller_id THEN
        RETURN jsonb_build_object('success', false, 'error', 'Only the seller can cancel this order');
    END IF;

    -- Guard 3: terminal / post-handoff states cannot be cancelled.
    IF v_order.status IN (
        'HANDOFF_PENDING', 'COMPLETED', 'CANCELLED', 'DISPUTED',
        'REFUND_REQUESTED', 'RETURN_REQUESTED', 'RETURN_APPROVED',
        'RETURN_COMPLETED', 'REFUND_COMPLETED', 'REFUND_PENDING'
    ) THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'Order cannot be cancelled from state: ' || v_order.status
        );
    END IF;

    v_payment_attached := v_order.amount_centavos IS NOT NULL
        OR v_order.payment_reference IS NOT NULL;

    -- Guard 4: money routing mirrors OrderService.cancelOrder exactly.
    IF v_order.status = 'ESCROWED' THEN
        v_target_status := 'REFUND_PENDING';
    ELSIF v_order.status = 'PAYMENT_VERIFIED' THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'Payment has been verified for this order. Request a refund instead.'
        );
    ELSIF v_order.status IN ('MEETUP_SCHEDULED', 'NEEDS_REVIEW') AND v_payment_attached THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'This order has payment attached. Request a refund instead.'
        );
    ELSIF v_order.status = 'PAYMENT_SUBMITTED' THEN
        v_target_status := 'REFUND_REQUESTED';
    ELSE
        -- PLACED, ACCEPTED, AWAITING_PAYMENT (seller aborts checkout), MEETUP_SCHEDULED (cash).
        v_target_status := 'CANCELLED';
    END IF;

    UPDATE public.orders
    SET status = v_target_status,
        paymongo_checkout_session_id = CASE
            WHEN v_order.status = 'AWAITING_PAYMENT' THEN NULL
            ELSE paymongo_checkout_session_id
        END,
        paymongo_checkout_url = CASE
            WHEN v_order.status = 'AWAITING_PAYMENT' THEN NULL
            ELSE paymongo_checkout_url
        END,
        payment_expires_at = CASE
            WHEN v_order.status = 'AWAITING_PAYMENT' THEN NULL
            ELSE payment_expires_at
        END,
        version = COALESCE(version, 0) + 1
    WHERE order_id = p_order_id;

    -- Audit trail is best-effort: a logging failure must not undo the transition.
    BEGIN
        INSERT INTO public.order_audit_logs
            (order_id, actor_id, previous_status, new_status, method)
        VALUES
            (p_order_id, v_caller_id, v_order.status, v_target_status,
             left('RPC_SELLER_CANCEL - ' || v_reason, 100));
    EXCEPTION WHEN OTHERS THEN
        NULL;
    END;

    RETURN jsonb_build_object(
        'success', true,
        'order_id', p_order_id,
        'status', v_target_status,
        'previous_status', v_order.status
    );
END;
$$;

-- 3. Lock down execution: authenticated sellers and backend service only.
REVOKE ALL ON FUNCTION public.cancel_order_as_seller(UUID, TEXT) FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        EXECUTE 'REVOKE ALL ON FUNCTION public.cancel_order_as_seller(UUID, TEXT) FROM anon';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION public.cancel_order_as_seller(UUID, TEXT) TO authenticated';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION public.cancel_order_as_seller(UUID, TEXT) TO service_role';
    END IF;
END $$;

COMMENT ON FUNCTION public.cancel_order_as_seller(UUID, TEXT)
IS 'Seller-only, authenticated cancellation. Routes escrowed orders to REFUND_PENDING and releases inventory holds.';
