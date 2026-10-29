package com.teknoycart.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewNotificationMigrationSqlTest {

    @Test
    void migrationAddsPrivacySafeInAppSellerReviewTrigger() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration_review_notifications.sql");
        assertTrue(resource.exists(), "Review notification migration must ship in the backend jar");

        String sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);

        assertTrue(sql.contains("DROP CONSTRAINT IF EXISTS chk_notifications_type"));
        assertTrue(sql.contains("'REVIEW'"));
        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION public.fn_notify_seller_on_review"));
        assertTrue(sql.contains("SECURITY DEFINER"));
        assertTrue(sql.contains("AFTER INSERT ON public.product_reviews"));
        assertTrue(sql.contains("DROP TRIGGER IF EXISTS trg_notify_seller_on_review"));
        assertTrue(sql.contains("ARRAY['IN_APP']"));
        assertTrue(sql.contains("'NOT_APPLICABLE'"));
        assertTrue(sql.contains("ON CONFLICT (user_id, dedupe_key) DO NOTHING"));
        assertTrue(sql.contains("NEW.seller_id = NEW.buyer_id"));
        assertTrue(sql.contains("EXCEPTION WHEN OTHERS"));
        assertTrue(sql.contains("RAISE WARNING"));

        assertFalse(sql.contains("NEW.buyer_name"), "Notification must not expose the buyer name");
        assertFalse(sql.contains("NEW.buyer_id::TEXT"), "Buyer id is only allowed in the self-review guard");
        assertFalse(sql.contains("'PUSH'"), "Review notifications must stay in-app only");
        assertFalse(sql.contains("'EMAIL'"), "Review notifications must stay in-app only");
    }
}
