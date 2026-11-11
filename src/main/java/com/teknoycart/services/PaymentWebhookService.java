package com.teknoycart.services;

import com.teknoycart.models.Order;
import com.teknoycart.models.OrderStatus;
import com.teknoycart.models.PaymentEvent;
import com.teknoycart.repositories.OrderRepository;
import com.teknoycart.repositories.PaymentEventRepository;
import com.teknoycart.services.PaymentExceptionRecorder.Issue;
import com.teknoycart.services.PaymentExceptionRecorder.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies a verified-signature PayMongo "checkout_session.payment.paid" event.
 *
 * <p>Everything happens in ONE transaction: the idempotency record, the order
 * transition and any payment exception. If anything throws, all of it rolls
 * back and the controller answers 500, so PayMongo redelivers and the retry is
 * processed from scratch (the idempotency row from the failed attempt does not
 * exist).
 *
 * <p>An order moves to ESCROWED only when the session id, amount and currency
 * all match what we stored when the checkout session was created. Payments
 * that cannot be applied are written to payment_exceptions for admin review.
 */
@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

    public enum Outcome {
        /** Order moved to ESCROWED. */
        PROCESSED,
        /** Already handled; nothing changed. */
        DUPLICATE,
        /** Payment could not be applied; recorded in payment_exceptions. */
        FLAGGED
    }

    /** Fields extracted from the webhook body. Any may be null if absent. */
    public record PaidEvent(
            String eventId,
            String eventType,
            String rawOrderId,
            String sessionId,
            Long amountCentavos,
            String currency) {

        /**
         * Builds the event from a PayMongo {@code checkout_session} resource
         * ({@code data.attributes.data} of the webhook).
         *
         * <p>A checkout session has NO top-level {@code amount} attribute
         * (docs.paymongo.com/reference/checkout-session-resource). The amount
         * actually paid is the sum of {@code payments[].attributes.amount} with
         * status "paid"; falls back to {@code payment_intent.attributes.amount}.
         */
        public static PaidEvent fromCheckoutSession(String eventId, String eventType,
                                                    com.fasterxml.jackson.databind.JsonNode session) {
            var attrs = session.path("attributes");
            Long amount = null;
            String currency = null;

            var payments = attrs.path("payments");
            if (payments.isArray()) {
                long paid = 0;
                boolean anyPaid = false;
                for (var payment : payments) {
                    var p = payment.path("attributes");
                    if ("paid".equalsIgnoreCase(p.path("status").asText("")) && p.path("amount").isNumber()) {
                        paid += p.path("amount").asLong();
                        anyPaid = true;
                        if (currency == null) currency = p.path("currency").asText(null);
                    }
                }
                if (anyPaid) amount = paid;
            }
            if (amount == null) {
                var intent = attrs.path("payment_intent").path("attributes");
                if (intent.path("amount").isNumber()) {
                    amount = intent.path("amount").asLong();
                    currency = intent.path("currency").asText(null);
                }
            }
            if (currency == null) {
                var items = attrs.path("line_items");
                if (items.isArray() && !items.isEmpty()) {
                    currency = items.get(0).path("currency").asText(null);
                }
            }

            return new PaidEvent(
                    eventId,
                    eventType,
                    attrs.path("metadata").path("order_id").asText(null),
                    session.path("id").asText(null),
                    amount,
                    currency);
        }
    }

    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private PaymentExceptionRecorder exceptionRecorder;
    @Autowired private OrderService orderService;

    @Transactional
    public Outcome process(PaidEvent event) {
        UUID orderId = parseUuid(event.rawOrderId());
        if (orderId == null) {
            if (paymentEventRepository.existsById(event.eventId())) return Outcome.DUPLICATE;
            saveEvent(event, null);
            flag(Kind.MISSING_ORDER_ID, null, event, null, null,
                    "metadata.order_id missing or not a UUID: " + event.rawOrderId());
            return Outcome.FLAGGED;
        }

        // Lock first, then check idempotency: concurrent deliveries for the same
        // order queue on the row lock, and the second one sees the first's
        // committed payment_events row (READ COMMITTED takes a fresh snapshot
        // per statement).
        Optional<Order> locked = orderRepository.findByIdForUpdate(orderId);

        if (paymentEventRepository.existsById(event.eventId())) {
            log.info("Duplicate PayMongo webhook event {}; ignoring.", event.eventId());
            return Outcome.DUPLICATE;
        }

        if (locked.isEmpty()) {
            saveEvent(event, null);
            flag(Kind.UNKNOWN_ORDER, null, event, null, null, "No order with id " + orderId);
            return Outcome.FLAGGED;
        }

        Order order = locked.get();
        saveEvent(event, orderId);
        OrderStatus status = order.getStatus();

        if (status == OrderStatus.ESCROWED) {
            if (event.sessionId() != null && event.sessionId().equals(order.getPaymongoCheckoutSessionId())) {
                return Outcome.DUPLICATE; // same payment, re-sent under a new event id
            }
            flag(Kind.PAID_IN_UNEXPECTED_STATE, orderId, event, order.getAmountCentavos(), status,
                    "Order already ESCROWED by session " + order.getPaymongoCheckoutSessionId()
                            + "; possible double payment");
            return Outcome.FLAGGED;
        }

        if (status != OrderStatus.AWAITING_PAYMENT) {
            // e.g. the checkout session expired (order reverted to ACCEPTED) or the
            // order was cancelled, but the buyer still paid. Needs a refund.
            flag(Kind.PAID_IN_UNEXPECTED_STATE, orderId, event, order.getAmountCentavos(), status,
                    "Payment arrived while order was " + status + "; refund required");
            return Outcome.FLAGGED;
        }

        List<Kind> mismatches = new ArrayList<>();
        if (!"PHP".equalsIgnoreCase(event.currency())) {
            mismatches.add(Kind.CURRENCY_MISMATCH);
        }
        if (event.sessionId() == null || !event.sessionId().equals(order.getPaymongoCheckoutSessionId())) {
            mismatches.add(Kind.SESSION_MISMATCH);
        }
        if (order.getAmountCentavos() == null || !order.getAmountCentavos().equals(event.amountCentavos())) {
            mismatches.add(Kind.AMOUNT_MISMATCH);
        }

        if (!mismatches.isEmpty()) {
            for (Kind kind : mismatches) {
                flag(kind, orderId, event, order.getAmountCentavos(), status,
                        "Expected session " + order.getPaymongoCheckoutSessionId()
                                + ", currency PHP, amount " + order.getAmountCentavos());
            }
            return Outcome.FLAGGED;
        }

        orderService.markEscrowed(orderId, event.sessionId());
        return Outcome.PROCESSED;
    }

    private void saveEvent(PaidEvent event, UUID orderId) {
        PaymentEvent row = new PaymentEvent();
        row.setEventId(event.eventId());
        row.setType(event.eventType());
        row.setOrderId(orderId);
        row.setReceivedAt(Instant.now());
        // Flush now so a primary-key race fails here, inside the transaction.
        paymentEventRepository.saveAndFlush(row);
    }

    private void flag(Kind kind, UUID orderId, PaidEvent event, Long expected,
                      OrderStatus status, String details) {
        exceptionRecorder.record(new Issue(
                kind, orderId, event.eventId(), event.sessionId(), event.amountCentavos(),
                expected, status != null ? status.name() : null, details));
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
