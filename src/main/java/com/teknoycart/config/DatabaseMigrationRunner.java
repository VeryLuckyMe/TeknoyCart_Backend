package com.teknoycart.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseMigrationRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMigrationRunner.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            logger.info("Executing database migration to ensure orders.status is VARCHAR...");
            jdbcTemplate.execute("ALTER TABLE orders ALTER COLUMN status TYPE VARCHAR USING status::TEXT;");
            logger.info("Successfully converted orders.status column to VARCHAR.");
        } catch (Exception e) {
            logger.warn("Database migration note: {}", e.getMessage());
        }
    }
}
