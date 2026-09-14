-- ==============================================================================
-- TeknoyCart: Comprehensive Row Level Security (RLS) Lockdown
-- Remediates: [CRIT-02] Database Row Level Security Disabled
-- ==============================================================================

-- 1. Enable RLS on all tables
ALTER TABLE IF EXISTS public.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS public.stores ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS public.store_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS public.categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS public.products ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS public.product_images ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS public.product_variants ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS public.inventory ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS public.inquiries ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS public.chats ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS public.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS public.orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS public.payment_proofs ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS public.order_returns ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS public.order_audit_logs ENABLE ROW LEVEL SECURITY;

-- ------------------------------------------------------------------------------
-- 2. Clean up existing policies for idempotency
-- ------------------------------------------------------------------------------
DO $$ 
DECLARE
    pol RECORD;
BEGIN
    FOR pol IN 
        SELECT schemaname, tablename, policyname 
        FROM pg_policies 
        WHERE schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON %I.%I', pol.policyname, pol.schemaname, pol.tablename);
    END LOOP;
END $$;

-- ------------------------------------------------------------------------------
-- 3. USERS TABLE POLICIES & SECURITY TRIGGER
-- ------------------------------------------------------------------------------
-- Authenticated users can view profile cards (name, email, role, store)
CREATE POLICY "users_select_authenticated"
    ON public.users FOR SELECT
    TO authenticated
    USING (true);

-- Users can insert their own profile on registration
CREATE POLICY "users_insert_own_profile"
    ON public.users FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = user_id);

-- Users can update only their own profile
CREATE POLICY "users_update_own_profile"
    ON public.users FOR UPDATE
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- Privilege escalation prevention trigger: block direct modification of security columns
CREATE OR REPLACE FUNCTION public.protect_user_security_columns()
RETURNS TRIGGER AS $$
DECLARE
    jwt_role text := current_setting('request.jwt.claim.role', true);
BEGIN
    -- Block authenticated and anonymous client-side tampering with roles, verification, and lockouts
    IF jwt_role = 'authenticated' OR jwt_role = 'anon' THEN
        IF NEW.role IS DISTINCT FROM OLD.role THEN
            RAISE EXCEPTION 'Modifying user role directly is forbidden.';
        END IF;
        IF NEW.is_verified IS DISTINCT FROM OLD.is_verified THEN
            RAISE EXCEPTION 'Modifying is_verified directly is forbidden.';
        END IF;
        IF NEW.is_locked IS DISTINCT FROM OLD.is_locked OR NEW.lock_until IS DISTINCT FROM OLD.lock_until THEN
            RAISE EXCEPTION 'Modifying account lockout parameters directly is forbidden.';
        END IF;
        IF NEW.failed_attempts IS DISTINCT FROM OLD.failed_attempts THEN
            RAISE EXCEPTION 'Modifying failed_attempts directly is forbidden.';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_protect_user_security_columns ON public.users;
CREATE TRIGGER trg_protect_user_security_columns
    BEFORE UPDATE ON public.users
    FOR EACH ROW
    EXECUTE FUNCTION public.protect_user_security_columns();

-- ------------------------------------------------------------------------------
-- 4. STORES & STORE PROFILES POLICIES
-- ------------------------------------------------------------------------------
CREATE POLICY "stores_select_public"
    ON public.stores FOR SELECT
    TO public
    USING (true);

CREATE POLICY "stores_insert_owner"
    ON public.stores FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = owner_id);

CREATE POLICY "stores_update_owner"
    ON public.stores FOR UPDATE
    TO authenticated
    USING (auth.uid() = owner_id)
    WITH CHECK (auth.uid() = owner_id);

CREATE POLICY "store_profiles_select_public"
    ON public.store_profiles FOR SELECT
    TO public
    USING (true);

CREATE POLICY "store_profiles_insert_owner"
    ON public.store_profiles FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = seller_id);

CREATE POLICY "store_profiles_update_owner"
    ON public.store_profiles FOR UPDATE
    TO authenticated
    USING (auth.uid() = seller_id)
    WITH CHECK (auth.uid() = seller_id);

-- ------------------------------------------------------------------------------
-- 5. CATEGORIES POLICIES (Read-only for regular users)
-- ------------------------------------------------------------------------------
CREATE POLICY "categories_select_public"
    ON public.categories FOR SELECT
    TO public
    USING (true);

-- ------------------------------------------------------------------------------
-- 6. PRODUCTS & PRODUCT DETAILS (IMAGES, VARIANTS, INVENTORY)
-- ------------------------------------------------------------------------------
-- Products
CREATE POLICY "products_select_public"
    ON public.products FOR SELECT
    TO public
    USING (true);

CREATE POLICY "products_insert_seller"
    ON public.products FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = seller_id);

CREATE POLICY "products_update_seller"
    ON public.products FOR UPDATE
    TO authenticated
    USING (auth.uid() = seller_id)
    WITH CHECK (auth.uid() = seller_id);

CREATE POLICY "products_delete_seller"
    ON public.products FOR DELETE
    TO authenticated
    USING (auth.uid() = seller_id);

-- Product Images
CREATE POLICY "product_images_select_public"
    ON public.product_images FOR SELECT
    TO public
    USING (true);

CREATE POLICY "product_images_insert_seller"
    ON public.product_images FOR INSERT
    TO authenticated
    WITH CHECK (EXISTS (
        SELECT 1 FROM public.products p
        WHERE p.product_id = product_images.product_id AND p.seller_id = auth.uid()
    ));

CREATE POLICY "product_images_update_seller"
    ON public.product_images FOR UPDATE
    TO authenticated
    USING (EXISTS (
        SELECT 1 FROM public.products p
        WHERE p.product_id = product_images.product_id AND p.seller_id = auth.uid()
    ));

CREATE POLICY "product_images_delete_seller"
    ON public.product_images FOR DELETE
    TO authenticated
    USING (EXISTS (
        SELECT 1 FROM public.products p
        WHERE p.product_id = product_images.product_id AND p.seller_id = auth.uid()
    ));

-- Product Variants
CREATE POLICY "product_variants_select_public"
    ON public.product_variants FOR SELECT
    TO public
    USING (true);

CREATE POLICY "product_variants_insert_seller"
    ON public.product_variants FOR INSERT
    TO authenticated
    WITH CHECK (EXISTS (
        SELECT 1 FROM public.products p
        WHERE p.product_id = product_variants.product_id AND p.seller_id = auth.uid()
    ));

CREATE POLICY "product_variants_update_seller"
    ON public.product_variants FOR UPDATE
    TO authenticated
    USING (EXISTS (
        SELECT 1 FROM public.products p
        WHERE p.product_id = product_variants.product_id AND p.seller_id = auth.uid()
    ));

CREATE POLICY "product_variants_delete_seller"
    ON public.product_variants FOR DELETE
    TO authenticated
    USING (EXISTS (
        SELECT 1 FROM public.products p
        WHERE p.product_id = product_variants.product_id AND p.seller_id = auth.uid()
    ));

-- Inventory
CREATE POLICY "inventory_select_public"
    ON public.inventory FOR SELECT
    TO public
    USING (true);

CREATE POLICY "inventory_insert_seller"
    ON public.inventory FOR INSERT
    TO authenticated
    WITH CHECK (EXISTS (
        SELECT 1 FROM public.product_variants pv
        JOIN public.products p ON pv.product_id = p.product_id
        WHERE pv.variant_id = inventory.variant_id AND p.seller_id = auth.uid()
    ));

CREATE POLICY "inventory_update_seller"
    ON public.inventory FOR UPDATE
    TO authenticated
    USING (EXISTS (
        SELECT 1 FROM public.product_variants pv
        JOIN public.products p ON pv.product_id = p.product_id
        WHERE pv.variant_id = inventory.variant_id AND p.seller_id = auth.uid()
    ));

-- ------------------------------------------------------------------------------
-- 7. INQUIRIES POLICIES
-- ------------------------------------------------------------------------------
CREATE POLICY "inquiries_select_participants"
    ON public.inquiries FOR SELECT
    TO authenticated
    USING (
        auth.uid() = buyer_id OR EXISTS (
            SELECT 1 FROM public.products p
            WHERE p.product_id = inquiries.product_id AND p.seller_id = auth.uid()
        )
    );

CREATE POLICY "inquiries_insert_buyer"
    ON public.inquiries FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = buyer_id);

CREATE POLICY "inquiries_update_participants"
    ON public.inquiries FOR UPDATE
    TO authenticated
    USING (
        auth.uid() = buyer_id OR EXISTS (
            SELECT 1 FROM public.products p
            WHERE p.product_id = inquiries.product_id AND p.seller_id = auth.uid()
        )
    );

-- ------------------------------------------------------------------------------
-- 8. CHATS & MESSAGES POLICIES
-- ------------------------------------------------------------------------------
-- Chats: Only buyer or seller in room can view or create
CREATE POLICY "chats_select_participants"
    ON public.chats FOR SELECT
    TO authenticated
    USING (auth.uid() = buyer_id OR auth.uid() = seller_id);

CREATE POLICY "chats_insert_participants"
    ON public.chats FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = buyer_id OR auth.uid() = seller_id);

CREATE POLICY "chats_update_participants"
    ON public.chats FOR UPDATE
    TO authenticated
    USING (auth.uid() = buyer_id OR auth.uid() = seller_id);

-- Messages: Only participants in the linked chat can view/insert/update
CREATE POLICY "messages_select_participants"
    ON public.messages FOR SELECT
    TO authenticated
    USING (EXISTS (
        SELECT 1 FROM public.chats c
        WHERE c.chat_id = messages.chat_id
        AND (c.buyer_id = auth.uid() OR c.seller_id = auth.uid())
    ));

CREATE POLICY "messages_insert_sender"
    ON public.messages FOR INSERT
    TO authenticated
    WITH CHECK (
        auth.uid() = sender_id AND EXISTS (
            SELECT 1 FROM public.chats c
            WHERE c.chat_id = messages.chat_id
            AND (c.buyer_id = auth.uid() OR c.seller_id = auth.uid())
        )
    );

CREATE POLICY "messages_update_participants"
    ON public.messages FOR UPDATE
    TO authenticated
    USING (EXISTS (
        SELECT 1 FROM public.chats c
        WHERE c.chat_id = messages.chat_id
        AND (c.buyer_id = auth.uid() OR c.seller_id = auth.uid())
    ));

-- ------------------------------------------------------------------------------
-- 9. ORDERS & ORDER RETURNS POLICIES
-- ------------------------------------------------------------------------------
-- Buyers & Sellers can inspect only their orders
CREATE POLICY "orders_select_parties"
    ON public.orders FOR SELECT
    TO authenticated
    USING (auth.uid() = buyer_id OR auth.uid() = seller_id);

-- Buyers can place orders with unforgeable initial fields
CREATE POLICY "orders_insert_buyer"
    ON public.orders FOR INSERT
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

-- Order Returns
CREATE POLICY "order_returns_select_parties"
    ON public.order_returns FOR SELECT
    TO authenticated
    USING (
        auth.uid() = requested_by OR EXISTS (
            SELECT 1 FROM public.orders o
            WHERE o.order_id = order_returns.order_id
            AND (o.buyer_id = auth.uid() OR o.seller_id = auth.uid())
        )
    );

CREATE POLICY "order_returns_insert_buyer"
    ON public.order_returns FOR INSERT
    TO authenticated
    WITH CHECK (
        auth.uid() = requested_by AND EXISTS (
            SELECT 1 FROM public.orders o
            WHERE o.order_id = order_returns.order_id
            AND o.buyer_id = auth.uid()
        )
    );

-- ------------------------------------------------------------------------------
-- 10. PAYMENT PROOFS & ORDER AUDIT LOGS
-- ------------------------------------------------------------------------------
CREATE POLICY "payment_proofs_select_parties"
    ON public.payment_proofs FOR SELECT
    TO authenticated
    USING (EXISTS (
        SELECT 1 FROM public.orders o
        WHERE o.order_id = payment_proofs.order_id
        AND (o.buyer_id = auth.uid() OR o.seller_id = auth.uid())
    ));

CREATE POLICY "payment_proofs_insert_buyer"
    ON public.payment_proofs FOR INSERT
    TO authenticated
    WITH CHECK (EXISTS (
        SELECT 1 FROM public.orders o
        WHERE o.order_id = payment_proofs.order_id
        AND o.buyer_id = auth.uid()
    ));

CREATE POLICY "order_audit_logs_select_parties"
    ON public.order_audit_logs FOR SELECT
    TO authenticated
    USING (
        auth.uid() = actor_id OR EXISTS (
            SELECT 1 FROM public.orders o
            WHERE o.order_id = order_audit_logs.order_id
            AND (o.buyer_id = auth.uid() OR o.seller_id = auth.uid())
        )
    );
