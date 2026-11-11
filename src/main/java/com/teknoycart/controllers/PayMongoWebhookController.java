package com.teknoycart.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teknoycart.security.PayMongoSignatureVerifier;
import com.teknoycart.security.PayMongoSignatureVerifier.PayMongoSignatureException;
import com.teknoycart.services.PaymentWebhookService;
import com.teknoycart.services.PaymentWebhookService.Outcome;
import com.teknoycart.services.PaymentWebhookService.PaidEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


/**
 * Receives PayMongo webhook events.
 *
 * <p>Full public path (with context-path /api): {@code POST /api/webhooks/paymongo}
 * Register this URL in the PayMongo sandbox dashboard.
 *
 * <p>Security: no JWT required. Validated entirely via the {@code Paymongo-Signature} header.
 * The endpoint is permit-all in SecurityConfig, scoped to {@code /webhooks/**} only.
 *
 * <h3>Processing order:</h3>
 * <ol>
 *   <li>Read raw body (Spring binding disabled for this endpoint via {@code byte[]}).</li>
 *   <li>Verify signature → 403 on failure.</li>
 *   <li>Parse event id/type; ignore unknown types with 200.</li>
 *   <li>{@link PaymentWebhookService#process} in one transaction: lock the order,
 *       idempotency check, verify session id + amount + currency, then ESCROWED.
 *       Payments that cannot be applied go to payment_exceptions (200).</li>
 *   <li>Any processing error rolls everything back and returns 500 so PayMongo
 *       redelivers the event.</li>
 * </ol>
 */
@RestController
@RequestMapping("/webhooks")
public class PayMongoWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PayMongoWebhookController.class);
    private static final String PAID_EVENT_TYPE = "checkout_session.payment.paid";

    @Autowired private PayMongoSignatureVerifier signatureVerifier;
    @Autowired private PaymentWebhookService paymentWebhookService;

    @Value("${paymongo.webhook-secret}")
    private String webhookSecret;

    @Value("${paymongo.test-mode:true}")
    private boolean testMode;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/paymongo")
    public ResponseEntity<String> handleWebhook(
            @RequestHeader(value = "Paymongo-Signature", required = false) String sigHeader,
            @RequestBody byte[] rawBody) {

        // ── 1. Signature verification ─────────────────────────────────────────
        try {
            signatureVerifier.verify(sigHeader, rawBody, webhookSecret, testMode);
        } catch (PayMongoSignatureException ex) {
            log.warn("PayMongo webhook rejected: {}", ex.getMessage());
            return ResponseEntity.status(403).body("Invalid signature");
        }

        // ── 2. Parse event ────────────────────────────────────────────────────
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception ex) {
            log.error("Failed to parse PayMongo webhook body", ex);
            return ResponseEntity.badRequest().body("Unparseable body");
        }

        JsonNode data       = root.path("data");
        String  eventId     = data.path("id").asText(null);
        String  eventType   = data.path("attributes").path("type").asText(null);

        if (eventId == null || eventType == null) {
            log.warn("Webhook missing event id or type; ignoring");
            return ResponseEntity.ok("Ignored: missing id/type");
        }

        // ── 3. Ignore unrelated events ────────────────────────────────────────
        if (!PAID_EVENT_TYPE.equals(eventType)) {
            log.debug("Ignoring unrelated PayMongo event type: {}", eventType);
            return ResponseEntity.ok("Ignored: " + eventType);
        }

        // ── 4. Apply in one transaction (idempotency + validation + transition) ──
        PaidEvent paidEvent = PaidEvent.fromCheckoutSession(
                eventId, eventType, data.path("attributes").path("data"));

        Outcome outcome;
        try {
            outcome = paymentWebhookService.process(paidEvent);
        } catch (Exception ex) {
            // Transaction rolled back (including the idempotency row), so a
            // redelivery is processed from scratch. 500 makes PayMongo retry.
            log.error("PayMongo webhook {} failed; returning 500 for retry: {}", eventId, ex.getMessage(), ex);
            return ResponseEntity.status(500).body("Processing failed; please retry");
        }

        return switch (outcome) {
            case PROCESSED -> ResponseEntity.ok("OK");
            case DUPLICATE -> ResponseEntity.ok("Duplicate event ignored");
            // Retrying cannot fix a mismatch; it is queued for admin review.
            case FLAGGED   -> ResponseEntity.ok("Recorded for admin review");
        };
    }
}
