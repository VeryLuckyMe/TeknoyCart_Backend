package com.teknoycart.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.teknoycart.models.Order;
import com.teknoycart.models.OrderStatus;
import com.teknoycart.repositories.OrderRepository;
import com.teknoycart.services.PaymentWebhookService.Outcome;
import com.teknoycart.services.PaymentWebhookService.PaidEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Safety net for missed PayMongo webhooks (Render free spins down, webhook
 * retries run out, etc.). Every 10 minutes it asks PayMongo about each order
 * still AWAITING_PAYMENT and, if the session was paid, feeds it through the
 * SAME verified path as the webhook ({@link PaymentWebhookService#process}),
 * so amount/session/currency checks and payment_exceptions apply equally.
 *
 * <p>The synthetic event id {@code reconcile:<session>} is stable, so repeated
 * runs are idempotent, and a late real webhook for the same session is seen
 * as a duplicate.
 */
@Service
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);
    static final int MAX_PER_RUN = 50;
    static final String EVENT_TYPE = "reconcile.checkout_session.paid";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired private OrderRepository orderRepository;
    @Autowired private PayMongoClient payMongoClient;
    @Autowired private PaymentWebhookService webhookService;

    public record RunSummary(int checked, int escrowed, int flagged, int errors) {}

    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void scheduledRun() {
        RunSummary s = reconcileAwaitingPayments();
        if (s.checked() > 0) {
            log.info("PayMongo reconciliation: checked={} escrowed={} flagged={} errors={}",
                    s.checked(), s.escrowed(), s.flagged(), s.errors());
        }
    }

    public RunSummary reconcileAwaitingPayments() {
        List<Order> pending = orderRepository.findByStatus(OrderStatus.AWAITING_PAYMENT);
        int checked = 0, escrowed = 0, flagged = 0, errors = 0;
        for (Order order : pending) {
            if (checked >= MAX_PER_RUN) break;
            String sessionId = order.getPaymongoCheckoutSessionId();
            if (sessionId == null || sessionId.isBlank()) continue;
            checked++;
            try {
                Outcome outcome = reconcile(order, sessionId);
                if (outcome == Outcome.PROCESSED) escrowed++;
                else if (outcome == Outcome.FLAGGED) flagged++;
            } catch (Exception e) {
                errors++;
                log.warn("Reconciliation failed for order {} (session {}): {}",
                        order.getId(), sessionId, e.getMessage());
            }
        }
        return new RunSummary(checked, escrowed, flagged, errors);
    }

    /** @return the processing outcome, or null when the session is not paid yet. */
    Outcome reconcile(Order order, String sessionId) {
        JsonNode attrs = payMongoClient.getCheckoutSession(sessionId);
        ObjectNode session = mapper.createObjectNode();
        session.put("id", sessionId);
        session.set("attributes", attrs);

        PaidEvent parsed = PaidEvent.fromCheckoutSession("reconcile:" + sessionId, EVENT_TYPE, session);
        if (parsed.amountCentavos() == null || !isPaid(attrs)) {
            return null; // not paid yet; nothing to do
        }
        String orderId = parsed.rawOrderId() != null ? parsed.rawOrderId() : order.getId().toString();
        PaidEvent event = new PaidEvent(parsed.eventId(), parsed.eventType(), orderId,
                parsed.sessionId(), parsed.amountCentavos(), parsed.currency());
        Outcome outcome = webhookService.process(event);
        if (outcome == Outcome.PROCESSED) {
            log.warn("Order {} escrowed by reconciliation: its PayMongo webhook was missed.", order.getId());
        }
        return outcome;
    }

    private static boolean isPaid(JsonNode attrs) {
        if ("paid".equalsIgnoreCase(attrs.path("status").asText(""))) return true;
        for (JsonNode p : attrs.path("payments")) {
            if ("paid".equalsIgnoreCase(p.path("attributes").path("status").asText(""))) return true;
        }
        return false;
    }
}
