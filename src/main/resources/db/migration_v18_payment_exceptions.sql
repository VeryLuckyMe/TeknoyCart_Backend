-- ============================================================================
-- V18: payment_exceptions  (Defense sprint, Day 2)
-- ============================================================================
-- Every PayMongo payment that cannot be applied to an order is recorded here
-- instead of only being logged: amount or session mismatches, payments that
-- arrive after the order expired or was cancelled, and unknown orders.
-- Admins review and resolve these from the web portal (Day 3).
--
-- Written by the backend with plain JDBC (PaymentExceptionRecorder), not JPA,
-- because hibernate ddl-auto=validate runs before boot migrations.
--
-- Idempotent: safe to run more than once.
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.payment_exceptions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id         UUID REFERENCES public.orders(order_id) ON DELETE SET NULL,
    event_id         TEXT,
    session_id       TEXT,
    kind             TEXT NOT NULL CHECK (kind IN (
                         'AMOUNT_MISMATCH',
                         'SESSION_MISMATCH',
                         'CURRENCY_MISMATCH',
                         'PAID_IN_UNEXPECTED_STATE',
                         'UNKNOWN_ORDER',
                         'MISSING_ORDER_ID')),
    amount_centavos  BIGINT,
    expected_centavos BIGINT,
    order_status     TEXT,
    details          TEXT,
    status           TEXT NOT NULL DEFAULT 'OPEN'
                         CHECK (status IN ('OPEN', 'REFUNDED', 'RESOLVED_NO_ACTION')),
    resolved_by      UUID,
    resolved_at      TIMESTAMPTZ,
    resolution_note  TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per (event, kind): a re-delivered event never duplicates the queue.
CREATE UNIQUE INDEX IF NOT EXISTS payment_exceptions_event_kind_uidx
    ON public.payment_exceptions (event_id, kind)
    WHERE event_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS payment_exceptions_open_idx
    ON public.payment_exceptions (created_at DESC)
    WHERE status = 'OPEN';

-- No client access: the app and admin portal reach this table only through
-- SECURITY DEFINER admin RPCs (added with the admin page).
ALTER TABLE public.payment_exceptions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.payment_exceptions FROM PUBLIC, anon, authenticated;
