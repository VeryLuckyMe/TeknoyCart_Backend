package com.teknoycart.controllers;

import com.teknoycart.models.PaymentEvent;
import com.teknoycart.repositories.PaymentEventRepository;
import com.teknoycart.security.PayMongoSignatureVerifier;
import com.teknoycart.security.PayMongoSignatureVerifier.PayMongoSignatureException;
import com.teknoycart.services.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PayMongoWebhookControllerTest {

    @Mock
    private PayMongoSignatureVerifier signatureVerifier;

    @Mock
    private PaymentEventRepository paymentEventRepository;

    @Mock
    private OrderService orderService;

    @InjectMocks
    private PayMongoWebhookController controller;

    private final String webhookSecret = "whsk_test_mock_secret";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "webhookSecret", webhookSecret);
        ReflectionTestUtils.setField(controller, "testMode", true);
    }

    private byte[] createSamplePayload(String eventId, String eventType, String orderId, String sessionId, long amount, String currency) {
        String json = """
        {
          "data": {
            "id": "%s",
            "type": "event",
            "attributes": {
              "type": "%s",
              "data": {
                "id": "%s",
                "attributes": {
                  "amount": %d,
                  "currency": "%s",
                  "metadata": {
                    "order_id": "%s"
                  }
                }
              }
            }
          }
        }
        """.formatted(eventId, eventType, sessionId, amount, currency, orderId);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Valid signature and paid checkout event moves order to ESCROWED and returns 200 OK")
    void testValidPaidWebhookSuccess() {
        UUID orderId = UUID.randomUUID();
        String eventId = "evt_paid_123";
        String sessionId = "cs_test_session_123";
        byte[] rawBody = createSamplePayload(eventId, "checkout_session.payment.paid", orderId.toString(), sessionId, 15000, "PHP");

        doNothing().when(signatureVerifier).verify(anyString(), any(byte[].class), eq(webhookSecret), eq(true));

        ResponseEntity<String> response = controller.handleWebhook("t=123,te=abc", rawBody);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("OK", response.getBody());

        verify(paymentEventRepository, atLeastOnce()).save(any(PaymentEvent.class));
        verify(orderService, times(1)).markEscrowed(eq(orderId), eq(sessionId));
    }

    @Test
    @DisplayName("Invalid signature returns 403 Forbidden without processing payload")
    void testInvalidSignatureReturns403() {
        byte[] rawBody = "{}".getBytes(StandardCharsets.UTF_8);
        doThrow(new PayMongoSignatureException("Bad signature"))
                .when(signatureVerifier).verify(any(), any(), anyString(), anyBoolean());

        ResponseEntity<String> response = controller.handleWebhook("t=1,te=bad", rawBody);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("Invalid signature", response.getBody());

        verify(paymentEventRepository, never()).save(any());
        verify(orderService, never()).markEscrowed(any(), any());
    }

    @Test
    @DisplayName("Malformed JSON returns 400 Bad Request")
    void testMalformedJsonReturns400() {
        byte[] malformedBody = "{not-json}".getBytes(StandardCharsets.UTF_8);
        doNothing().when(signatureVerifier).verify(any(), any(), anyString(), anyBoolean());

        ResponseEntity<String> response = controller.handleWebhook("t=1,te=valid", malformedBody);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(orderService, never()).markEscrowed(any(), any());
    }

    @Test
    @DisplayName("Unrelated event type returns 200 OK and is ignored")
    void testUnrelatedEventTypeIgnored() {
        byte[] rawBody = createSamplePayload("evt_456", "payment.failed", UUID.randomUUID().toString(), "cs_456", 10000, "PHP");
        doNothing().when(signatureVerifier).verify(any(), any(), anyString(), anyBoolean());

        ResponseEntity<String> response = controller.handleWebhook("t=1,te=valid", rawBody);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("Ignored"));
        verify(orderService, never()).markEscrowed(any(), any());
    }

    @Test
    @DisplayName("Duplicate event ID (DataIntegrityViolationException) returns 200 and ignores duplicate")
    void testDuplicateWebhookIdempotent() {
        UUID orderId = UUID.randomUUID();
        byte[] rawBody = createSamplePayload("evt_dup_789", "checkout_session.payment.paid", orderId.toString(), "cs_dup", 10000, "PHP");
        doNothing().when(signatureVerifier).verify(any(), any(), anyString(), anyBoolean());
        doThrow(new DataIntegrityViolationException("Duplicate event key"))
                .when(paymentEventRepository).save(any(PaymentEvent.class));

        ResponseEntity<String> response = controller.handleWebhook("t=1,te=valid", rawBody);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Duplicate event ignored", response.getBody());
        verify(orderService, never()).markEscrowed(any(), any());
    }

    @Test
    @DisplayName("Non-PHP currency fails validation and does not call markEscrowed")
    void testNonPhpCurrencyValidationFails() {
        UUID orderId = UUID.randomUUID();
        byte[] rawBody = createSamplePayload("evt_usd", "checkout_session.payment.paid", orderId.toString(), "cs_usd", 10000, "USD");
        doNothing().when(signatureVerifier).verify(any(), any(), anyString(), anyBoolean());

        ResponseEntity<String> response = controller.handleWebhook("t=1,te=valid", rawBody);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("Validation failed"));
        verify(orderService, never()).markEscrowed(any(), any());
    }
}
