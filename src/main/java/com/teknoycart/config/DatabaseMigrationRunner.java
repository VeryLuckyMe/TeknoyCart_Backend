package com.teknoycart.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

@Component
public class DatabaseMigrationRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMigrationRunner.class);

    private static final String RLS_MIGRATION_VERSION = "V2__comprehensive_rls_lockdown";
    private static final String LIFECYCLE_MIGRATION_VERSION = "V3__order_lifecycle_and_verification";
    private static final String ACCEPT_ORDER_FIX_VERSION = "V4__accept_order_fix_and_paymongo";
    private static final String STATUS_RESILIENCE_VERSION = "V5__order_status_and_audit_resilience";
    private static final String NOTIFICATIONS_MIGRATION_VERSION = "V7__notifications_admin_fanout_and_verify_procedure";
    private static final String P6_HARDENING_VERSION = "V8__admin_backdoor_hardening";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS public.schema_migrations (" +
                "    version VARCHAR(255) PRIMARY KEY," +
                "    applied_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP" +
                ");" +
                "ALTER TABLE public.schema_migrations ENABLE ROW LEVEL SECURITY;" +
                "REVOKE ALL ON public.schema_migrations FROM anon, authenticated;"
            );
        } catch (Exception e) {
            logger.warn("Could not create schema_migrations table: {}", e.getMessage());
        }

        // Direct DDL safety fixes executed on every boot:
        try {
            // 1. Ensure status column on orders is VARCHAR, not bound by legacy DEFAULT or enum
            jdbcTemplate.execute(
                "DO $$ " +
                "BEGIN " +
                "    ALTER TABLE public.orders ALTER COLUMN status DROP DEFAULT; " +
                "    ALTER TABLE public.orders ALTER COLUMN status TYPE VARCHAR(50) USING status::TEXT; " +
                "    ALTER TABLE public.orders ALTER COLUMN status SET DEFAULT 'PLACED'; " +
                "EXCEPTION WHEN OTHERS THEN " +
                "    NULL; " +
                "END $$;"
            );
            // 2. Drop any legacy check constraints on status
            jdbcTemplate.execute(
                "DO $$ " +
                "DECLARE r RECORD; " +
                "BEGIN " +
                "    FOR r IN ( " +
                "        SELECT conname FROM pg_constraint " +
                "        WHERE conrelid = 'public.orders'::regclass AND contype = 'c' " +
                "          AND pg_get_constraintdef(oid) LIKE '%status%' " +
                "    ) LOOP " +
                "        EXECUTE 'ALTER TABLE public.orders DROP CONSTRAINT IF EXISTS ' || quote_ident(r.conname); " +
                "    END LOOP; " +
                "END $$;"
            );
            // 3. Drop foreign key constraint on actor_id in order_audit_logs and widen method to TEXT
            jdbcTemplate.execute("ALTER TABLE public.order_audit_logs DROP CONSTRAINT IF EXISTS order_audit_logs_actor_id_fkey;");
            jdbcTemplate.execute("ALTER TABLE public.order_audit_logs ALTER COLUMN method TYPE TEXT;");
            jdbcTemplate.execute("ALTER TABLE public.order_audit_logs ALTER COLUMN previous_status TYPE VARCHAR(100);");
            jdbcTemplate.execute("ALTER TABLE public.order_audit_logs ALTER COLUMN new_status TYPE VARCHAR(100);");
            // 4. Ensure PayMongo GCash checkout columns exist
            jdbcTemplate.execute(
                "ALTER TABLE public.orders " +
                "ADD COLUMN IF NOT EXISTS payment_method TEXT, " +
                "ADD COLUMN IF NOT EXISTS paymongo_checkout_session_id TEXT, " +
                "ADD COLUMN IF NOT EXISTS paymongo_checkout_url TEXT, " +
                "ADD COLUMN IF NOT EXISTS payment_expires_at TIMESTAMPTZ, " +
                "ADD COLUMN IF NOT EXISTS amount_centavos BIGINT, " +
                "ADD COLUMN IF NOT EXISTS payout_released BOOLEAN NOT NULL DEFAULT false, " +
                "ADD COLUMN IF NOT EXISTS pickup_location VARCHAR(255), " +
                "ADD COLUMN IF NOT EXISTS pickup_day VARCHAR(50), " +
                "ADD COLUMN IF NOT EXISTS pickup_time VARCHAR(50);"
            );
            // 5. Ensure version column has DEFAULT 0
            jdbcTemplate.execute(
                "DO $$ " +
                "BEGIN " +
                "    IF EXISTS ( " +
                "        SELECT 1 FROM information_schema.columns " +
                "        WHERE table_schema = 'public' AND table_name = 'orders' AND column_name = 'version' " +
                "    ) THEN " +
                "        ALTER TABLE public.orders ALTER COLUMN version SET DEFAULT 0; " +
                "        UPDATE public.orders SET version = 0 WHERE version IS NULL; " +
                "    ELSE " +
                "        ALTER TABLE public.orders ADD COLUMN version BIGINT DEFAULT 0; " +
                "    END IF; " +
                "END $$;"
            );
        } catch (Exception e) {
            logger.warn("Pre-boot database schema resilience fix: {}", e.getMessage());
        }

        applyRlsMigration();
        applyLifecycleMigration();
        applyAcceptOrderFixMigration();
        applyStatusResilienceMigration();
        applyNotificationsMigration();
        applyP6HardeningMigration();
    }

    private void applyRlsMigration() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.schema_migrations WHERE version = ?",
                Integer.class,
                RLS_MIGRATION_VERSION
            );

            if (count != null && count > 0) {
                logger.info("Comprehensive RLS policies [{}] are already applied. Skipping DDL on boot.", RLS_MIGRATION_VERSION);
                return;
            }

            logger.info("Applying Comprehensive Row Level Security (RLS) policies [{}]...", RLS_MIGRATION_VERSION);
            ClassPathResource resource = new ClassPathResource("db/comprehensive_rls_policies.sql");
            if (resource.exists()) {
                String rlsSql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                jdbcTemplate.execute(rlsSql);
                jdbcTemplate.update(
                    "INSERT INTO public.schema_migrations (version) VALUES (?) ON CONFLICT (version) DO NOTHING;",
                    RLS_MIGRATION_VERSION
                );
                logger.info("Successfully applied Comprehensive Row Level Security (RLS) policies to all tables.");
            } else {
                logger.warn("RLS migration script not found in classpath: db/comprehensive_rls_policies.sql");
            }
        } catch (Exception e) {
            logger.error("Failed to apply Comprehensive RLS policies: {}", e.getMessage(), e);
        }
    }

    private void applyLifecycleMigration() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.schema_migrations WHERE version = ?",
                Integer.class,
                LIFECYCLE_MIGRATION_VERSION
            );

            if (count != null && count > 0) {
                logger.info("Order lifecycle migration [{}] is already applied. Skipping DDL on boot.", LIFECYCLE_MIGRATION_VERSION);
                return;
            }

            logger.info("Applying Order Lifecycle & Verification migration [{}]...", LIFECYCLE_MIGRATION_VERSION);
            ClassPathResource resource = new ClassPathResource("db/migration_order_lifecycle.sql");
            if (resource.exists()) {
                String lifecycleSql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                jdbcTemplate.execute(lifecycleSql);
                jdbcTemplate.update(
                    "INSERT INTO public.schema_migrations (version) VALUES (?) ON CONFLICT (version) DO NOTHING;",
                    LIFECYCLE_MIGRATION_VERSION
                );
                logger.info("Successfully applied Order Lifecycle & Verification migration to database.");
            } else {
                logger.warn("Lifecycle migration script not found in classpath: db/migration_order_lifecycle.sql");
            }
        } catch (Exception e) {
            logger.error("Failed to apply Order Lifecycle migration: {}", e.getMessage(), e);
        }
    }

    private void applyAcceptOrderFixMigration() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.schema_migrations WHERE version = ?",
                Integer.class,
                ACCEPT_ORDER_FIX_VERSION
            );

            if (count != null && count > 0) {
                logger.info("Accept order fix migration [{}] is already applied. Skipping DDL on boot.", ACCEPT_ORDER_FIX_VERSION);
                return;
            }

            logger.info("Applying Accept Order Fix & PayMongo migration [{}]...", ACCEPT_ORDER_FIX_VERSION);
            ClassPathResource resource = new ClassPathResource("db/migration_accept_order_fix.sql");
            if (resource.exists()) {
                String acceptFixSql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                jdbcTemplate.execute(acceptFixSql);
                jdbcTemplate.update(
                    "INSERT INTO public.schema_migrations (version) VALUES (?) ON CONFLICT (version) DO NOTHING;",
                    ACCEPT_ORDER_FIX_VERSION
                );
                logger.info("Successfully applied Accept Order Fix & PayMongo migration to database.");
            } else {
                logger.warn("Accept order fix migration script not found in classpath: db/migration_accept_order_fix.sql");
            }
        } catch (Exception e) {
            logger.error("Failed to apply Accept Order Fix migration: {}", e.getMessage(), e);
        }
    }

    private void applyStatusResilienceMigration() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.schema_migrations WHERE version = ?",
                Integer.class,
                STATUS_RESILIENCE_VERSION
            );

            if (count != null && count > 0) {
                logger.info("Order status resilience migration [{}] is already applied. Skipping DDL on boot.", STATUS_RESILIENCE_VERSION);
                return;
            }

            logger.info("Applying Order Status & Audit Resilience migration [{}]...", STATUS_RESILIENCE_VERSION);
            ClassPathResource resource = new ClassPathResource("db/migration_order_status_resilience.sql");
            if (resource.exists()) {
                String resilienceSql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                jdbcTemplate.execute(resilienceSql);
                jdbcTemplate.update(
                    "INSERT INTO public.schema_migrations (version) VALUES (?) ON CONFLICT (version) DO NOTHING;",
                    STATUS_RESILIENCE_VERSION
                );
                logger.info("Successfully applied Order Status & Audit Resilience migration to database.");
            } else {
                logger.warn("Resilience migration script not found in classpath: db/migration_order_status_resilience.sql");
            }
        } catch (Exception e) {
            logger.error("Failed to apply Order Status Resilience migration: {}", e.getMessage(), e);
        }
    }

    private void applyNotificationsMigration() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.schema_migrations WHERE version = ?",
                Integer.class,
                NOTIFICATIONS_MIGRATION_VERSION
            );

            if (count != null && count > 0) {
                logger.info("Notifications & KYC admin notification migration [{}] is already applied. Skipping DDL on boot.", NOTIFICATIONS_MIGRATION_VERSION);
                return;
            }

            logger.info("Applying In-App Notification Center & KYC Admin Fan-out migration [{}]...", NOTIFICATIONS_MIGRATION_VERSION);
            ClassPathResource resource = new ClassPathResource("db/migration_notifications.sql");
            if (resource.exists()) {
                String notificationsSql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                jdbcTemplate.execute(notificationsSql);
                jdbcTemplate.update(
                    "INSERT INTO public.schema_migrations (version) VALUES (?) ON CONFLICT (version) DO NOTHING;",
                    NOTIFICATIONS_MIGRATION_VERSION
                );
                logger.info("Successfully applied In-App Notification Center & KYC Admin Fan-out migration to database.");
            } else {
                logger.warn("Notifications migration script not found in classpath: db/migration_notifications.sql");
            }
        } catch (Exception e) {
            logger.error("Failed to apply In-App Notification Center & KYC Admin Fan-out migration: {}", e.getMessage(), e);
        }
    }

    private void applyP6HardeningMigration() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.schema_migrations WHERE version = ?",
                Integer.class,
                P6_HARDENING_VERSION
            );

            if (count != null && count > 0) {
                logger.info("P6 admin backdoor hardening migration [{}] is already applied. Skipping DDL on boot.", P6_HARDENING_VERSION);
                return;
            }

            logger.info("Applying P6 admin backdoor & KYC self-approval hardening migration [{}]...", P6_HARDENING_VERSION);
            ClassPathResource resource = new ClassPathResource("db/migration_p6_admin_backdoor_hardening.sql");
            if (resource.exists()) {
                String p6Sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                jdbcTemplate.execute(p6Sql);
                jdbcTemplate.update(
                    "INSERT INTO public.schema_migrations (version) VALUES (?) ON CONFLICT (version) DO NOTHING;",
                    P6_HARDENING_VERSION
                );
                logger.info("Successfully applied P6 admin backdoor & KYC self-approval hardening migration to database.");
            } else {
                logger.warn("P6 hardening migration script not found in classpath: db/migration_p6_admin_backdoor_hardening.sql");
            }
        } catch (Exception e) {
            logger.error("Failed to apply P6 admin backdoor hardening migration: {}", e.getMessage(), e);
        }
    }
}


