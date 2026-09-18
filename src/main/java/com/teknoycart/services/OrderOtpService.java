package com.teknoycart.services;

import com.teknoycart.models.Order;
import com.teknoycart.models.OrderAuditLog;
import com.teknoycart.repositories.OrderAuditLogRepository;
import com.teknoycart.repositories.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * Service managing OTP lifecycle operations that require independent transaction boundaries.
 */
@Service
public class OrderOtpService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderAuditLogRepository auditLogRepository;

    /**
     * Records a failed OTP attempt in an autonomous transaction (Propagation.REQUIRES_NEW).
     * This guarantees the attempt count and lockout audit log are persisted even when the
     * calling method rolls back due to throwing a ResponseStatusException.
     *
     * @param orderId the order ID
     * @param actorId the actor attempting verification
     * @param maxAttempts the lockout threshold
     * @return the updated failed attempt count
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailedAttempt(UUID orderId, UUID actorId, int maxAttempts) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        int newAttempts = order.getOtpFailedAttempts() + 1;
        order.setOtpFailedAttempts(newAttempts);
        orderRepository.saveAndFlush(order);

        if (newAttempts >= maxAttempts) {
            OrderAuditLog auditLog = new OrderAuditLog();
            auditLog.setOrderId(order.getId());
            auditLog.setActorId(actorId);
            auditLog.setPreviousStatus(order.getStatus() != null ? order.getStatus().name() : null);
            auditLog.setNewStatus(order.getStatus() != null ? order.getStatus().name() : null);
            auditLog.setMethod("OTP_LOCKOUT");
            auditLog.setCreatedAt(Instant.now());
            auditLogRepository.saveAndFlush(auditLog);
        }

        return newAttempts;
    }

    /**
     * Records a failed return OTP attempt in an autonomous transaction (Propagation.REQUIRES_NEW).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailedReturnAttempt(UUID orderId, UUID actorId, int maxAttempts) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        int newAttempts = order.getReturnOtpFailedAttempts() + 1;
        order.setReturnOtpFailedAttempts(newAttempts);
        orderRepository.saveAndFlush(order);

        if (newAttempts >= maxAttempts) {
            OrderAuditLog auditLog = new OrderAuditLog();
            auditLog.setOrderId(order.getId());
            auditLog.setActorId(actorId);
            auditLog.setPreviousStatus(order.getStatus() != null ? order.getStatus().name() : null);
            auditLog.setNewStatus(order.getStatus() != null ? order.getStatus().name() : null);
            auditLog.setMethod("RETURN_OTP_LOCKOUT");
            auditLog.setCreatedAt(Instant.now());
            auditLogRepository.saveAndFlush(auditLog);
        }

        return newAttempts;
    }
}
