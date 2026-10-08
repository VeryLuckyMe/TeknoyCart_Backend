package com.teknoycart.services;

import com.teknoycart.models.Order;
import com.teknoycart.models.OrderStatus;
import com.teknoycart.repositories.OrderAuditLogRepository;
import com.teknoycart.repositories.OrderRepository;
import com.teknoycart.repositories.PaymentEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@Import({OrderService.class, OrderOtpService.class})
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:paymongodb;MODE=PostgreSQL;TIME ZONE=UTC",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false",
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
    "paymongo.checkout-ttl-minutes=30"
})
public class PayMongoPaymentServiceTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderAuditLogRepository auditLogRepository;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @MockBean
    private EmailService emailService;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private PayMongoClient payMongoClient;

    @MockBean
    private PayoutService payoutService;

    private UUID buyerId;
    private UUID sellerId;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        orderRepository.deleteAll();
        paymentEventRepository.deleteAll();

        buyerId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
    }

    private Order createOrder(OrderStatus status, String paymentMethod, BigDecimal totalAmount) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setBuyerId(buyerId);
        order.setSellerId(sellerId);
        order.setStatus(status);
        order.setPaymentMethod(paymentMethod);
        order.setTotalAmount(totalAmount);
        return orderRepository.save(order);
    }

    @Test
    @DisplayName("initiateGcashPayment: Happy path transitions ACCEPTED to AWAITING_PAYMENT and stores session details")
    void testInitiatePaymentHappyPath() {
        Order order = createOrder(OrderStatus.ACCEPTED, "GCASH", new BigDecimal("150.00"));
        String fakeSessionId = "cs_test_123456";
        String fakeCheckoutUrl = "https://checkout.paymongo.com/cs_test_123456";

        when(payMongoClient.createGcashCheckoutSession(eq(15000L), eq(order.getId().toString()), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new PayMongoClient.CheckoutSessionResult(fakeSessionId, fakeCheckoutUrl));

        OrderService.InitiatePaymentResult result = orderService.initiateGcashPayment(
                order.getId(), buyerId, "https://app/success", "https://app/cancel");

        assertNotNull(result);
        assertEquals(fakeCheckoutUrl, result.checkoutUrl());
        assertNotNull(result.expiresAt());

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.AWAITING_PAYMENT, reloaded.getStatus());
        assertEquals(fakeSessionId, reloaded.getPaymongoCheckoutSessionId());
        assertEquals(fakeCheckoutUrl, reloaded.getPaymongoCheckoutUrl());
        assertEquals(15000L, reloaded.getAmountCentavos());
        assertNotNull(reloaded.getPaymentExpiresAt());

        verify(payMongoClient, times(1)).createGcashCheckoutSession(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("initiateGcashPayment: Idempotent double tap returns existing URL without calling PayMongo client")
    void testInitiatePaymentIdempotent() {
        Order order = createOrder(OrderStatus.AWAITING_PAYMENT, "GCASH", new BigDecimal("150.00"));
        String existingUrl = "https://checkout.paymongo.com/existing";
        order.setPaymongoCheckoutUrl(existingUrl);
        order.setPaymentExpiresAt(Instant.now().plusSeconds(1800));
        order = orderRepository.save(order);

        OrderService.InitiatePaymentResult result = orderService.initiateGcashPayment(
                order.getId(), buyerId, "https://app/success", "https://app/cancel");

        assertEquals(existingUrl, result.checkoutUrl());
        verify(payMongoClient, never()).createGcashCheckoutSession(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("initiateGcashPayment: Non-buyer actor is rejected with 403 Forbidden")
    void testInitiatePaymentForbiddenForNonBuyer() {
        Order order = createOrder(OrderStatus.ACCEPTED, "GCASH", new BigDecimal("100.00"));
        UUID strangerId = UUID.randomUUID();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> orderService.initiateGcashPayment(order.getId(), strangerId, "https://app/success", "https://app/cancel"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(payMongoClient, never()).createGcashCheckoutSession(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("initiateGcashPayment: Non-GCASH payment method is rejected with 409 Conflict")
    void testInitiatePaymentRejectsCashOnPickup() {
        Order order = createOrder(OrderStatus.ACCEPTED, "CASH_ON_PICKUP", new BigDecimal("100.00"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> orderService.initiateGcashPayment(order.getId(), buyerId, "https://app/success", "https://app/cancel"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(payMongoClient, never()).createGcashCheckoutSession(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("markEscrowed: Transitions AWAITING_PAYMENT order to ESCROWED")
    void testMarkEscrowedHappyPath() {
        Order order = createOrder(OrderStatus.AWAITING_PAYMENT, "GCASH", new BigDecimal("200.00"));
        order.setPaymongoCheckoutSessionId("cs_test_escrow");
        order = orderRepository.save(order);

        orderService.markEscrowed(order.getId(), "cs_test_escrow");

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.ESCROWED, reloaded.getStatus());
    }

    @Test
    @DisplayName("markEscrowed: Duplicate webhook delivery is a no-op")
    void testMarkEscrowedDuplicateIsNoOp() {
        Order initial = createOrder(OrderStatus.ESCROWED, "GCASH", new BigDecimal("200.00"));
        initial.setPaymongoCheckoutSessionId("cs_test_escrow");
        Order order = orderRepository.save(initial);
        final UUID orderId = order.getId();

        assertDoesNotThrow(() -> orderService.markEscrowed(orderId, "cs_test_escrow"));

        Order reloaded = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.ESCROWED, reloaded.getStatus());
    }

    @Test
    @DisplayName("markEscrowed: Payment received for CANCELLED order logs warning and remains unchanged for manual admin refund")
    void testMarkEscrowedOnCancelledOrderDoesNotSetEscrowed() {
        Order order = createOrder(OrderStatus.CANCELLED, "GCASH", new BigDecimal("200.00"));
        order.setPaymongoCheckoutSessionId("cs_test_cancelled");
        order = orderRepository.save(order);

        orderService.markEscrowed(order.getId(), "cs_test_cancelled");

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, reloaded.getStatus());
    }

    @Test
    @DisplayName("releasePayout: COMPLETED order triggers payoutService and marks payoutReleased")
    void testReleasePayoutHappyPath() {
        Order order = createOrder(OrderStatus.COMPLETED, "GCASH", new BigDecimal("300.00"));
        order.setAmountCentavos(30000L);
        order.setPayoutReleased(false);
        order = orderRepository.save(order);

        orderService.releasePayout(order);

        verify(payoutService, times(1)).release(any(Order.class));
    }

    @Test
    @DisplayName("releasePayout: Idempotent — does not call payoutService if payoutReleased is true")
    void testReleasePayoutIdempotent() {
        Order order = createOrder(OrderStatus.COMPLETED, "GCASH", new BigDecimal("300.00"));
        order.setAmountCentavos(30000L);
        order.setPayoutReleased(true);
        order = orderRepository.save(order);

        orderService.releasePayout(order);

        verify(payoutService, never()).release(any(Order.class));
    }

    @Test
    @DisplayName("releasePayout: Cash order (amountCentavos is null) does not trigger payout")
    void testReleasePayoutCashOrderIgnored() {
        Order order = createOrder(OrderStatus.COMPLETED, "CASH_ON_PICKUP", new BigDecimal("300.00"));
        order.setAmountCentavos(null);
        order = orderRepository.save(order);

        orderService.releasePayout(order);

        verify(payoutService, never()).release(any(Order.class));
    }

    @Test
    @DisplayName("expireAwaitingPayments: Sweeps past-expiry AWAITING_PAYMENT orders back to ACCEPTED")
    void testExpireAwaitingPaymentsRevertsToAccepted() {
        Order order = createOrder(OrderStatus.AWAITING_PAYMENT, "GCASH", new BigDecimal("100.00"));
        order.setPaymongoCheckoutSessionId("cs_expired");
        order.setPaymongoCheckoutUrl("https://checkout.url");
        order.setPaymentExpiresAt(Instant.now().minusSeconds(10));
        order = orderRepository.save(order);

        orderService.expireAwaitingPayments();

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.ACCEPTED, reloaded.getStatus());
        assertNull(reloaded.getPaymongoCheckoutSessionId());
        assertNull(reloaded.getPaymongoCheckoutUrl());
        assertNull(reloaded.getPaymentExpiresAt());
    }
}
