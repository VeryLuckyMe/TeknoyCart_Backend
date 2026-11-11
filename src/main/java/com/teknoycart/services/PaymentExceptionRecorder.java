package com.teknoycart.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Writes rows to {@code public.payment_exceptions} (migration V18): payments
 * that reached us but could not be applied to an order, for admin review.
 *
 * <p>Uses plain JDBC rather than a JPA entity because hibernate
 * {@code ddl-auto=validate} runs before boot migrations create the table.
 * The JdbcTemplate shares the caller's transaction, so a rolled-back webhook
 * also rolls back its exception row.
 */
@Service
public class PaymentExceptionRecorder {

    private static final Logger log = LoggerFactory.getLogger(PaymentExceptionRecorder.class);

    public enum Kind {
        AMOUNT_MISMATCH,
        SESSION_MISMATCH,
        CURRENCY_MISMATCH,
        PAID_IN_UNEXPECTED_STATE,
        UNKNOWN_ORDER,
        MISSING_ORDER_ID
    }

    /** What we know about a payment that could not be applied. */
    public record Issue(
            Kind kind,
            UUID orderId,
            String eventId,
            String sessionId,
            Long amountCentavos,
            Long expectedCentavos,
            String orderStatus,
            String details) {}

    @Autowired private JdbcTemplate jdbcTemplate;

    public void record(Issue issue) {
        log.error("PAYMENT_EXCEPTION {} order={} event={} session={} amount={} expected={} status={} {}",
                issue.kind(), issue.orderId(), issue.eventId(), issue.sessionId(),
                issue.amountCentavos(), issue.expectedCentavos(), issue.orderStatus(), issue.details());

        jdbcTemplate.update(
            "INSERT INTO public.payment_exceptions " +
            "  (order_id, event_id, session_id, kind, amount_centavos, expected_centavos, order_status, details) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (event_id, kind) WHERE event_id IS NOT NULL DO NOTHING",
            issue.orderId(), issue.eventId(), issue.sessionId(), issue.kind().name(),
            issue.amountCentavos(), issue.expectedCentavos(), issue.orderStatus(), issue.details());
    }
}
