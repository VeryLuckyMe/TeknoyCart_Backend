package com.teknoycart.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the security invariants of the V12 seller-cancel RPC. These are SQL
 * assertions because the migration runs against PostgreSQL/Supabase, while the
 * unit suite runs on H2 and cannot execute auth.uid().
 */
class SellerCancelMigrationSqlTest {

    private String sql() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration_v12_seller_cancel.sql");
        assertTrue(resource.exists(), "V12 seller cancellation migration must ship in the backend jar");
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }

    @Test
    void migrationRequiresAuthenticationAndSellerOwnership() throws Exception {
        String sql = sql();

        assertTrue(sql.contains("v_caller_id IS NULL"),
                "A NULL auth.uid() must be rejected, never treated as a service bypass");
        assertFalse(sql.contains("v_caller_id IS NOT NULL"),
                "The legacy NULL-uid bypass must not survive");
        assertTrue(sql.contains("Only the seller can cancel this order"));
        assertTrue(sql.contains("seller_id IS DISTINCT FROM v_caller_id"));
        assertTrue(sql.contains("FOR UPDATE"), "Concurrent seller actions must serialize on the order row");
    }

    @Test
    void migrationRoutesEscrowedMoneyToRefundStates() throws Exception {
        String sql = sql();

        assertTrue(sql.contains("'REFUND_PENDING'"),
                "ESCROWED orders must route to REFUND_PENDING, not CANCELLED");
        assertTrue(sql.contains("'REFUND_REQUESTED'"),
                "PAYMENT_SUBMITTED orders must route to REFUND_REQUESTED");
        assertTrue(sql.contains("Payment has been verified for this order"),
                "PAYMENT_VERIFIED must be blocked from unilateral cancellation");
    }

    @Test
    void migrationLocksDownExecutionPrivileges() throws Exception {
        String sql = sql();

        assertTrue(sql.contains(
                "REVOKE ALL ON FUNCTION public.cancel_order_as_seller(UUID, TEXT) FROM PUBLIC"));
        assertTrue(sql.contains(
                "GRANT EXECUTE ON FUNCTION public.cancel_order_as_seller(UUID, TEXT) TO authenticated"));
        assertTrue(sql.contains(
                "GRANT EXECUTE ON FUNCTION public.cancel_order_as_seller(UUID, TEXT) TO service_role"));
        assertTrue(sql.contains("SET search_path = public, pg_temp"));
    }

    @Test
    void migrationReleasesReservedInventoryOnCancellation() throws Exception {
        String sql = sql();

        assertTrue(sql.contains("CREATE TRIGGER trg_sync_order_status_to_inventory"));
        assertTrue(sql.contains("reserved_qty = GREATEST(0, reserved_qty - COALESCE(NEW.quantity, 1))"));
    }
}
