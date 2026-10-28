package com.teknoycart.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class P0SecurityMigrationSqlTest {

    @Test
    void migrationKeepsOrderWritesAndOtpColumnsLocked() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration_p0_security_hardening.sql");
        assertTrue(resource.exists(), "P0 migration must ship in the backend jar");

        String sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);

        assertTrue(sql.contains("inventory_select_public"));
        assertTrue(sql.contains("REVOKE ALL PRIVILEGES ON TABLE public.orders FROM PUBLIC, anon, authenticated"));
        assertTrue(sql.contains("CREATE OR REPLACE VIEW public.my_order_codes"));
        assertTrue(sql.contains("CREATE POLICY reviews_insert_buyer"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS public.product_reviews"));
        assertTrue(sql.contains("REVOKE ALL ON FUNCTION public.delete_user_account() FROM PUBLIC, anon"));
        assertTrue(sql.contains("enforce_order_insert_integrity"));
        assertTrue(sql.contains("ALTER PUBLICATION supabase_realtime DROP TABLE public.orders"));
        assertTrue(sql.contains("P0 verification failed"));
    }
}
