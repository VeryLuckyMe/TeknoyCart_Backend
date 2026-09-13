package com.teknoycart.services;

import com.teknoycart.models.Order;
import com.teknoycart.models.OrderAuditLog;
import com.teknoycart.models.OrderStatus;
import com.teknoycart.repositories.OrderAuditLogRepository;
import com.teknoycart.repositories.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Random;
import java.util.UUID;

@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderAuditLogRepository auditLogRepository;

    private void logAudit(Order order, UUID actorId, OrderStatus oldStatus, OrderStatus newStatus, String method) {
        OrderAuditLog log = new OrderAuditLog();
        log.setOrderId(order.getId());
        log.setActorId(actorId);
        log.setPreviousStatus(oldStatus != null ? oldStatus.name() : null);
        log.setNewStatus(newStatus.name());
        log.setMethod(method);
        auditLogRepository.save(log);
    }

    public Order getOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    public Order acceptOrder(UUID orderId, UUID actorId) {
        Order order = getOrder(orderId);
        // Accept both new (PLACED) and legacy (INQUIRY_SENT, PENDING_SELLER_ACCEPT) statuses
        if (order.getStatus() != OrderStatus.PLACED && 
            order.getStatus() != OrderStatus.INQUIRY_SENT && 
            order.getStatus() != OrderStatus.PENDING_SELLER_ACCEPT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order cannot be accepted from state: " + order.getStatus());
        }
        // Enforce: only the designated seller can accept the order
        if (order.getSellerId() == null || !order.getSellerId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the seller can accept this order");
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.ACCEPTED);
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, order.getStatus(), "MANUAL");
        return order;
    }

    public Order cancelOrder(UUID orderId, UUID actorId, String reason) {
        Order order = getOrder(orderId);
        // Enforce: only the buyer or seller can cancel the order
        if (!actorId.equals(order.getBuyerId()) && !actorId.equals(order.getSellerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the buyer or seller can cancel this order");
        }
        // Enforce: non-blank cancellation reason
        if (reason == null || reason.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cancellation reason is required");
        }
        if (order.getStatus() == OrderStatus.HANDOFF_PENDING || 
            order.getStatus() == OrderStatus.COMPLETED || 
            order.getStatus() == OrderStatus.CANCELLED ||
            order.getStatus() == OrderStatus.DISPUTED ||
            order.getStatus() == OrderStatus.REFUND_REQUESTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order cannot be cancelled from state: " + order.getStatus());
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.CANCELLED);
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, order.getStatus(), "MANUAL - Reason: " + reason.trim());
        return order;
    }

    public Order scheduleMeetup(UUID orderId, UUID actorId) {
        Order order = getOrder(orderId);
        // Enforce: only the buyer or seller can schedule/generate meetup code
        if (!actorId.equals(order.getBuyerId()) && !actorId.equals(order.getSellerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the buyer or seller can schedule this meetup");
        }
        // Accept both new (ACCEPTED, MEETUP_SCHEDULED) and legacy (APPROVED, SELLER_ACCEPTED) statuses
        if (order.getStatus() != OrderStatus.ACCEPTED && 
            order.getStatus() != OrderStatus.MEETUP_SCHEDULED && 
            order.getStatus() != OrderStatus.APPROVED && 
            order.getStatus() != OrderStatus.SELLER_ACCEPTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Meetup cannot be scheduled from state: " + order.getStatus());
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.MEETUP_SCHEDULED);
        
        // Generate 6 digit OTP
        Random random = new Random();
        int otp = 100000 + random.nextInt(900000);
        order.setHandoffOtp(String.valueOf(otp));
        
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, order.getStatus(), "MANUAL");
        return order;
    }

    public Order verifyHandoff(UUID orderId, UUID actorId, String otp) {
        Order order = getOrder(orderId);
        if (order.getStatus() != OrderStatus.MEETUP_SCHEDULED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Handoff cannot be verified from state: " + order.getStatus());
        }
        // Enforce: only the seller can verify handoff using the OTP
        if (order.getSellerId() == null || !order.getSellerId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the seller can verify handoff");
        }
        if (order.getHandoffOtp() == null || !order.getHandoffOtp().equals(otp)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OTP");
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.HANDOFF_PENDING);
        order.setSellerHandedOff(true);
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, order.getStatus(), "OTP_MATCH");
        return order;
    }

    public Order confirmReceipt(UUID orderId, UUID actorId) {
        Order order = getOrder(orderId);
        if (order.getStatus() != OrderStatus.HANDOFF_PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Receipt cannot be confirmed from state: " + order.getStatus());
        }
        // Enforce: only the buyer can confirm receipt of the item
        if (order.getBuyerId() == null || !order.getBuyerId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the buyer can confirm receipt");
        }

        OrderStatus oldStatus = order.getStatus();
        order.setBuyerConfirmedReceipt(true);
        
        if (order.isSellerHandedOff() && order.isBuyerConfirmedReceipt()) {
            order.setStatus(OrderStatus.COMPLETED);
        }
        
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, order.getStatus(), "MANUAL");
        return order;
    }

    public Order requestRefund(UUID orderId, UUID actorId, String reason, String evidence) {
        Order order = getOrder(orderId);
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund cannot be requested from state: " + order.getStatus());
        }
        // Enforce: only the buyer can request a refund
        if (order.getBuyerId() == null || !order.getBuyerId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the buyer can request refund");
        }
        // Enforce: non-blank reason
        if (reason == null || reason.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund reason is required");
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.REFUND_REQUESTED);
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, order.getStatus(), "MANUAL - Reason: " + reason.trim() + (evidence != null ? " Evidence: " + evidence.trim() : ""));
        return order;
    }
}
