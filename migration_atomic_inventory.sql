-- ==============================================================================
-- TeknoyCart: Atomic Inventory Management & Order Lifecycle Sync
-- Migration: migration_atomic_inventory.sql
-- ==============================================================================

-- 1. Add is_preorder_enabled column to products table
ALTER TABLE public.products 
ADD COLUMN IF NOT EXISTS is_preorder_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Inventory sync trigger on orders
-- Single Source of Truth: ONLY this trigger mutates inventory when an order changes status.
CREATE OR REPLACE FUNCTION trg_order_status_inventory_sync()
RETURNS TRIGGER AS $$
BEGIN
    -- Physical Handoff Occurred (or Direct Completion):
    -- Deduct physical stock and release reservation lock
    IF (NEW.status = 'HANDOFF_PENDING' AND (OLD.status IS NULL OR OLD.status != 'HANDOFF_PENDING'))
       OR (NEW.status = 'COMPLETED' AND (OLD.status IS NULL OR (OLD.status != 'COMPLETED' AND OLD.status != 'HANDOFF_PENDING' AND OLD.status NOT IN ('RETURN_REQUESTED', 'RETURN_APPROVED', 'DISPUTED')))) THEN
        UPDATE public.inventory
        SET stock_qty = GREATEST(0, stock_qty - COALESCE(NEW.quantity, 1)),
            reserved_qty = GREATEST(0, reserved_qty - COALESCE(NEW.quantity, 1)),
            last_updated = NOW()
        WHERE variant_id = NEW.variant_id;

    -- Physical Return Completed:
    -- Returned item physically handed back to seller -> replenish physical stock
    ELSIF NEW.status = 'RETURN_COMPLETED' AND (OLD.status IS NULL OR OLD.status != 'RETURN_COMPLETED') THEN
        UPDATE public.inventory
        SET stock_qty = stock_qty + COALESCE(NEW.quantity, 1),
            last_updated = NOW()
        WHERE variant_id = NEW.variant_id;

    -- Pre-Handoff Cancellation, Rejection, or Refund:
    -- Item never left seller -> release the reserved hold back to available stock.
    -- If handoff already completed (handoff_completed_at IS NOT NULL), reserved_qty was
    -- already deducted at handoff and stock_qty already physically left the seller.
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
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS trg_sync_order_status_to_inventory ON public.orders;
CREATE TRIGGER trg_sync_order_status_to_inventory
AFTER UPDATE OF status ON public.orders
FOR EACH ROW
EXECUTE FUNCTION trg_order_status_inventory_sync();

-- 2b. Reconcile inventory holds RPC
-- Recalculates reserved_qty based on exact sum of active in-flight pre-handoff orders.
-- Clears orphaned/phantom reservations safely without altering legitimate orders.
CREATE OR REPLACE FUNCTION reconcile_inventory_holds(p_variant_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_actual_reserved INT;
    v_stock INT;
    v_new_reserved INT;
BEGIN
    SELECT COALESCE(SUM(quantity), 0)
    INTO v_actual_reserved
    FROM public.orders
    WHERE variant_id = p_variant_id
      AND status IN ('PLACED', 'ACCEPTED', 'PAYMENT_SUBMITTED', 'PAYMENT_VERIFIED', 'MEETUP_SCHEDULED', 'NEEDS_REVIEW', 'REFUND_REQUESTED');

    UPDATE public.inventory
    SET reserved_qty = v_actual_reserved,
        last_updated = NOW()
    WHERE variant_id = p_variant_id
    RETURNING stock_qty, reserved_qty INTO v_stock, v_new_reserved;

    RETURN jsonb_build_object(
        'success', true,
        'variant_id', p_variant_id,
        'stock_qty', v_stock,
        'reserved_qty', v_new_reserved,
        'available_qty', GREATEST(0, v_stock - v_new_reserved)
    );
END;
$$;


-- 3. Atomic reservation RPC with SELECT ... FOR UPDATE row-level locking
-- Enforces security guards: authentication, active product, no self-purchasing, positive qty.
CREATE OR REPLACE FUNCTION reserve_inventory_atomic(
    p_variant_id UUID,
    p_quantity INT DEFAULT 1,
    p_allow_preorder BOOLEAN DEFAULT FALSE
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_buyer_id UUID;
    v_product_status VARCHAR(50);
    v_seller_id UUID;
    v_db_preorder BOOLEAN;
    v_stock INT;
    v_reserved INT;
    v_available INT;
BEGIN
    -- Guard 1: Must be an authenticated user
    v_buyer_id := auth.uid();
    IF v_buyer_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'UNAUTHORIZED');
    END IF;

    -- Guard 2: Quantity sanity check
    IF p_quantity IS NULL OR p_quantity <= 0 THEN
        RETURN jsonb_build_object('success', false, 'error', 'INVALID_QUANTITY');
    END IF;

    -- Guard 3 & 4: Join product and lock inventory row exclusively (FOR UPDATE OF inv)
    SELECT 
        p.status,
        p.seller_id,
        p.is_preorder_enabled,
        inv.stock_qty,
        inv.reserved_qty
    INTO 
        v_product_status,
        v_seller_id,
        v_db_preorder,
        v_stock,
        v_reserved
    FROM public.inventory inv
    JOIN public.product_variants pv ON pv.variant_id = inv.variant_id
    JOIN public.products p ON p.product_id = pv.product_id
    WHERE inv.variant_id = p_variant_id
    FOR UPDATE OF inv;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'INVENTORY_NOT_FOUND');
    END IF;

    -- Guard 5: Product must be ACTIVE
    IF v_product_status != 'ACTIVE' THEN
        RETURN jsonb_build_object('success', false, 'error', 'PRODUCT_NOT_ACTIVE');
    END IF;

    -- Guard 6: Prevent seller from reserving own item
    IF v_seller_id = v_buyer_id THEN
        RETURN jsonb_build_object('success', false, 'error', 'CANNOT_RESERVE_OWN_PRODUCT');
    END IF;

    v_available := v_stock - v_reserved;

    -- Path A: Physical stock available -> lock in reserved_qty atomically
    IF v_available >= p_quantity THEN
        UPDATE public.inventory
        SET reserved_qty = reserved_qty + p_quantity,
            last_updated = NOW()
        WHERE variant_id = p_variant_id;

        RETURN jsonb_build_object(
            'success', true,
            'is_preorder', false,
            'reserved_count', p_quantity,
            'remaining_available', v_available - p_quantity
        );

    -- Path B: Stock exhausted, but pre-order is permitted (Option A)
    ELSIF v_db_preorder = TRUE OR p_allow_preorder = TRUE THEN
        RETURN jsonb_build_object(
            'success', true,
            'is_preorder', true,
            'reserved_count', 0,
            'remaining_available', 0
        );

    -- Path C: Stock exhausted, no pre-orders allowed
    ELSE
        RETURN jsonb_build_object(
            'success', false,
            'error', 'INSUFFICIENT_STOCK',
            'available', v_available
        );
    END IF;
END;
$$;


-- 4. Expired reservation sweeper
-- CRITICAL ARCHITECTURAL GUARANTEE:
-- This function ONLY updates order status to 'CANCELLED'.
-- It does NOT directly mutate inventory, guaranteeing the trigger trg_order_status_inventory_sync
-- is the sole path executing the reserved_qty decrement (zero double-release bugs).
CREATE OR REPLACE FUNCTION release_expired_reservations()
RETURNS INT
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_cancelled_count INT := 0;
BEGIN
    WITH expired_orders AS (
        SELECT order_id
        FROM public.orders
        WHERE reservation_expires_at IS NOT NULL
          AND reservation_expires_at < NOW()
          AND status IN ('APPROVED', 'INQUIRY_SENT', 'PLACED', 'PENDING_SELLER_ACCEPT')
        FOR UPDATE SKIP LOCKED
    )
    UPDATE public.orders o
    SET status = 'CANCELLED'
    FROM expired_orders eo
    WHERE o.order_id = eo.order_id;

    GET DIAGNOSTICS v_cancelled_count = ROW_COUNT;
    RETURN v_cancelled_count;
END;
$$;

-- Grant execution to authenticated users
GRANT EXECUTE ON FUNCTION reserve_inventory_atomic(UUID, INT, BOOLEAN) TO authenticated;
GRANT EXECUTE ON FUNCTION release_expired_reservations() TO authenticated;
