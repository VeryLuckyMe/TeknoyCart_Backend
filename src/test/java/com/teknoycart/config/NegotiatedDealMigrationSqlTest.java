package com.teknoycart.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the security invariants of the V20 negotiated-price migration.
 *
 * <p>The SQL runs against PostgreSQL/Supabase, while the unit suite runs on H2
 * and cannot execute auth.uid(). These assertions make sure the migration keeps
 * the catalog-pricing trigger as the only INSERT-time price authority and never
 * lets a client invent a Tawad price.</p>
 */
class NegotiatedDealMigrationSqlTest {

    private String sql() throws Exception {
        ClassPathResource resource =
                new ClassPathResource("db/migration_v20_negotiated_deals.sql");
        assertTrue(resource.exists(),
                "V20 negotiated-deal migration must ship in the backend jar");
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }

    @Test
    void migrationStoresAcceptedDealsWithParticipantOnlyReads() throws Exception {
        String sql = sql();

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS public.negotiated_deals"));
        assertTrue(sql.contains("agreed_price NUMERIC(10, 2) NOT NULL CHECK (agreed_price > 0)"));
        assertTrue(sql.contains("status IN ('ACTIVE', 'CONSUMED', 'CANCELLED')"));
        assertTrue(sql.contains("ENABLE ROW LEVEL SECURITY"));
        assertTrue(sql.contains("REVOKE ALL ON TABLE public.negotiated_deals FROM PUBLIC, anon, authenticated"));
        assertTrue(sql.contains("GRANT SELECT ON TABLE public.negotiated_deals TO authenticated"));
        assertTrue(sql.contains("negotiated_deals_select_participants"));
        assertTrue(sql.contains("auth.uid() = buyer_id OR auth.uid() = seller_id"));
    }

    @Test
    void acceptanceRpcRequiresTheVerifiedSellerInThatChat() throws Exception {
        String sql = sql();

        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION public.accept_negotiated_deal("));
        assertTrue(sql.contains("SECURITY DEFINER"));
        assertTrue(sql.contains("c.chat_id = p_chat_id"));
        assertTrue(sql.contains("c.seller_id = v_seller"));
        assertTrue(sql.contains("p.seller_id = v_seller"));
        assertTrue(sql.contains("u.role = 'SELLER'"));
        assertTrue(sql.contains("u.is_seller_verified IS TRUE"));
        assertTrue(sql.contains("p_agreed_price > v_list_price"));
        assertTrue(sql.contains("REVOKE ALL ON FUNCTION public.accept_negotiated_deal(UUID, UUID, NUMERIC)"));
    }

    @Test
    void checkoutOverloadRequiresAnActiveDealAndKeepsInsertPricingLocked() throws Exception {
        String sql = sql();

        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION public.place_orders_atomic("));
        assertTrue(sql.contains("p_chat_id UUID"));
        assertTrue(sql.contains("c.chat_id = p_chat_id"));
        assertTrue(sql.contains("c.buyer_id = v_buyer"));
        assertTrue(sql.contains("d.status = 'ACTIVE'"));
        assertTrue(sql.contains("d.seller_id = v_seller"));
        assertTrue(sql.contains("v_claimed = v_deal_price"));
        assertTrue(sql.contains("v_line_deals"));
        assertTrue(sql.contains("DEAL_NOT_FOUND"));
        assertTrue(sql.contains("UPDATE public.orders"));
        assertTrue(sql.contains("unit_price = v_deal_price"));
        assertTrue(sql.contains("SET status = 'CONSUMED'"));
        assertTrue(sql.contains("REVOKE ALL ON FUNCTION public.place_orders_atomic(JSONB, TEXT, UUID, UUID)"));

        // The existing trigger must remain the only INSERT-time price authority:
        // this migration may not drop or redefine it.
        assertFalse(sql.contains("DROP TRIGGER IF EXISTS trg_enforce_order_insert_integrity"));
        assertFalse(sql.contains("CREATE OR REPLACE FUNCTION public.enforce_order_insert_integrity"));
    }

    @Test
    void backfillOnlyRestoresAlreadyAcceptedChatOffers() throws Exception {
        String sql = sql();

        assertTrue(sql.contains("Offer Accepted!"));
        assertTrue(sql.contains("m.sender_id = c.buyer_id"));
        assertTrue(sql.contains("a.sender_id = c2.seller_id"));
        assertTrue(sql.contains("a.price <= p.base_price"));
        assertTrue(sql.contains("h.content LIKE '🤝 Handshake Deal Confirmed!%'"));
        assertTrue(sql.contains("ON CONFLICT DO NOTHING"));
    }
}
