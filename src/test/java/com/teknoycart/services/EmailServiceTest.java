package com.teknoycart.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    @DisplayName("sendNotificationEmailSync returns false and skips dispatch when Brevo API key is missing or blank")
    void testNotificationEmailWithMissingApiKey() {
        EmailService service = new EmailService();
        service.setBrevoApiKey(null);

        boolean result = service.sendNotificationEmailSync("student@cit.edu", "Test Student", "Order Update", "Your order has been escrowed", "ORDER", "order-123");
        assertFalse(result, "Expected false return indicator when API key is null");

        service.setBrevoApiKey("   ");
        result = service.sendNotificationEmailSync("student@cit.edu", "Test Student", "Order Update", "Your order has been escrowed", "ORDER", "order-123");
        assertFalse(result, "Expected false return indicator when API key is blank");
    }

    @Test
    @DisplayName("sendNotificationEmail returns false gracefully when user is not found in repository")
    void testNotificationEmailUserNotFound() {
        com.teknoycart.repositories.UserRepository repo = org.mockito.Mockito.mock(com.teknoycart.repositories.UserRepository.class);
        org.mockito.Mockito.when(repo.findById(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.empty());

        EmailService service = new EmailService();
        service.setUserRepository(repo);

        boolean result = service.sendNotificationEmail(java.util.UUID.randomUUID(), "Order Completed", "Item delivered", "ORDER", "order-456");
        assertFalse(result, "Expected false when recipient user is not found in database");
    }

    @Test
    @DisplayName("sendVerificationEmailSync and sendNotificationEmailSync use teknoycart@gmail.com as default Brevo sender")
    void testDefaultSenderUsesTeknoyCartEmail() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = startCaptureServer(capturedBody);
        try {
            EmailService service = new EmailService();
            service.setBrevoApiKey("test-api-key");
            service.setBrevoApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v3/smtp/email");

            assertTrue(service.sendVerificationEmailSync("student@cit.edu", "Test Student", "token-123"));
            Map<String, Object> verificationPayload = parseJson(capturedBody.get());
            @SuppressWarnings("unchecked")
            Map<String, Object> verificationSender = (Map<String, Object>) verificationPayload.get("sender");
            assertEquals("teknoycart@gmail.com", verificationSender.get("email"));
            assertEquals("TeknoyCart CIT-U", verificationSender.get("name"));

            capturedBody.set(null);
            assertTrue(service.sendNotificationEmailSync("student@cit.edu", "Test Student", "Order Update", "Ready for meetup", "ORDER", "order-1"));
            Map<String, Object> notificationPayload = parseJson(capturedBody.get());
            @SuppressWarnings("unchecked")
            Map<String, Object> notificationSender = (Map<String, Object>) notificationPayload.get("sender");
            assertEquals("teknoycart@gmail.com", notificationSender.get("email"));
            assertEquals("TeknoyCart CIT-U", notificationSender.get("name"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("EmailService uses configured sender overrides and falls back when blank")
    void testConfiguredSenderOverridesAndBlankFallback() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = startCaptureServer(capturedBody);
        try {
            EmailService service = new EmailService();
            service.setBrevoApiKey("test-api-key");
            service.setBrevoApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v3/smtp/email");
            service.setBrevoSenderEmail("noreply@teknoycart.example");
            service.setBrevoSenderName("TeknoyCart Support");

            assertTrue(service.sendVerificationEmailSync("student@cit.edu", "Test Student", "token-123"));
            Map<String, Object> customPayload = parseJson(capturedBody.get());
            @SuppressWarnings("unchecked")
            Map<String, Object> customSender = (Map<String, Object>) customPayload.get("sender");
            assertEquals("noreply@teknoycart.example", customSender.get("email"));
            assertEquals("TeknoyCart Support", customSender.get("name"));

            service.setBrevoSenderEmail("   ");
            service.setBrevoSenderName("");
            assertTrue(service.sendVerificationEmailSync("student@cit.edu", "Test Student", "token-456"));
            Map<String, Object> fallbackPayload = parseJson(capturedBody.get());
            @SuppressWarnings("unchecked")
            Map<String, Object> fallbackSender = (Map<String, Object>) fallbackPayload.get("sender");
            assertEquals("teknoycart@gmail.com", fallbackSender.get("email"));
            assertEquals("TeknoyCart CIT-U", fallbackSender.get("name"));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startCaptureServer(AtomicReference<String> capturedBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/smtp/email", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            capturedBody.set(new String(bytes, StandardCharsets.UTF_8));
            byte[] resp = "{\"messageId\":\"<test>\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static Map<String, Object> parseJson(String json) throws Exception {
        return new ObjectMapper().readValue(json, new TypeReference<>() {});
    }
}
