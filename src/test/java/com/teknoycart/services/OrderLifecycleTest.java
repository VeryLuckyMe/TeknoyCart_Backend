package com.teknoycart.services;

import com.teknoycart.models.Order;
import com.teknoycart.models.OrderStatus;
import com.teknoycart.models.User;
import com.teknoycart.repositories.OrderAuditLogRepository;
import com.teknoycart.repositories.OrderRepository;
import com.teknoycart.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import({OrderService.class, OrderOtpService.class})
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:lifecycledb;MODE=PostgreSQL;TIME ZONE=UTC",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false",
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"
})
public class OrderLifecycleTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderAuditLogRepository auditLogRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.teknoycart.services.EmailService emailService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private UUID buyerId;
    private UUID sellerId;
    private UUID adminId;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        orderRepository.deleteAll();
        userRepository.deleteAll();

        buyerId = UUID.randomUUID();
        sellerId = UUID.randomUUID();

        User admin = new User();
        admin.setEmail("admin.test@cit.edu");
        admin.setFullName("Admin Tester");
        admin.setPasswordHash("hashedpw");
        admin.setRole("ADMIN");
        admin.setVerified(true);
        admin = userRepository.saveAndFlush(admin);
        adminId = admin.getUserId();
    }

    private Order createOrder(OrderStatus status) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setBuyerId(buyerId);
        order.setSellerId(sellerId);
        order.setStatus(status);
        return orderRepository.saveAndFlush(order);
    }

    @Test
    @DisplayName("COM Flow: Schedule Meetup -> Handoff OTP -> Confirm Receipt -> Completed")
    void testCompleteComOrderFlow() {
        Order order = createOrder(OrderStatus.ACCEPTED);

        // Schedule meetup
        order = orderService.scheduleMeetup(order.getId(), sellerId);
        assertEquals(OrderStatus.MEETUP_SCHEDULED, order.getStatus());
        assertNotNull(order.getHandoffOtp());

        String otp = order.getHandoffOtp();

        // Verify handoff
        order = orderService.verifyHandoff(order.getId(), sellerId, otp);
        assertEquals(OrderStatus.HANDOFF_PENDING, order.getStatus());
        assertTrue(order.isSellerHandedOff());
        assertNotNull(order.getHandoffCompletedAt());
        assertNull(order.getHandoffOtp());

        // Confirm receipt
        order = orderService.confirmReceipt(order.getId(), buyerId);
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertTrue(order.isBuyerConfirmedReceipt());
    }

    @Test
    @DisplayName("No-Show: COM cancels cleanly, GCash routes to DISPUTED")
    void testNoShowHandling() {
        // 1. COM order
        Order comOrder = createOrder(OrderStatus.ACCEPTED);
        comOrder = orderService.scheduleMeetup(comOrder.getId(), sellerId);
        comOrder = orderService.reportNoShow(comOrder.getId(), buyerId, "Seller never arrived");
        assertEquals(OrderStatus.CANCELLED, comOrder.getStatus());

        // 2. GCash order (verified payment)
        Order gcashOrder = createOrder(OrderStatus.PAYMENT_VERIFIED);
        gcashOrder.setPaymentReference("GCASH-998877");
        gcashOrder = orderRepository.saveAndFlush(gcashOrder);
        gcashOrder = orderService.scheduleMeetup(gcashOrder.getId(), sellerId);
        gcashOrder = orderService.reportNoShow(gcashOrder.getId(), buyerId, "Seller ran away with money");
        assertEquals(OrderStatus.DISPUTED, gcashOrder.getStatus());
        assertTrue(gcashOrder.getDisputeReason().contains("NO_SHOW"));
    }

    @Test
    @DisplayName("Return Meetup Flow: Request Return -> Approve -> Verify Return OTP -> Terminal State")
    void testReturnMeetupFlow() {
        Order order = createOrder(OrderStatus.HANDOFF_PENDING);
        order.setHandoffCompletedAt(Instant.now());
        order = orderRepository.saveAndFlush(order);

        // Buyer requests return
        order = orderService.requestRefund(order.getId(), buyerId, "Damaged textbook binding", "https://cit.edu/evidence.jpg");
        assertEquals(OrderStatus.RETURN_REQUESTED, order.getStatus());

        // Seller approves return
        order = orderService.approveReturn(order.getId(), sellerId);
        assertEquals(OrderStatus.RETURN_APPROVED, order.getStatus());
        assertNotNull(order.getReturnOtp());

        String returnOtp = order.getReturnOtp();

        // Seller verifies return handoff at campus landmark (COM)
        order = orderService.verifyReturnHandoff(order.getId(), sellerId, returnOtp, null);
        assertEquals(OrderStatus.RETURN_COMPLETED, order.getStatus());
        assertNotNull(order.getReturnCompletedAt());
        assertNull(order.getReturnOtp());
    }

    @Test
    @DisplayName("Admin Dispute Resolution: Ruling RELEASE_TO_SELLER vs REFUND_BUYER")
    void testAdminDisputeResolution() {
        Order order = createOrder(OrderStatus.DISPUTED);
        order.setPaymentReference("GCASH-112233");
        order = orderRepository.saveAndFlush(order);

        // Admin rules to refund buyer
        order = orderService.resolveDispute(order.getId(), adminId, "REFUND_BUYER", "Clear evidence of counterfeit goods");
        assertEquals(OrderStatus.REFUND_COMPLETED, order.getStatus());
        assertEquals("REFUND_BUYER", order.getDisputeRuling());
    }
}
