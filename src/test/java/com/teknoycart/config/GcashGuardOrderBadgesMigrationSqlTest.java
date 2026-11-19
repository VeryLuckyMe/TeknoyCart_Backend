package com.teknoycart.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the invariants of the V21 migration. The SQL targets Supabase
 * (auth.uid()), which the H2 unit suite cannot execute, so the shipped script
 * is checked textually.
 */
class GcashGuardOrderBadgesMigrationSqlTest {

    private String sql() throws Exception {
        ClassPathResource resource =
                new ClassPathResource("db/migration_v21_gcash_guard_and_order_badges.sql");
        assertTrue(resource.exists(), "V21 migration must ship in the backend jar");
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }

    @Test
    void gcashCheckAnswersYesNoWithoutExposingTheNumber() throws Exception {
        String sql = sql();
        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION public.seller_accepts_gcash(p_seller_id UUID)"));
        assertTrue(sql.contains("RETURNS BOOLEAN"));
        assertTrue(sql.contains("REVOKE ALL ON FUNCTION public.seller_accepts_gcash(UUID) FROM PUBLIC, anon"));
        assertFalse(sql.contains("RETURNS TEXT"), "must never return the payout number itself");
    }

    @Test
    void everyOrderInsertIsGuardedNotJustOneCheckoutOverload() throws Exception {
        String sql = sql();
        assertTrue(sql.contains("BEFORE INSERT ON public.orders"));
        assertTrue(sql.contains("NEW.payment_method::TEXT = 'GCASH'"));
        assertTrue(sql.contains("RAISE EXCEPTION 'SELLER_GCASH_NOT_SET'"));
    }

    @Test
    void badgeStateIsOnlyReachableThroughOwnerScopedFunctions() throws Exception {
        String sql = sql();
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS public.order_list_seen"));
        assertTrue(sql.contains("ALTER TABLE public.order_list_seen ENABLE ROW LEVEL SECURITY"));
        assertTrue(sql.contains("REVOKE ALL ON TABLE public.order_list_seen FROM PUBLIC, anon, authenticated"));
        assertTrue(sql.contains("v_uid       UUID := auth.uid()"));
        assertTrue(sql.contains("WHERE n.user_id = v_uid"));
        assertTrue(sql.contains("p_list NOT IN ('INCOMING', 'PURCHASES')"));
    }

    @Test
    void openingAListClearsOnlyThatSidesOrderAlerts() throws Exception {
        String sql = sql();
        assertTrue(sql.contains("(p_list = 'INCOMING'  AND o.seller_id = v_uid)"));
        assertTrue(sql.contains("(p_list = 'PURCHASES' AND o.buyer_id  = v_uid)"));
        assertTrue(sql.contains("n.reference_type = 'ORDER'"));
    }
}
