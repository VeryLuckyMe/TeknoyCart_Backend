-- ============================================================================
-- V19: admin payment-exception RPCs + cryptographically secure handoff OTP
--      (Defense sprint, Day 3)
-- ============================================================================
-- 1. payment_exceptions (V18) has no client grants. Admins reach it ONLY
--    through these SECURITY DEFINER functions, which check role = 'ADMIN'.
-- 2. Handoff OTPs were generated with random(), which is NOT a CSPRNG and is
--    predictable. Every live public function using that expression is
--    rewritten in place to use gen_random_uuid() (core Postgres 13+, backed by
--    the OS CSPRNG; no pgcrypto/search_path dependency).
--
-- Idempotent: safe to run more than once.
-- ============================================================================

-- ── Shared admin guard ──────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.assert_admin_caller()
RETURNS void
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF current_setting('request.jwt.claim.role', true) = 'service_role' THEN
        RETURN;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.users
         WHERE user_id = auth.uid()
           AND UPPER(COALESCE(role, '')) = 'ADMIN'
    ) THEN
        RAISE EXCEPTION 'Access denied. Administrator privileges required.'
            USING ERRCODE = '42501';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION public.assert_admin_caller() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.assert_admin_caller() TO authenticated, service_role;

-- ── List ────────────────────────────────────────────────────────────────────
DROP FUNCTION IF EXISTS public.admin_list_payment_exceptions(TEXT);
CREATE FUNCTION public.admin_list_payment_exceptions(p_status TEXT DEFAULT 'OPEN')
RETURNS TABLE (
    id                UUID,
    order_id          UUID,
    event_id          TEXT,
    session_id        TEXT,
    kind              TEXT,
    amount_centavos   BIGINT,
    expected_centavos BIGINT,
    order_status      TEXT,
    current_order_status TEXT,
    buyer_name        TEXT,
    seller_name       TEXT,
    details           TEXT,
    status            TEXT,
    resolved_by       UUID,
    resolved_at       TIMESTAMPTZ,
    resolution_note   TEXT,
    created_at        TIMESTAMPTZ
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    PERFORM public.assert_admin_caller();
    RETURN QUERY
    SELECT e.id, e.order_id, e.event_id, e.session_id, e.kind,
           e.amount_centavos, e.expected_centavos, e.order_status,
           o.status::TEXT,
           bu.full_name::TEXT, su.full_name::TEXT,
           e.details, e.status, e.resolved_by, e.resolved_at,
           e.resolution_note, e.created_at
      FROM public.payment_exceptions e
      LEFT JOIN public.orders o ON o.order_id = e.order_id
      LEFT JOIN public.users bu ON bu.user_id = o.buyer_id
      LEFT JOIN public.users su ON su.user_id = o.seller_id
     WHERE p_status IS NULL OR p_status = 'ALL' OR e.status = p_status
     ORDER BY (e.status = 'OPEN') DESC, e.created_at DESC
     LIMIT 500;
END;
$$;
REVOKE ALL ON FUNCTION public.admin_list_payment_exceptions(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_list_payment_exceptions(TEXT) TO authenticated, service_role;

-- ── Count (sidebar badge) ───────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.admin_count_open_payment_exceptions()
RETURNS INTEGER
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    PERFORM public.assert_admin_caller();
    RETURN (SELECT count(*)::INTEGER FROM public.payment_exceptions WHERE status = 'OPEN');
END;
$$;
REVOKE ALL ON FUNCTION public.admin_count_open_payment_exceptions() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_count_open_payment_exceptions() TO authenticated, service_role;

-- ── Resolve ─────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.admin_resolve_payment_exception(
    p_id     UUID,
    p_status TEXT,
    p_note   TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_row public.payment_exceptions%ROWTYPE;
    v_note TEXT := NULLIF(btrim(COALESCE(p_note, '')), '');
BEGIN
    PERFORM public.assert_admin_caller();

    IF p_status NOT IN ('REFUNDED', 'RESOLVED_NO_ACTION') THEN
        RETURN jsonb_build_object('success', false, 'error', 'INVALID_STATUS');
    END IF;
    IF v_note IS NULL THEN
        -- A refund or dismissal must say why (PayMongo refund id, reason, ...).
        RETURN jsonb_build_object('success', false, 'error', 'NOTE_REQUIRED');
    END IF;

    SELECT * INTO v_row FROM public.payment_exceptions WHERE id = p_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'NOT_FOUND');
    END IF;
    IF v_row.status <> 'OPEN' THEN
        RETURN jsonb_build_object('success', false, 'error', 'ALREADY_RESOLVED');
    END IF;

    UPDATE public.payment_exceptions
       SET status = p_status,
           resolved_by = auth.uid(),
           resolved_at = now(),
           resolution_note = left(v_note, 1000)
     WHERE id = p_id;

    RETURN jsonb_build_object('success', true, 'id', p_id, 'status', p_status);
END;
$$;
REVOKE ALL ON FUNCTION public.admin_resolve_payment_exception(UUID, TEXT, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_resolve_payment_exception(UUID, TEXT, TEXT) TO authenticated, service_role;

-- ── Secure OTP ──────────────────────────────────────────────────────────────
-- Rewrites every public function whose body still uses the random() OTP
-- expression. The first 8 hex digits of a v4 UUID are 32 fully random bits;
-- modulo bias over 900000 is < 0.03%.
DO $$
DECLARE
    r RECORD;
    v_def TEXT;
    v_old CONSTANT TEXT := 'lpad((floor(random() * 900000) + 100000)::text, 6, ''0'')';
    v_new CONSTANT TEXT :=
        '(100000 + ((''x'' || substr(replace(gen_random_uuid()::text, ''-'', ''''), 1, 8))::bit(32)::bigint % 900000))::text';
BEGIN
    FOR r IN
        SELECT p.oid
          FROM pg_proc p
          JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'public'
           AND p.prokind = 'f'
           AND position(v_old IN p.prosrc) > 0
    LOOP
        v_def := pg_get_functiondef(r.oid);
        EXECUTE replace(v_def, v_old, v_new);
        RAISE NOTICE 'Secure OTP applied to %', r.oid::regprocedure;
    END LOOP;
END;
$$;
