-- ====================================================================
-- TeknoyCart: Order Status & Audit Resilience Migration (V5)
-- Platform: Supabase / PostgreSQL
-- ====================================================================

-- 1. Ensure status column in public.orders is VARCHAR without legacy default or enum lock
DO $$
BEGIN
    -- Drop default on status if present
    ALTER TABLE public.orders ALTER COLUMN status DROP DEFAULT;
    -- Convert column to VARCHAR(50)
    ALTER TABLE public.orders ALTER COLUMN status TYPE VARCHAR(50) USING status::TEXT;
    -- Set default to 'PLACED'
    ALTER TABLE public.orders ALTER COLUMN status SET DEFAULT 'PLACED';
EXCEPTION WHEN OTHERS THEN
    NULL;
END $$;

-- 2. Drop any legacy check constraints on public.orders.status
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT conname 
        FROM pg_constraint 
        WHERE conrelid = 'public.orders'::regclass 
          AND contype = 'c' 
          AND pg_get_constraintdef(oid) LIKE '%status%'
    ) LOOP
        EXECUTE 'ALTER TABLE public.orders DROP CONSTRAINT IF EXISTS ' || quote_ident(r.conname);
    END LOOP;
END $$;

-- 3. If order_status enum exists, add all application states for safety
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_type WHERE typname = 'order_status') THEN
        BEGIN
            ALTER TYPE order_status ADD VALUE IF NOT EXISTS 'PLACED';
            ALTER TYPE order_status ADD VALUE IF NOT EXISTS 'ACCEPTED';
            ALTER TYPE order_status ADD VALUE IF NOT EXISTS 'AWAITING_PAYMENT';
            ALTER TYPE order_status ADD VALUE IF NOT EXISTS 'ESCROWED';
            ALTER TYPE order_status ADD VALUE IF NOT EXISTS 'REFUND_PENDING';
            ALTER TYPE order_status ADD VALUE IF NOT EXISTS 'MEETUP_SCHEDULED';
            ALTER TYPE order_status ADD VALUE IF NOT EXISTS 'HANDOFF_PENDING';
            ALTER TYPE order_status ADD VALUE IF NOT EXISTS 'DISPUTED';
        EXCEPTION WHEN OTHERS THEN
            NULL;
        END;
    END IF;
END $$;

-- 4. Drop actor_id foreign key constraint on order_audit_logs if present
-- (Allows actor_id to log Supabase Auth user IDs without requiring a row in public.users)
ALTER TABLE public.order_audit_logs DROP CONSTRAINT IF EXISTS order_audit_logs_actor_id_fkey;

-- 5. Ensure PayMongo GCash checkout columns exist
ALTER TABLE public.orders
  ADD COLUMN IF NOT EXISTS payment_method TEXT,
  ADD COLUMN IF NOT EXISTS paymongo_checkout_session_id TEXT,
  ADD COLUMN IF NOT EXISTS paymongo_checkout_url TEXT,
  ADD COLUMN IF NOT EXISTS payment_expires_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS amount_centavos BIGINT,
  ADD COLUMN IF NOT EXISTS payout_released BOOLEAN NOT NULL DEFAULT false;

-- 6. Ensure version column has DEFAULT 0 and backfill any NULL rows
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' AND table_name = 'orders' AND column_name = 'version'
    ) THEN
        ALTER TABLE public.orders ALTER COLUMN version SET DEFAULT 0;
        UPDATE public.orders SET version = 0 WHERE version IS NULL;
    ELSE
        ALTER TABLE public.orders ADD COLUMN version BIGINT DEFAULT 0;
    END IF;
END $$;
