package com.teknoycart.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class EmailServiceTest {

    @Test
    @DisplayName("sendVerificationEmailSync returns false and skips dispatch when API key is null")
    void testNullApiKeyReturnsFalse() {
        EmailService service = new EmailService();
        service.setBrevoApiKey(null);

        boolean result = service.sendVerificationEmailSync("student@cit.edu", "Test Student", "token-123");
        assertFalse(result, "Expected false return indicator when API key is null");
    }

    @Test
    @DisplayName("sendVerificationEmailSync returns false and skips dispatch when API key is blank or placeholder")
    void testBlankApiKeyReturnsFalse() {
        EmailService service = new EmailService();
        service.setBrevoApiKey("   ");

        boolean result = service.sendVerificationEmailSync("student@cit.edu", "Test Student", "token-123");
        assertFalse(result, "Expected false return indicator when API key is blank");

        service.setBrevoApiKey("PLACEHOLDER");
        result = service.sendVerificationEmailSync("student@cit.edu", "Test Student", "token-123");
        assertFalse(result, "Expected false return indicator when API key is PLACEHOLDER");
    }

    @Test
    @DisplayName("sendVerificationEmail returns completed future with false when API key is missing")
    void testAsyncReturnsCompletedFutureFalse() throws Exception {
        EmailService service = new EmailService();
        service.setBrevoApiKey("");

        CompletableFuture<Boolean> future = service.sendVerificationEmail("student@cit.edu", "Test Student", "token-123");
        assertNotNull(future);
        Boolean result = future.get();
        assertFalse(result, "Expected async call to resolve with false indicator when unconfigured");
    }
}
