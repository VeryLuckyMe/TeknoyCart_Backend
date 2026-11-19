-- ============================================================================
-- V21: GCash payout guard + order list badges
--
-- 1. A buyer may only pay by GCash when the seller has a GCash payout number,
--    otherwise escrowed money would have nowhere to be released to.
--    seller_accepts_gcash() answers yes/no without exposing the number, and a
--    BEFORE INSERT trigger on orders enforces it for every checkout path
--    (2-, 3- and 4-argument place_orders_atomic overloads all insert here).
-- 2. Badges for "Incoming Orders" and "My Purchases" that clear only when that
--    list is opened, independent of the notification bell.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1a. Yes/no check the checkout screen can call
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.seller_accepts_gcash(p_seller_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1
          FROM public.users u
         WHERE u.user_id = p_seller_id
           AND length(regexp_replace(COALESCE(u.gcash_number, ''), '[^0-9]', '', 'g')) >= 11
    );
$$;

REVOKE ALL ON FUNCTION public.seller_accepts_gcash(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.seller_accepts_gcash(UUID) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 1b. Server-side enforcement
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.orders_require_seller_gcash()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF NEW.payment_method::TEXT = 'GCASH'
       AND NOT public.seller_accepts_gcash(NEW.seller_id) THEN
        RAISE EXCEPTION 'SELLER_GCASH_NOT_SET';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.orders_require_seller_gcash() FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS trg_orders_require_seller_gcash ON public.orders;
CREATE TRIGGER trg_orders_require_seller_gcash
    BEFORE INSERT ON public.orders
    FOR EACH ROW
    EXECUTE FUNCTION public.orders_require_seller_gcash();

-- ----------------------------------------------------------------------------
-- 2a. When each user last opened each order list
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.order_list_seen (
    user_id UUID NOT NULL REFERENCES public.users(user_id) ON DELETE CASCADE,
    list    TEXT NOT NULL CHECK (list IN ('INCOMING', 'PURCHASES')),
    seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, list)
);

ALTER TABLE public.order_list_seen ENABLE ROW LEVEL SECURITY;
-- Only reachable through the SECURITY DEFINER functions below.
REVOKE ALL ON TABLE public.order_list_seen FROM PUBLIC, anon, authenticated;

-- ----------------------------------------------------------------------------
-- 2b. Badge counts: orders with news since that list was last opened.
--     Before a list is ever opened, only still-unread order alerts count, so
--     existing users are not greeted with their whole history.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.order_badge_counts()
RETURNS JSONB
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_uid       UUID := auth.uid();
    v_incoming  TIMESTAMPTZ;
    v_purchases TIMESTAMPTZ;
    v_in_count  INTEGER;
    v_pu_count  INTEGER;
BEGIN
    IF v_uid IS NULL THEN
        RETURN jsonb_build_object('incoming', 0, 'purchases', 0);
    END IF;

    SELECT seen_at INTO v_incoming
      FROM public.order_list_seen WHERE user_id = v_uid AND list = 'INCOMING';
    SELECT seen_at INTO v_purchases
      FROM public.order_list_seen WHERE user_id = v_uid AND list = 'PURCHASES';

    SELECT
        COUNT(DISTINCT o.order_id) FILTER (
            WHERE o.seller_id = v_uid
              AND (CASE WHEN v_incoming IS NULL THEN n.is_read = FALSE
                        ELSE n.created_at > v_incoming END)),
        COUNT(DISTINCT o.order_id) FILTER (
            WHERE o.buyer_id = v_uid
              AND (CASE WHEN v_purchases IS NULL THEN n.is_read = FALSE
                        ELSE n.created_at > v_purchases END))
      INTO v_in_count, v_pu_count
      FROM public.notifications n
      JOIN public.orders o ON o.order_id::TEXT = n.reference_id
     WHERE n.user_id = v_uid
       AND n.reference_type = 'ORDER';

    RETURN jsonb_build_object(
        'incoming',  COALESCE(v_in_count, 0),
        'purchases', COALESCE(v_pu_count, 0));
END;
$$;

REVOKE ALL ON FUNCTION public.order_badge_counts() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.order_badge_counts() TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 2c. Opening a list clears its badge and the matching bell alerts.
--     (Opening the bell never touches order_list_seen.)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.mark_order_list_seen(p_list TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_uid     UUID := auth.uid();
    v_cleared INTEGER;
BEGIN
    IF v_uid IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'UNAUTHORIZED');
    END IF;
    IF p_list IS NULL OR p_list NOT IN ('INCOMING', 'PURCHASES') THEN
        RETURN jsonb_build_object('success', false, 'error', 'INVALID_LIST');
    END IF;

    INSERT INTO public.order_list_seen (user_id, list, seen_at)
    VALUES (v_uid, p_list, NOW())
    ON CONFLICT (user_id, list) DO UPDATE SET seen_at = EXCLUDED.seen_at;

    UPDATE public.notifications n
       SET is_read = TRUE
      FROM public.orders o
     WHERE n.user_id = v_uid
       AND n.reference_type = 'ORDER'
       AND n.is_read = FALSE
       AND o.order_id::TEXT = n.reference_id
       AND ((p_list = 'INCOMING'  AND o.seller_id = v_uid)
         OR (p_list = 'PURCHASES' AND o.buyer_id  = v_uid));
    GET DIAGNOSTICS v_cleared = ROW_COUNT;

    RETURN jsonb_build_object('success', true, 'cleared_notifications', v_cleared);
END;
$$;

REVOKE ALL ON FUNCTION public.mark_order_list_seen(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.mark_order_list_seen(TEXT) TO authenticated, service_role;

NOTIFY pgrst, 'reload schema';
