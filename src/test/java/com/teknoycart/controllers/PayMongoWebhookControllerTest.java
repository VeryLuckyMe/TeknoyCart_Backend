package com.teknoycart.controllers;

import com.teknoycart.security.PayMongoSignatureVerifier;
import com.teknoycart.security.PayMongoSignatureVerifier.PayMongoSignatureException;
import com.teknoycart.services.PaymentWebhookService;
import com.teknoycart.services.PaymentWebhookService.Outcome;
import com.teknoycart.services.PaymentWebhookService.PaidEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    @Mock private PayMongoSignatureVerifier signatureVerifier;
    @Mock private PaymentWebhookService paymentWebhookService;
    @InjectMocks private PayMongoWebhookController controller;

    private final String webhookSecret = "whsk_test_mock_secret";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "webhookSecret", webhookSecret);
        ReflectionTestUtils.setField(controller, "testMode", true);
    }

    /**
     * Shape of a real checkout_session.payment.paid webhook: the session has
     * no top-level amount; the paid amount is under payments[].attributes.
     */
    private byte[] payload(String eventId, String eventType, String orderId, String sessionId, long amount) {
        String json = """
        {
          "data": {
            "id": "%s",
            "type": "event",
            "attributes": {
              "type": "%s",
              "data": {
                "id": "%s",
                "type": "checkout_session",
                "attributes": {
                  "metadata": { "order_id": "%s" },
                  "line_items": [ { "amount": %d, "currency": "PHP", "quantity": 1 } ],
                  "payments": [
                    { "id": "pay_1", "type": "payment",
                      "attributes": { "amount": %d, "currency": "PHP", "status": "paid" } }
                  ]
                }
              }
            }
          }
        }
        """.formatted(eventId, eventType, sessionId, orderId, amount, amount);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private void signatureOk() {
        doNothing().when(signatureVerifier).verify(any(), any(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("Paid event is parsed from payments[] and handed to the service; PROCESSED -> 200 OK")
    void paidEventProcessed() {
        UUID orderId = UUID.randomUUID();
        signatureOk();
        when(paymentWebhookService.process(any())).thenReturn(Outcome.PROCESSED);

        ResponseEntity<String> response = controller.handleWebhook("t=1,te=x",
                payload("evt_1", "checkout_session.payment.paid", orderId.toString(), "cs_1", 15000));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("OK", response.getBody());

        ArgumentCaptor<PaidEvent> captor = ArgumentCaptor.forClass(PaidEvent.class);
        verify(paymentWebhookService).process(captor.capture());
        PaidEvent e = captor.getValue();
        assertEquals("evt_1", e.eventId());
        assertEquals(orderId.toString(), e.rawOrderId());
        assertEquals("cs_1", e.sessionId());
        assertEquals(15000L, e.amountCentavos());
        assertEquals("PHP", e.currency());
    }

    @Test
    @DisplayName("Invalid signature returns 403 without processing")
    void invalidSignature() {
        doThrow(new PayMongoSignatureException("Bad signature"))
                .when(signatureVerifier).verify(any(), any(), anyString(), anyBoolean());

        ResponseEntity<String> response = controller.handleWebhook("t=1,te=bad", "{}".getBytes(StandardCharsets.UTF_8));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verifyNoInteractions(paymentWebhookService);
    }

    @Test
    @DisplayName("Malformed JSON returns 400")
    void malformedJson() {
        signatureOk();

        ResponseEntity<String> response = controller.handleWebhook("t=1,te=x", "{not-json}".getBytes(StandardCharsets.UTF_8));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(paymentWebhookService);
    }

    @Test
    @DisplayName("Unrelated event type is ignored with 200")
    void unrelatedEventIgnored() {
        signatureOk();

        ResponseEntity<String> response = controller.handleWebhook("t=1,te=x",
                payload("evt_2", "payment.failed", UUID.randomUUID().toString(), "cs_2", 100));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("Ignored"));
        verifyNoInteractions(paymentWebhookService);
    }

    @Test
    @DisplayName("DUPLICATE -> 200")
    void duplicate() {
        signatureOk();
        when(paymentWebhookService.process(any())).thenReturn(Outcome.DUPLICATE);

        ResponseEntity<String> response = controller.handleWebhook("t=1,te=x",
                payload("evt_3", "checkout_session.payment.paid", UUID.randomUUID().toString(), "cs_3", 100));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Duplicate event ignored", response.getBody());
    }

    @Test
    @DisplayName("FLAGGED (mismatch) -> 200, because a retry cannot fix it")
    void flagged() {
        signatureOk();
        when(paymentWebhookService.process(any())).thenReturn(Outcome.FLAGGED);

        ResponseEntity<String> response = controller.handleWebhook("t=1,te=x",
                payload("evt_4", "checkout_session.payment.paid", UUID.randomUUID().toString(), "cs_4", 100));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Recorded for admin review", response.getBody());
    }

    @Test
    @DisplayName("Processing error -> 500 so PayMongo redelivers")
    void processingErrorReturns500() {
        signatureOk();
        when(paymentWebhookService.process(any())).thenThrow(new RuntimeException("db down"));

        ResponseEntity<String> response = controller.handleWebhook("t=1,te=x",
                payload("evt_5", "checkout_session.payment.paid", UUID.randomUUID().toString(), "cs_5", 100));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }
}
