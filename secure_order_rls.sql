-- =============================================================
-- TeknoyCart: Secure Row Level Security (RLS) on Orders
-- Ensures all order state transitions, handoff confirmations,
-- and status updates are mediated exclusively by the Spring Boot backend.
-- =============================================================

-- 1. Ensure RLS is active on public.orders
ALTER TABLE public.orders ENABLE ROW LEVEL SECURITY;

-- 2. Drop existing triggers from earlier iterations if present
DROP TRIGGER IF EXISTS trg_prevent_order_status_tampering ON public.orders;
DROP FUNCTION IF EXISTS prevent_order_status_tampering();

-- 3. Drop all known existing policies on public.orders
-- (Includes the currently-active Supabase policies: 'Buyers can update own orders',
--  'Buyers can insert own orders', and 'Users can view own orders')
DROP POLICY IF EXISTS "Buyers can update own orders" ON public.orders;
DROP POLICY IF EXISTS "Buyers can insert own orders" ON public.orders;
DROP POLICY IF EXISTS "Users can view own orders" ON public.orders;
DROP POLICY IF EXISTS "Enable read access for all users" ON public.orders;
DROP POLICY IF EXISTS "Enable insert access for all users" ON public.orders;
DROP POLICY IF EXISTS "Enable update access for all users" ON public.orders;
DROP POLICY IF EXISTS "Public read orders" ON public.orders;
DROP POLICY IF EXISTS "Public update orders" ON public.orders;
DROP POLICY IF EXISTS "Block direct client updates on orders" ON public.orders;
DROP POLICY IF EXISTS "orders_select_policy" ON public.orders;
DROP POLICY IF EXISTS "orders_insert_policy" ON public.orders;
DROP POLICY IF EXISTS "orders_update_policy" ON public.orders;

-- 4. SELECT Policy: Buyers and sellers can only view orders they are party to
CREATE POLICY "Users can view own orders"
    ON public.orders
    FOR SELECT
    TO authenticated
    USING (
        auth.uid() = buyer_id OR auth.uid() = seller_id
    );

-- 5. INSERT Policy: Authenticated users can place new orders as the buyer.
-- Validates that buyer_id matches the session UID, status begins at PLACED/INQUIRY_SENT,
-- and confirmation flags/OTP cannot be pre-forged at creation.
CREATE POLICY "Buyers can insert own orders"
    ON public.orders
    FOR INSERT
    TO authenticated
    WITH CHECK (
        auth.uid() = buyer_id
        AND (status::text = 'PLACED' OR status::text = 'INQUIRY_SENT')
        AND (seller_handed_off IS FALSE OR seller_handed_off IS NULL)
        AND (buyer_confirmed_receipt IS FALSE OR buyer_confirmed_receipt IS NULL)
        AND handoff_otp IS NULL
        AND payment_reference IS NULL
        AND payment_proof_url IS NULL
    );

-- 6. UPDATE & DELETE:
-- NO policies are granted to 'authenticated' or 'anon' for UPDATE or DELETE.
-- Direct client mutations via PostgREST are blocked by PostgreSQL default-deny.
-- All transitions (accept, cancel, schedule meetup, verify handoff, confirm receipt, refund)
-- must be executed through the Spring Boot backend using the service_role credentials
-- (which bypasses RLS).
