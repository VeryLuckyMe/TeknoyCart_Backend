-- ====================================================================
-- TeknoyCart: PayMongo GCash Payment Hold Migration (Rev 2 — 2026)
-- Platform: Supabase / PostgreSQL
-- Apply MANUALLY before restarting the Spring Boot backend.
-- ddl-auto is set to 'validate', so Hibernate will verify these columns exist.
--
-- NOTE: No ALTER TYPE order_status is needed.
-- The `status` column was already converted to VARCHAR by migration_order_lifecycle.sql.
-- New statuses (AWAITING_PAYMENT, ESCROWED, REFUND_PENDING) exist only in OrderStatus.java
-- and are mapped via OrderStatusConverter.
-- ====================================================================

-- 1. Add PayMongo checkout columns to orders
ALTER TABLE public.orders
  ADD COLUMN IF NOT EXISTS payment_method        TEXT,
  ADD COLUMN IF NOT EXISTS paymongo_checkout_session_id TEXT,
  ADD COLUMN IF NOT EXISTS paymongo_checkout_url TEXT,
  ADD COLUMN IF NOT EXISTS payment_expires_at    TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS amount_centavos       BIGINT,
  ADD COLUMN IF NOT EXISTS payout_released       BOOLEAN NOT NULL DEFAULT false;

-- 2. Payment events idempotency table
--    event_id is the PayMongo webhook event ID (globally unique); used as PK for idempotency.
--    PayMongo retries delivery up to 12 times, so insert-or-skip on conflict is the contract.
CREATE TABLE IF NOT EXISTS public.payment_events (
  event_id    TEXT        PRIMARY KEY,
  order_id    UUID        REFERENCES public.orders(order_id) ON DELETE SET NULL,
  type        TEXT        NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3. Index for expiry sweep job (Phase 6)
--    Filters AWAITING_PAYMENT orders with expired sessions — covers the hot query path.
CREATE INDEX IF NOT EXISTS idx_orders_awaiting_payment_expiry
  ON public.orders (status, payment_expires_at)
  WHERE status = 'AWAITING_PAYMENT';

-- 4. Row-Level Security for payment_events
--    No client (anon / authenticated) policies. Service role only.
ALTER TABLE public.payment_events ENABLE ROW LEVEL SECURITY;

-- NOTE: Do NOT add any anon/authenticated SELECT/INSERT policies here.
-- All writes go through the Spring Boot service role connection.
-- ====================================================================
