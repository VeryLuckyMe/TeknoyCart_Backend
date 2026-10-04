-- ==============================================================================
-- Migration: Secure Private KYC Storage Bucket & RLS Policies (RA 10173 Compliance)
-- ==============================================================================
-- 1. Create the dedicated 'seller-kyc-documents' bucket with public = false.
-- 2. Restrict storage.objects policies so only the applicant and university
--    administrators can view/download student ID cards and selfies.
-- 3. Scope public read access to public buckets ONLY (product-images, chat-images).
-- ==============================================================================

-- 1. Create private bucket for KYC documents
INSERT INTO storage.buckets (id, name, public) 
VALUES ('seller-kyc-documents', 'seller-kyc-documents', false) 
ON CONFLICT (id) DO UPDATE SET public = false;

-- 2. Drop overly-broad public read policy on all storage objects if present
DROP POLICY IF EXISTS "Public Read Access" ON storage.objects;

-- 3. Re-create public read access scoped ONLY to genuinely public buckets
CREATE POLICY "Public Read Access" ON storage.objects
    FOR SELECT
    USING (bucket_id IN ('product-images', 'chat-images'));

-- 4. KYC Private Storage Policy: Only the uploading user and verified Admins can read KYC docs
DROP POLICY IF EXISTS "KYC Document View Policy" ON storage.objects;
CREATE POLICY "KYC Document View Policy" ON storage.objects
    FOR SELECT
    TO authenticated
    USING (
        bucket_id = 'seller-kyc-documents'
        AND (
            -- Applicant owns the folder: kyc/<user_id>/...
            name LIKE 'kyc/' || auth.uid()::text || '/%'
            -- Or caller is an Administrator
            OR EXISTS (
                SELECT 1 FROM public.users
                WHERE user_id = auth.uid() AND role = 'ADMIN'
            )
        )
    );

-- 5. KYC Upload Policy: Authenticated users can upload only into their own KYC folder
DROP POLICY IF EXISTS "KYC Document Upload Policy" ON storage.objects;
CREATE POLICY "KYC Document Upload Policy" ON storage.objects
    FOR INSERT
    TO authenticated
    WITH CHECK (
        bucket_id = 'seller-kyc-documents'
        AND name LIKE 'kyc/' || auth.uid()::text || '/%'
    );

-- 6. Zero-Retention Purge Policy: Admins and owners can delete KYC documents
DROP POLICY IF EXISTS "KYC Document Delete Policy" ON storage.objects;
CREATE POLICY "KYC Document Delete Policy" ON storage.objects
    FOR DELETE
    TO authenticated
    USING (
        bucket_id = 'seller-kyc-documents'
        AND (
            name LIKE 'kyc/' || auth.uid()::text || '/%'
            OR EXISTS (
                SELECT 1 FROM public.users
                WHERE user_id = auth.uid() AND role = 'ADMIN'
            )
        )
    );
