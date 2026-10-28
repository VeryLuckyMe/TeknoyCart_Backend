-- ==============================================================================
-- TeknoyCart: Hotfix for "relation inventory does not exist" on Checkout
-- Apply in Supabase SQL Editor: https://supabase.com/dashboard/project/chmtvasbhkbrvydbajnd/sql
-- ==============================================================================

-- 1. Ensure inventory table exists
CREATE TABLE IF NOT EXISTS public.inventory (
    inventory_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    variant_id UUID NOT NULL UNIQUE REFERENCES public.product_variants(variant_id) ON DELETE CASCADE,
    stock_qty INT NOT NULL DEFAULT 0 CHECK (stock_qty >= 0),
    reserved_qty INT NOT NULL DEFAULT 0 CHECK (reserved_qty >= 0),
    low_stock_threshold INT NOT NULL DEFAULT 5,
    last_updated TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- 2. Populate missing inventory rows for existing product variants
INSERT INTO public.inventory (variant_id, stock_qty, reserved_qty, low_stock_threshold)
SELECT pv.variant_id, 10, 0, 3
FROM public.product_variants pv
WHERE NOT EXISTS (
    SELECT 1 FROM public.inventory inv WHERE inv.variant_id = pv.variant_id
)
ON CONFLICT (variant_id) DO NOTHING;

-- 3. Enable RLS and grant required permissions for authenticated buyers/sellers
ALTER TABLE public.inventory ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "inventory_select_all" ON public.inventory;
CREATE POLICY "inventory_select_all" ON public.inventory
    FOR SELECT TO public USING (true);

DROP POLICY IF EXISTS "inventory_update_all" ON public.inventory;
CREATE POLICY "inventory_update_all" ON public.inventory
    FOR UPDATE TO authenticated USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "inventory_insert_all" ON public.inventory;
CREATE POLICY "inventory_insert_all" ON public.inventory
    FOR INSERT TO authenticated WITH CHECK (true);

-- 4. Re-create place_orders_atomic with search_path = public, pg_temp
CREATE OR REPLACE FUNCTION public.place_orders_atomic(
  p_lines jsonb,
  p_payment_method text
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_buyer uuid := auth.uid();
  v_line jsonb;
  v_idx bigint;
  v_product_id uuid;
  v_variant_id uuid;
  v_quantity integer;
  v_unit_price numeric;
  v_total numeric;
  v_seller uuid;
  v_preorder_enabled boolean;
  v_product_status text;
  v_stock integer;
  v_reserved integer;
  v_is_preorder boolean;
  v_inquiry uuid;
  v_order uuid;
  v_orders jsonb := '[]'::jsonb;
  v_lines_count integer;
BEGIN
  IF v_buyer IS NULL THEN
    RETURN jsonb_build_object('success', false, 'error', 'UNAUTHORIZED');
  END IF;
  IF jsonb_typeof(p_lines) <> 'array' OR jsonb_array_length(p_lines) NOT BETWEEN 1 AND 50 THEN
    RETURN jsonb_build_object('success', false, 'error', 'INVALID_LINES');
  END IF;
  IF p_payment_method NOT IN ('GCASH', 'CASH_ON_PICKUP') THEN
    RETURN jsonb_build_object('success', false, 'error', 'INVALID_PAYMENT_METHOD');
  END IF;

  FOR v_line, v_idx IN
    SELECT line, ordinality
    FROM jsonb_array_elements(p_lines) WITH ORDINALITY AS x(line, ordinality)
    ORDER BY (line->>'variant_id')
  LOOP
    v_product_id := (v_line->>'product_id')::uuid;
    v_variant_id := (v_line->>'variant_id')::uuid;
    v_quantity := (v_line->>'quantity')::integer;
    IF v_quantity IS NULL OR v_quantity <= 0 THEN
      RAISE EXCEPTION 'INVALID_QUANTITY';
    END IF;

    SELECT p.seller_id, p.status::text, COALESCE(p.is_preorder_enabled, false),
           p.base_price + COALESCE(pv.additional_price, 0.00), 
           COALESCE(inv.stock_qty, 10), COALESCE(inv.reserved_qty, 0)
      INTO v_seller, v_product_status, v_preorder_enabled,
           v_unit_price, v_stock, v_reserved
      FROM public.products p
      JOIN public.product_variants pv ON pv.product_id = p.product_id
      LEFT JOIN public.inventory inv ON inv.variant_id = pv.variant_id
      JOIN public.users u ON u.user_id = p.seller_id
     WHERE p.product_id = v_product_id
       AND pv.variant_id = v_variant_id
       AND u.role = 'SELLER'
       AND u.is_seller_verified IS TRUE
     FOR UPDATE OF p;

    IF NOT FOUND THEN RAISE EXCEPTION 'INVALID_LISTING'; END IF;
    IF v_product_status <> 'ACTIVE' THEN RAISE EXCEPTION 'PRODUCT_NOT_ACTIVE'; END IF;
    IF v_seller = v_buyer THEN RAISE EXCEPTION 'CANNOT_PURCHASE_OWN_PRODUCT'; END IF;
    IF (v_line->>'is_preorder')::boolean IS TRUE AND v_preorder_enabled IS NOT TRUE THEN
      RAISE EXCEPTION 'PREORDER_NOT_ALLOWED';
    END IF;

    IF (v_line->>'unit_price')::numeric IS DISTINCT FROM v_unit_price THEN
      RAISE EXCEPTION 'PRICE_MISMATCH';
    END IF;
    v_total := round(v_unit_price * v_quantity, 2);
    v_is_preorder := FALSE;

    -- Ensure inventory row exists for this variant
    INSERT INTO public.inventory (variant_id, stock_qty, reserved_qty, low_stock_threshold)
    VALUES (v_variant_id, 10, 0, 3)
    ON CONFLICT (variant_id) DO NOTHING;

    IF v_stock - v_reserved >= v_quantity AND (v_line->>'is_preorder')::boolean IS NOT TRUE THEN
      UPDATE public.inventory
         SET reserved_qty = reserved_qty + v_quantity, last_updated = now()
       WHERE variant_id = v_variant_id;
    ELSIF v_preorder_enabled IS TRUE THEN
      v_is_preorder := TRUE;
    ELSE
      RAISE EXCEPTION 'INSUFFICIENT_STOCK';
    END IF;

    INSERT INTO public.inquiries (buyer_id, product_id, variant_id, quantity, inquiry_type, message)
    VALUES (v_buyer, v_product_id, v_variant_id, v_quantity, 'AVAILABILITY', 'Checkout order')
    RETURNING inquiry_id INTO v_inquiry;

    INSERT INTO public.orders (
      inquiry_id, buyer_id, seller_id, variant_id, quantity, unit_price, total_amount,
      payment_method, status, version, pickup_location, pickup_day, pickup_time,
      is_preorder, reservation_expires_at
    ) VALUES (
      v_inquiry, v_buyer, v_seller, v_variant_id, v_quantity, v_unit_price, v_total,
      p_payment_method, 'PLACED', 0,
      v_line->>'pickup_location', v_line->>'pickup_day', v_line->>'pickup_time',
      v_is_preorder, CASE WHEN v_is_preorder THEN NULL ELSE now() + interval '24 hours' END
    ) RETURNING order_id INTO v_order;

    v_orders := v_orders || jsonb_build_array(jsonb_build_object(
      'line_index', v_idx - 1, 'order_id', v_order, 'total_amount', v_total
    ));
  END LOOP;

  SELECT count(*) INTO v_lines_count FROM jsonb_array_elements(p_lines);
  IF jsonb_array_length(v_orders) <> v_lines_count THEN RAISE EXCEPTION 'CHECKOUT_INCOMPLETE'; END IF;
  RETURN jsonb_build_object('success', true, 'orders', v_orders);
EXCEPTION
  WHEN SQLSTATE 'P0001' THEN
    IF SQLERRM IN ('INVALID_QUANTITY', 'INVALID_LISTING', 'PRODUCT_NOT_ACTIVE',
                   'CANNOT_PURCHASE_OWN_PRODUCT', 'PREORDER_NOT_ALLOWED',
                   'INSUFFICIENT_STOCK', 'PRICE_MISMATCH') THEN
      RETURN jsonb_build_object('success', false, 'error', SQLERRM);
    END IF;
    RAISE;
END;
$$;

REVOKE ALL ON FUNCTION public.place_orders_atomic(jsonb, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.place_orders_atomic(jsonb, text) TO authenticated;
