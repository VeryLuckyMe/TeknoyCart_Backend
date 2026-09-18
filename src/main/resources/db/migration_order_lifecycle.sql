-- ====================================================================
-- TeknoyCart: Courier-Free Delivery Confirmation & Lifecycle Migration
-- Platform: Supabase / PostgreSQL
-- ====================================================================

-- 1. Extend orders table with Return OTP, Dispute, Refund, and Inspection timestamps
ALTER TABLE public.orders
ADD COLUMN IF NOT EXISTS seller_handed_off BOOLEAN DEFAULT false,
ADD COLUMN IF NOT EXISTS buyer_confirmed_receipt BOOLEAN DEFAULT false,
ADD COLUMN IF NOT EXISTS handoff_otp VARCHAR(6),
ADD COLUMN IF NOT EXISTS otp_created_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS otp_failed_attempts INT DEFAULT 0,
ADD COLUMN IF NOT EXISTS handoff_completed_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS return_otp VARCHAR(6),
ADD COLUMN IF NOT EXISTS return_otp_created_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS return_otp_failed_attempts INT DEFAULT 0,
ADD COLUMN IF NOT EXISTS refund_reference VARCHAR(100),
ADD COLUMN IF NOT EXISTS dispute_reason TEXT,
ADD COLUMN IF NOT EXISTS dispute_ruling VARCHAR(50),
ADD COLUMN IF NOT EXISTS return_completed_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;

-- 2. Convert status to VARCHAR if not already, ensuring smooth transitions across state machine
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' AND table_name = 'orders' AND column_name = 'status'
        AND data_type = 'USER-DEFINED'
    ) THEN
        ALTER TABLE public.orders ALTER COLUMN status TYPE VARCHAR USING status::TEXT;
    END IF;
END $$;

-- 3. Ensure order_audit_logs exists with indexes for fast traversal
CREATE TABLE IF NOT EXISTS public.order_audit_logs (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES public.orders(order_id) ON DELETE CASCADE,
    actor_id UUID REFERENCES public.users(user_id) ON DELETE SET NULL, -- NULL denotes SYSTEM
    previous_status VARCHAR(50),
    new_status VARCHAR(50) NOT NULL,
    method VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_order_audit_logs_order_created 
ON public.order_audit_logs (order_id, created_at DESC);

-- 4. Index for timeout service background sweep on stale meetups & 24h inspection completion
CREATE INDEX IF NOT EXISTS idx_orders_status_handoff_completed 
ON public.orders (status, handoff_completed_at)
WHERE status IN ('HANDOFF_PENDING', 'MEETUP_SCHEDULED');

-- 5. RLS policies for order_audit_logs
ALTER TABLE public.order_audit_logs ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE tablename = 'order_audit_logs' AND policyname = 'Allow read audit logs'
    ) THEN
        CREATE POLICY "Allow read audit logs" ON public.order_audit_logs
            FOR SELECT USING (true);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE tablename = 'order_audit_logs' AND policyname = 'Allow insert audit logs'
    ) THEN
        CREATE POLICY "Allow insert audit logs" ON public.order_audit_logs
            FOR INSERT WITH CHECK (true);
    END IF;
END $$;
