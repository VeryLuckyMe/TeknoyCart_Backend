package com.teknoycart.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.teknoycart.models.Order;
import com.teknoycart.models.OrderStatus;
import com.teknoycart.repositories.OrderRepository;
import com.teknoycart.services.PaymentWebhookService.Outcome;
import com.teknoycart.services.PaymentWebhookService.PaidEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentReconciliationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock private OrderRepository orderRepository;
    @Mock private PayMongoClient payMongoClient;
    @Mock private PaymentWebhookService webhookService;
    @InjectMocks private PaymentReconciliationService service;

    private Order awaiting(String session) {
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setStatus(OrderStatus.AWAITING_PAYMENT);
        o.setPaymongoCheckoutSessionId(session);
        return o;
    }

    private ObjectNode sessionAttrs(String paymentStatus, long amount, String orderId) {
        ObjectNode attrs = mapper.createObjectNode();
        attrs.put("status", "active");
        ObjectNode p = attrs.putArray("payments").addObject().putObject("attributes");
        p.put("status", paymentStatus);
        p.put("amount", amount);
        p.put("currency", "PHP");
        if (orderId != null) attrs.putObject("metadata").put("order_id", orderId);
        return attrs;
    }

    @Test
    void paidSessionGoesThroughTheVerifiedWebhookPath() {
        Order o = awaiting("cs_1");
        when(orderRepository.findByStatus(OrderStatus.AWAITING_PAYMENT)).thenReturn(List.of(o));
        when(payMongoClient.getCheckoutSession("cs_1")).thenReturn(sessionAttrs("paid", 15000, o.getId().toString()));
        when(webhookService.process(any())).thenReturn(Outcome.PROCESSED);

        var summary = service.reconcileAwaitingPayments();

        ArgumentCaptor<PaidEvent> ev = ArgumentCaptor.forClass(PaidEvent.class);
        verify(webhookService).process(ev.capture());
        assertEquals("reconcile:cs_1", ev.getValue().eventId());
        assertEquals("cs_1", ev.getValue().sessionId());
        assertEquals(15000L, ev.getValue().amountCentavos());
        assertEquals(o.getId().toString(), ev.getValue().rawOrderId());
        assertEquals(1, summary.escrowed());
    }

    @Test
    void unpaidSessionIsLeftAlone() {
        Order o = awaiting("cs_2");
        when(orderRepository.findByStatus(OrderStatus.AWAITING_PAYMENT)).thenReturn(List.of(o));
        ObjectNode attrs = mapper.createObjectNode();
        attrs.put("status", "active");
        attrs.putArray("payments");
        when(payMongoClient.getCheckoutSession("cs_2")).thenReturn(attrs);

        var summary = service.reconcileAwaitingPayments();

        verify(webhookService, never()).process(any());
        assertEquals(1, summary.checked());
        assertEquals(0, summary.escrowed());
    }

    @Test
    void missingMetadataFallsBackToTheKnownOrderId() {
        Order o = awaiting("cs_3");
        when(orderRepository.findByStatus(OrderStatus.AWAITING_PAYMENT)).thenReturn(List.of(o));
        when(payMongoClient.getCheckoutSession("cs_3")).thenReturn(sessionAttrs("paid", 500, null));
        when(webhookService.process(any())).thenReturn(Outcome.FLAGGED);

        var summary = service.reconcileAwaitingPayments();

        ArgumentCaptor<PaidEvent> ev = ArgumentCaptor.forClass(PaidEvent.class);
        verify(webhookService).process(ev.capture());
        assertEquals(o.getId().toString(), ev.getValue().rawOrderId());
        assertEquals(1, summary.flagged());
    }

    @Test
    void oneFailingOrderDoesNotStopTheRun() {
        Order bad = awaiting("cs_bad");
        Order good = awaiting("cs_good");
        Order noSession = awaiting(null);
        when(orderRepository.findByStatus(OrderStatus.AWAITING_PAYMENT)).thenReturn(List.of(bad, noSession, good));
        when(payMongoClient.getCheckoutSession("cs_bad")).thenThrow(new RuntimeException("PayMongo down"));
        when(payMongoClient.getCheckoutSession("cs_good")).thenReturn(sessionAttrs("paid", 100, good.getId().toString()));
        when(webhookService.process(any())).thenReturn(Outcome.PROCESSED);

        var summary = service.reconcileAwaitingPayments();

        assertEquals(2, summary.checked());
        assertEquals(1, summary.errors());
        assertEquals(1, summary.escrowed());
    }

    @Test
    void runIsCapped() {
        List<Order> many = new ArrayList<>();
        for (int i = 0; i < PaymentReconciliationService.MAX_PER_RUN + 10; i++) many.add(awaiting("cs_" + i));
        when(orderRepository.findByStatus(OrderStatus.AWAITING_PAYMENT)).thenReturn(many);
        ObjectNode unpaid = mapper.createObjectNode();
        unpaid.putArray("payments");
        when(payMongoClient.getCheckoutSession(any())).thenReturn(unpaid);

        var summary = service.reconcileAwaitingPayments();

        assertEquals(PaymentReconciliationService.MAX_PER_RUN, summary.checked());
    }
}
