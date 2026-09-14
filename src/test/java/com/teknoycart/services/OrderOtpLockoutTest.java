package com.teknoycart.services;

import com.teknoycart.models.Order;
import com.teknoycart.models.OrderAuditLog;
import com.teknoycart.models.OrderStatus;
import com.teknoycart.repositories.OrderAuditLogRepository;
import com.teknoycart.repositories.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import({OrderService.class, OrderOtpService.class})
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;TIME ZONE=UTC",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false",
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"
})
public class OrderOtpLockoutTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderAuditLogRepository auditLogRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.teknoycart.services.EmailService emailService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private UUID orderId;
    private UUID sellerId;
    private UUID buyerId;


    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        orderRepository.deleteAll();

        sellerId = UUID.randomUUID();
        buyerId = UUID.randomUUID();
        orderId = UUID.randomUUID();

        Order order = new Order();
        order.setId(orderId);
        order.setSellerId(sellerId);
        order.setBuyerId(buyerId);
        order.setStatus(OrderStatus.MEETUP_SCHEDULED);
        order.setHandoffOtp("123456");
        order.setOtpCreatedAt(Instant.now().plus(java.time.Duration.ofDays(1))); // Buffer across timezones in H2
        order.setOtpFailedAttempts(0);
        orderRepository.saveAndFlush(order);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Verify that 5 failed OTP attempts lock out and the 6th attempt is blocked with 429")
    void testOtpFailedAttemptsPersistAcrossRollbacksAndLockoutOnSixthAttempt() {
        String wrongOtp = "999999";

        // Attempts 1 to 4: Expect 400 BAD_REQUEST with decreasing remaining attempts
        for (int i = 1; i <= 4; i++) {
            final int attemptNum = i;
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> {
                orderService.verifyHandoff(orderId, sellerId, wrongOtp);
            }, "Attempt " + attemptNum + " should throw ResponseStatusException");

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode(),
                    "Attempt " + attemptNum + " should fail with 400 BAD_REQUEST");
            int expectedRemaining = 5 - attemptNum;
            assertTrue(ex.getReason().contains(expectedRemaining + " attempt(s) remaining"),
                    "Error reason should state " + expectedRemaining + " remaining");

            // CRITICAL CHECK: Verify that failed attempt count WAS COMMITTED to DB
            Order dbOrder = orderRepository.findById(orderId).orElseThrow();
            assertEquals(attemptNum, dbOrder.getOtpFailedAttempts(),
                    "DB should have persisted " + attemptNum + " failed attempt(s) despite exception rollback");
        }

        // Attempt 5: Reaches MAX_OTP_ATTEMPTS (5) -> Expect 429 TOO_MANY_REQUESTS and OTP_LOCKOUT audit log
        ResponseStatusException ex5 = assertThrows(ResponseStatusException.class, () -> {
            orderService.verifyHandoff(orderId, sellerId, wrongOtp);
        }, "Attempt 5 should trigger lockout");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex5.getStatusCode(),
                "Attempt 5 should return 429 TOO_MANY_REQUESTS");
        assertTrue(ex5.getReason().contains("locked after 5 failed attempts"),
                "Reason should mention locked after 5 failed attempts");

        Order dbOrderAfter5 = orderRepository.findById(orderId).orElseThrow();
        assertEquals(5, dbOrderAfter5.getOtpFailedAttempts(),
                "DB should persist 5 failed attempts");

        // Verify audit log has OTP_LOCKOUT
        List<OrderAuditLog> logs = auditLogRepository.findAll();
        boolean hasLockoutLog = logs.stream().anyMatch(l -> "OTP_LOCKOUT".equals(l.getMethod()));
        assertTrue(hasLockoutLog, "An OTP_LOCKOUT audit log entry must be persisted");

        // Attempt 6: Any OTP attempt (even the correct OTP "123456") must be IMMEDIATELY blocked with 429
        ResponseStatusException ex6Wrong = assertThrows(ResponseStatusException.class, () -> {
            orderService.verifyHandoff(orderId, sellerId, wrongOtp);
        }, "Attempt 6 with wrong OTP must be blocked");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex6Wrong.getStatusCode(),
                "Attempt 6 must return 429 TOO_MANY_REQUESTS");

        ResponseStatusException ex6Correct = assertThrows(ResponseStatusException.class, () -> {
            orderService.verifyHandoff(orderId, sellerId, "123456");
        }, "Attempt 6 with correct OTP must also be blocked due to existing lockout");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex6Correct.getStatusCode(),
                "Attempt 6 with correct OTP must also return 429 TOO_MANY_REQUESTS");
    }
}
