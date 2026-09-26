-- =====================================================
-- TEKNOYCART - PRODUCT & SELLER REVIEWS MIGRATION
-- Enables verified campus buyers to rate and review products
-- and sellers to reply to reviews.
-- =====================================================

-- 1. Create product_reviews table
CREATE TABLE IF NOT EXISTS public.product_reviews (
    review_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NULL REFERENCES public.orders(order_id) ON DELETE SET NULL,
    product_id TEXT NOT NULL,
    buyer_id UUID NOT NULL REFERENCES public.users(user_id) ON DELETE CASCADE,
    buyer_name VARCHAR(100) NOT NULL DEFAULT 'CIT Student',
    seller_id UUID NOT NULL REFERENCES public.users(user_id) ON DELETE CASCADE,
    rating INTEGER NOT NULL CHECK (rating >= 1 AND rating <= 5),
    comment TEXT,
    tags TEXT[] DEFAULT '{}',
    image_urls TEXT[] DEFAULT '{}',
    variant_name VARCHAR(100),
    seller_reply TEXT,
    seller_replied_at TIMESTAMP WITH TIME ZONE,
    is_anonymous BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_order_buyer_review UNIQUE (order_id, buyer_id)
);

-- 2. Indexes for fast lookup
CREATE INDEX IF NOT EXISTS idx_reviews_product_id ON public.product_reviews(product_id);
CREATE INDEX IF NOT EXISTS idx_reviews_seller_id ON public.product_reviews(seller_id);
CREATE INDEX IF NOT EXISTS idx_reviews_buyer_id ON public.product_reviews(buyer_id);
CREATE INDEX IF NOT EXISTS idx_reviews_rating ON public.product_reviews(rating);
CREATE INDEX IF NOT EXISTS idx_reviews_order_id ON public.product_reviews(order_id);

-- 3. Row Level Security (RLS)
ALTER TABLE public.product_reviews ENABLE ROW LEVEL SECURITY;

-- Anyone (public/anon/authenticated) can read reviews
DROP POLICY IF EXISTS "reviews_select_all" ON public.product_reviews;
CREATE POLICY "reviews_select_all" ON public.product_reviews
    FOR SELECT TO public USING (true);

-- Authenticated buyers can insert their own reviews
DROP POLICY IF EXISTS "reviews_insert_buyer" ON public.product_reviews;
CREATE POLICY "reviews_insert_buyer" ON public.product_reviews
    FOR INSERT TO authenticated
    WITH CHECK (auth.uid() = buyer_id);

-- Buyers can update their own reviews, or sellers can update seller_reply
DROP POLICY IF EXISTS "reviews_update_party" ON public.product_reviews;
CREATE POLICY "reviews_update_party" ON public.product_reviews
    FOR UPDATE TO authenticated
    USING (auth.uid() = buyer_id OR auth.uid() = seller_id);

-- Buyers can delete their own reviews
DROP POLICY IF EXISTS "reviews_delete_buyer" ON public.product_reviews;
CREATE POLICY "reviews_delete_buyer" ON public.product_reviews
    FOR DELETE TO authenticated
    USING (auth.uid() = buyer_id);

-- 4. Grant Permissions
GRANT ALL ON public.product_reviews TO authenticated;
GRANT SELECT ON public.product_reviews TO anon;
