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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS public.schema_migrations (" +
                "    version VARCHAR(255) PRIMARY KEY," +
                "    applied_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP" +
                ");"
            );
        } catch (Exception e) {
            logger.warn("Could not create schema_migrations table: {}", e.getMessage());
        }

        try {
            jdbcTemplate.execute("ALTER TABLE orders ALTER COLUMN status TYPE VARCHAR USING status::TEXT;");
        } catch (Exception e) {
            // No-op if already converted
        }

        try {
            // Check if RLS migration has already been executed to prevent policy drop/re-creation on every boot
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
}

