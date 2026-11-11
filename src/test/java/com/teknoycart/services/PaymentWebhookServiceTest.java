package com.teknoycart.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teknoycart.models.Order;
import com.teknoycart.models.OrderStatus;
import com.teknoycart.repositories.OrderAuditLogRepository;
import com.teknoycart.repositories.OrderRepository;
import com.teknoycart.repositories.PaymentEventRepository;
import com.teknoycart.services.PaymentExceptionRecorder.Issue;
import com.teknoycart.services.PaymentExceptionRecorder.Kind;
import com.teknoycart.services.PaymentWebhookService.Outcome;
import com.teknoycart.services.PaymentWebhookService.PaidEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DataJpaTest
@Import({PaymentWebhookService.class, OrderService.class, OrderOtpService.class})
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:webhookdb;MODE=PostgreSQL;TIME ZONE=UTC",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
    // findByIdForUpdate renders PostgreSQL's FOR NO KEY UPDATE, which H2 rejects.
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class PaymentWebhookServiceTest {

    private static final String SESSION = "cs_test_abc";
    private static final long AMOUNT = 15000L;

    @Autowired private PaymentWebhookService service;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderAuditLogRepository auditLogRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;

    @MockBean private PaymentExceptionRecorder exceptionRecorder;
    @MockBean private EmailService emailService;
    @MockBean private JdbcTemplate jdbcTemplate;
    @MockBean private PayMongoClient payMongoClient;
    @MockBean private PayoutService payoutService;

    @BeforeEach
    void clean() {
        auditLogRepository.deleteAll();
        paymentEventRepository.deleteAll();
        orderRepository.deleteAll();
    }

    private Order order(OrderStatus status) {
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setBuyerId(UUID.randomUUID());
        o.setSellerId(UUID.randomUUID());
        o.setStatus(status);
        o.setPaymentMethod("GCASH");
        o.setTotalAmount(new BigDecimal("150.00"));
        o.setAmountCentavos(AMOUNT);
        o.setPaymongoCheckoutSessionId(SESSION);
        return orderRepository.saveAndFlush(o);
    }

    private PaidEvent paid(String eventId, Order o, String session, Long amount, String currency) {
        return new PaidEvent(eventId, "checkout_session.payment.paid",
                o.getId().toString(), session, amount, currency);
    }

    private OrderStatus statusOf(Order o) {
        return orderRepository.findById(o.getId()).orElseThrow().getStatus();
    }

    private List<Kind> flaggedKinds() {
        ArgumentCaptor<Issue> captor = ArgumentCaptor.forClass(Issue.class);
        verify(exceptionRecorder, atLeastOnce()).record(captor.capture());
        return captor.getAllValues().stream().map(Issue::kind).toList();
    }

    @Test
    @DisplayName("Matching session, amount and currency moves AWAITING_PAYMENT to ESCROWED")
    void happyPath() {
        Order o = order(OrderStatus.AWAITING_PAYMENT);

        assertEquals(Outcome.PROCESSED, service.process(paid("evt_1", o, SESSION, AMOUNT, "PHP")));
        assertEquals(OrderStatus.ESCROWED, statusOf(o));
        assertTrue(paymentEventRepository.existsById("evt_1"));
        verifyNoInteractions(exceptionRecorder);
    }

    @Test
    @DisplayName("Wrong amount is flagged and the order is NOT escrowed")
    void amountMismatch() {
        Order o = order(OrderStatus.AWAITING_PAYMENT);

        assertEquals(Outcome.FLAGGED, service.process(paid("evt_2", o, SESSION, 100L, "PHP")));
        assertEquals(OrderStatus.AWAITING_PAYMENT, statusOf(o));
        assertEquals(List.of(Kind.AMOUNT_MISMATCH), flaggedKinds());
    }

    @Test
    @DisplayName("Missing amount (unrecognised payload) is flagged, never escrowed")
    void amountMissing() {
        Order o = order(OrderStatus.AWAITING_PAYMENT);

        assertEquals(Outcome.FLAGGED, service.process(paid("evt_2b", o, SESSION, null, "PHP")));
        assertEquals(OrderStatus.AWAITING_PAYMENT, statusOf(o));
        assertEquals(List.of(Kind.AMOUNT_MISMATCH), flaggedKinds());
    }

    @Test
    @DisplayName("Session id from another checkout is flagged and the order is NOT escrowed")
    void sessionMismatch() {
        Order o = order(OrderStatus.AWAITING_PAYMENT);

        assertEquals(Outcome.FLAGGED, service.process(paid("evt_3", o, "cs_other", AMOUNT, "PHP")));
        assertEquals(OrderStatus.AWAITING_PAYMENT, statusOf(o));
        assertEquals(List.of(Kind.SESSION_MISMATCH), flaggedKinds());
    }

    @Test
    @DisplayName("Non-PHP currency is flagged")
    void currencyMismatch() {
        Order o = order(OrderStatus.AWAITING_PAYMENT);

        assertEquals(Outcome.FLAGGED, service.process(paid("evt_4", o, SESSION, AMOUNT, "USD")));
        assertEquals(OrderStatus.AWAITING_PAYMENT, statusOf(o));
        assertEquals(List.of(Kind.CURRENCY_MISMATCH), flaggedKinds());
    }

    @Test
    @DisplayName("Same event delivered twice is processed once")
    void duplicateEvent() {
        Order o = order(OrderStatus.AWAITING_PAYMENT);
        service.process(paid("evt_5", o, SESSION, AMOUNT, "PHP"));

        assertEquals(Outcome.DUPLICATE, service.process(paid("evt_5", o, SESSION, AMOUNT, "PHP")));
        assertEquals(OrderStatus.ESCROWED, statusOf(o));
        verifyNoInteractions(exceptionRecorder);
    }

    @Test
    @DisplayName("Same session re-sent under a new event id on an ESCROWED order is a harmless duplicate")
    void sameSessionNewEventId() {
        Order o = order(OrderStatus.ESCROWED);

        assertEquals(Outcome.DUPLICATE, service.process(paid("evt_6", o, SESSION, AMOUNT, "PHP")));
        verifyNoInteractions(exceptionRecorder);
    }

    @Test
    @DisplayName("Payment for a CANCELLED order is flagged for refund")
    void paidAfterCancel() {
        Order o = order(OrderStatus.CANCELLED);

        assertEquals(Outcome.FLAGGED, service.process(paid("evt_7", o, SESSION, AMOUNT, "PHP")));
        assertEquals(OrderStatus.CANCELLED, statusOf(o));
        assertEquals(List.of(Kind.PAID_IN_UNEXPECTED_STATE), flaggedKinds());
    }

    @Test
    @DisplayName("Payment after the session expired (order back to ACCEPTED) is flagged for refund")
    void paidAfterExpiry() {
        Order o = order(OrderStatus.ACCEPTED);

        assertEquals(Outcome.FLAGGED, service.process(paid("evt_8", o, SESSION, AMOUNT, "PHP")));
        assertEquals(OrderStatus.ACCEPTED, statusOf(o));
        assertEquals(List.of(Kind.PAID_IN_UNEXPECTED_STATE), flaggedKinds());
    }

    @Test
    @DisplayName("Unknown order id is flagged")
    void unknownOrder() {
        PaidEvent e = new PaidEvent("evt_9", "checkout_session.payment.paid",
                UUID.randomUUID().toString(), SESSION, AMOUNT, "PHP");

        assertEquals(Outcome.FLAGGED, service.process(e));
        assertEquals(List.of(Kind.UNKNOWN_ORDER), flaggedKinds());
    }

    @Test
    @DisplayName("Missing or malformed order id is flagged")
    void missingOrderId() {
        PaidEvent e = new PaidEvent("evt_10", "checkout_session.payment.paid",
                "not-a-uuid", SESSION, AMOUNT, "PHP");

        assertEquals(Outcome.FLAGGED, service.process(e));
        assertEquals(List.of(Kind.MISSING_ORDER_ID), flaggedKinds());
    }

    @Test
    @DisplayName("If recording fails, the exception propagates so the controller can answer 500")
    void recorderFailurePropagates() {
        Order o = order(OrderStatus.CANCELLED);
        doThrow(new RuntimeException("db down")).when(exceptionRecorder).record(any());

        assertThrows(RuntimeException.class,
                () -> service.process(paid("evt_11", o, SESSION, AMOUNT, "PHP")));
    }

    @Nested
    @DisplayName("PaidEvent.fromCheckoutSession")
    class Parsing {
        private final ObjectMapper mapper = new ObjectMapper();

        private JsonNode session(String attributesJson) throws Exception {
            return mapper.readTree("{\"id\":\"cs_x\",\"type\":\"checkout_session\",\"attributes\":"
                    + attributesJson + "}");
        }

        @Test
        @DisplayName("Sums paid payments; there is no top-level amount on a checkout session")
        void fromPayments() throws Exception {
            PaidEvent e = PaidEvent.fromCheckoutSession("evt", "t", session("""
                {"metadata":{"order_id":"abc"},
                 "line_items":[{"amount":15000,"currency":"PHP","quantity":1}],
                 "payments":[
                   {"attributes":{"amount":15000,"currency":"PHP","status":"paid"}},
                   {"attributes":{"amount":999,"currency":"PHP","status":"failed"}}]}
                """));

            assertEquals(15000L, e.amountCentavos());
            assertEquals("PHP", e.currency());
            assertEquals("cs_x", e.sessionId());
            assertEquals("abc", e.rawOrderId());
        }

        @Test
        @DisplayName("Falls back to payment_intent amount when payments are absent")
        void fromPaymentIntent() throws Exception {
            PaidEvent e = PaidEvent.fromCheckoutSession("evt", "t", session("""
                {"payment_intent":{"attributes":{"amount":20000,"currency":"PHP"}}}
                """));

            assertEquals(20000L, e.amountCentavos());
            assertEquals("PHP", e.currency());
        }

        @Test
        @DisplayName("No amount anywhere yields null (which the service flags)")
        void noAmount() throws Exception {
            PaidEvent e = PaidEvent.fromCheckoutSession("evt", "t", session("""
                {"line_items":[{"amount":15000,"currency":"PHP","quantity":1}]}
                """));

            assertNull(e.amountCentavos());
            assertEquals("PHP", e.currency());
        }
    }
}
