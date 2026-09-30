-- ==============================================================================
-- Migration: Seller KYC Verification Table with Zero-Retention Policy
-- ==============================================================================
-- Stores pending seller verification requests for individual and organization accounts.
-- Supports the Shopee-style Lead Officer Custodian model for campus organizations.
-- Photos are wiped from Supabase Storage upon approval/rejection under RA 10173.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS public.seller_verifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.users(user_id) ON DELETE CASCADE,
    seller_type TEXT NOT NULL DEFAULT 'STUDENT', -- 'STUDENT' or 'ORG'
    officer_name TEXT,                          -- Custodian full name (for ORG)
    officer_position TEXT,                      -- Custodian title, e.g. Treasurer, President (for ORG)
    id_card_url TEXT NOT NULL,                  -- Signed or public URL for inspection
    selfie_url TEXT NOT NULL,                   -- Signed or public URL for inspection
    id_card_storage_path TEXT,                  -- Storage object key for zero-retention deletion
    selfie_storage_path TEXT,                   -- Storage object key for zero-retention deletion
    status TEXT NOT NULL DEFAULT 'PENDING',     -- 'PENDING', 'APPROVED', 'REJECTED'
    rejection_reason TEXT,                      -- Detailed note from university admin
    reviewed_by UUID REFERENCES public.users(user_id),
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Index for instant queue filtering by admin and user lookup
CREATE INDEX IF NOT EXISTS idx_seller_verifications_user ON public.seller_verifications(user_id);
CREATE INDEX IF NOT EXISTS idx_seller_verifications_status ON public.seller_verifications(status);

-- Enable Row Level Security
ALTER TABLE public.seller_verifications ENABLE ROW LEVEL SECURITY;

-- 1. Users can view their own verification status
DROP POLICY IF EXISTS "Users can view own verifications" ON public.seller_verifications;
CREATE POLICY "Users can view own verifications"
    ON public.seller_verifications
    FOR SELECT
    USING (auth.uid() = user_id);

-- 2. Users can insert their own verification request
DROP POLICY IF EXISTS "Users can submit own verification" ON public.seller_verifications;
CREATE POLICY "Users can submit own verification"
    ON public.seller_verifications
    FOR INSERT
    WITH CHECK (auth.uid() = user_id);

-- 3. Users can update their own verification (e.g. resubmission when rejected)
DROP POLICY IF EXISTS "Users can update own rejected verification" ON public.seller_verifications;
CREATE POLICY "Users can update own rejected verification"
    ON public.seller_verifications
    FOR UPDATE
    USING (auth.uid() = user_id);

-- 4. Admins & service role have full access to review, approve, and reject
DROP POLICY IF EXISTS "Admins can view and manage all verifications" ON public.seller_verifications;
CREATE POLICY "Admins can view and manage all verifications"
    ON public.seller_verifications
    FOR ALL
    USING (
        current_setting('request.jwt.claim.role', true) = 'service_role'
        OR EXISTS (
            SELECT 1 FROM public.users
            WHERE user_id = auth.uid() AND role = 'ADMIN'
        )
    );
