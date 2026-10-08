package com.teknoycart.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;

/**
 * HTTP client for the PayMongo Checkout Sessions API.
 *
 * <h3>Spike findings — Oct 2026 (Phase 1.3)</h3>
 * <ul>
 *   <li>Endpoint: {@code POST https://api.paymongo.com/v1/checkout_sessions}</li>
 *   <li>Auth: HTTP Basic with secret key as username, empty password.</li>
 *   <li>GCash: {@code payment_method_types: ["gcash"]}</li>
 *   <li>Metadata: arbitrary key-value pairs returned in webhook payload under
 *       {@code data.attributes.metadata}</li>
 *   <li>Webhook event type for paid sessions: {@code checkout_session.payment.paid}</li>
 *   <li>Amount: integer centavos (e.g. PHP 100.50 = 10050)</li>
 *   <li>Signature header: {@code t=<ts>,te=<test-hmac>,li=<live-hmac>}
 *       signed over {@code ts + "." + rawBody} with HMAC-SHA256</li>
 * </ul>
 *
 * <p>Uses {@link RestTemplate} (already available via spring-boot-starter-web).
 * No extra Maven dependency required.
 */
@Service
public class PayMongoClient {

    private static final Logger log = LoggerFactory.getLogger(PayMongoClient.class);

    @Value("${paymongo.secret-key}")
    private String secretKey;

    @Value("${paymongo.base-url:https://api.paymongo.com}")
    private String baseUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates a GCash-only Checkout Session on PayMongo.
     *
     * @param amountCentavos  amount in PHP centavos (e.g. 10050 = PHP 100.50)
     * @param orderId         TeknoyCart order UUID — stored in PayMongo metadata
     * @param description     short description shown on the PayMongo-hosted page
     * @param successUrl      URL PayMongo redirects the buyer to after payment
     * @param cancelUrl       URL PayMongo redirects the buyer to if they cancel
     * @param expiresAt       absolute expiry Unix timestamp in seconds (from checkout-ttl-minutes)
     * @return {@link CheckoutSessionResult} containing the session ID and checkout URL
     */
    public CheckoutSessionResult createGcashCheckoutSession(
            long amountCentavos,
            String orderId,
            String description,
            String successUrl,
            String cancelUrl,
            long expiresAt) {

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("amount", amountCentavos);
        attributes.put("currency", "PHP");
        attributes.put("payment_method_types", List.of("gcash"));
        attributes.put("description", description);
        attributes.put("reference_number", orderId);
        attributes.put("metadata", Map.of("order_id", orderId));
        attributes.put("success_url", successUrl);
        attributes.put("cancel_url", cancelUrl);

        Map<String, Object> body = Map.of("data", Map.of("attributes", attributes));

        HttpHeaders headers = buildHeaders();
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    baseUrl + "/v1/checkout_sessions", request, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode data = root.path("data");
            String sessionId  = data.path("id").asText();
            String checkoutUrl = data.path("attributes").path("checkout_url").asText();

            if (sessionId.isBlank() || checkoutUrl.isBlank()) {
                throw new PayMongoException("PayMongo response missing session id or checkout_url: "
                        + response.getBody());
            }

            log.info("Created PayMongo checkout session {} for order {}", sessionId, orderId);
            return new CheckoutSessionResult(sessionId, checkoutUrl);

        } catch (PayMongoException e) {
            throw e;
        } catch (Exception e) {
            throw new PayMongoException("Failed to create PayMongo checkout session", e);
        }
    }

    /**
     * Fetches a Checkout Session for amount/status reconciliation.
     *
     * @param sessionId PayMongo checkout session ID
     * @return raw JSON node of the session's {@code data.attributes}
     */
    public JsonNode getCheckoutSession(String sessionId) {
        HttpHeaders headers = buildHeaders();
        HttpEntity<Void> request = new HttpEntity<>(headers);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/v1/checkout_sessions/" + sessionId,
                    HttpMethod.GET, request, String.class);
            return objectMapper.readTree(response.getBody()).path("data").path("attributes");
        } catch (Exception e) {
            throw new PayMongoException("Failed to fetch PayMongo checkout session " + sessionId, e);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // PayMongo uses HTTP Basic auth: secret key as username, empty password.
        String credentials = Base64.getEncoder().encodeToString((secretKey + ":").getBytes());
        headers.set("Authorization", "Basic " + credentials);
        return headers;
    }

    // ── Result / Exception types ──────────────────────────────────────────────

    /** Holds the PayMongo session ID and hosted checkout URL. */
    public record CheckoutSessionResult(String sessionId, String checkoutUrl) {}

    /** Unchecked exception wrapping PayMongo API errors. */
    public static class PayMongoException extends RuntimeException {
        public PayMongoException(String msg)              { super(msg); }
        public PayMongoException(String msg, Throwable t) { super(msg, t); }
    }
}
