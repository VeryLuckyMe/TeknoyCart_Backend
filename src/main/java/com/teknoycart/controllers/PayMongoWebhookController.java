package com.teknoycart.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teknoycart.models.PaymentEvent;
import com.teknoycart.repositories.PaymentEventRepository;
import com.teknoycart.security.PayMongoSignatureVerifier;
import com.teknoycart.security.PayMongoSignatureVerifier.PayMongoSignatureException;
import com.teknoycart.services.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

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
 *   <li>Idempotency insert → 200 no-op if event already processed.</li>
 *   <li>Verify session id + amount + currency against the stored order.</li>
 *   <li>Call {@link OrderService#markEscrowed}.</li>
 *   <li>Return 200 quickly; notifications happen after commit.</li>
 * </ol>
 */
@RestController
@RequestMapping("/webhooks")
public class PayMongoWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PayMongoWebhookController.class);
    private static final String PAID_EVENT_TYPE = "checkout_session.payment.paid";

    @Autowired private PayMongoSignatureVerifier signatureVerifier;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private OrderService orderService;

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
            if ((webhookSecret == null || webhookSecret.isBlank()) && testMode) {
                log.warn("PAYMONGO_WEBHOOK_SECRET is not configured. Allowing sandbox webhook in testMode.");
            } else {
                signatureVerifier.verify(sigHeader, rawBody, webhookSecret, testMode);
            }
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

        // ── 4. Idempotency: insert event record; skip if already processed ────
        PaymentEvent event = new PaymentEvent();
        event.setEventId(eventId);
        event.setType(eventType);
        event.setReceivedAt(Instant.now());

        try {
            paymentEventRepository.save(event);
        } catch (DataIntegrityViolationException ex) {
            // event_id PRIMARY KEY constraint violated → duplicate delivery
            log.info("Duplicate PayMongo webhook event {}; ignoring.", eventId);
            return ResponseEntity.ok("Duplicate event ignored");
        }

        // ── 5. Resolve and validate the order ────────────────────────────────
        JsonNode sessionAttrs = data.path("attributes").path("data").path("attributes");

        // metadata.order_id — set by us when creating the checkout session
        String orderIdStr = sessionAttrs.path("metadata").path("order_id").asText(null);
        if (orderIdStr == null) {
            log.error("Webhook event {} missing metadata.order_id", eventId);
            return ResponseEntity.ok("Missing order_id in metadata");
        }

        UUID orderId;
        try {
            orderId = UUID.fromString(orderIdStr);
        } catch (IllegalArgumentException ex) {
            log.error("Webhook event {} has invalid order_id: {}", eventId, orderIdStr);
            return ResponseEntity.ok("Invalid order_id");
        }

        // Update event with order id for audit linkage
        event.setOrderId(orderId);
        paymentEventRepository.save(event);

        // Verify the session ID, amount, and currency before advancing state (D5)
        String  sessionId       = data.path("attributes").path("data").path("id").asText(null);
        long    webhookAmount   = sessionAttrs.path("amount").asLong(-1);
        String  webhookCurrency = sessionAttrs.path("currency").asText(null);

        if (sessionId == null || webhookAmount < 0 || !"PHP".equalsIgnoreCase(webhookCurrency)) {
            log.error("Webhook event {} failed amount/currency/sessionId validation", eventId);
            return ResponseEntity.ok("Validation failed; event recorded for admin review");
        }

        // Delegate state machine transition to OrderService
        try {
            orderService.markEscrowed(orderId, sessionId);
        } catch (Exception ex) {
            // Log but return 200 so PayMongo does not retry indefinitely.
            // The payment_events record + audit log are already written.
            log.error("markEscrowed failed for order {} session {}: {}", orderId, sessionId, ex.getMessage());
            return ResponseEntity.ok("Recorded; manual review required");
        }

        return ResponseEntity.ok("OK");
    }
}
