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

    public Order acceptOrder(UUID orderId, UUID sellerId) {
        Order order = getOrder(orderId);
        // Accept both new (PLACED) and legacy (INQUIRY_SENT, PENDING_SELLER_ACCEPT) statuses
        if (order.getStatus() != OrderStatus.PLACED && 
            order.getStatus() != OrderStatus.INQUIRY_SENT && 
            order.getStatus() != OrderStatus.PENDING_SELLER_ACCEPT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order cannot be accepted from state: " + order.getStatus());
        }
        if (order.getSellerId() != null && !order.getSellerId().equals(sellerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the seller can accept the order. Order seller: " + order.getSellerId() + ", Actor: " + sellerId);
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.ACCEPTED);
        order = orderRepository.save(order);
        logAudit(order, sellerId, oldStatus, order.getStatus(), "MANUAL");
        return order;
    }

    public Order cancelOrder(UUID orderId, UUID actorId, String reason) {
        Order order = getOrder(orderId);
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
        logAudit(order, actorId, oldStatus, order.getStatus(), "MANUAL - Reason: " + reason);
        return order;
    }

    public Order scheduleMeetup(UUID orderId, UUID actorId) {
        Order order = getOrder(orderId);
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

    public Order verifyHandoff(UUID orderId, UUID sellerId, String otp) {
        Order order = getOrder(orderId);
        if (order.getStatus() != OrderStatus.MEETUP_SCHEDULED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Handoff cannot be verified from state: " + order.getStatus());
        }
        if (!order.getSellerId().equals(sellerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the seller can verify handoff");
        }
        if (order.getHandoffOtp() == null || !order.getHandoffOtp().equals(otp)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OTP");
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.HANDOFF_PENDING);
        order.setSellerHandedOff(true);
        order = orderRepository.save(order);
        logAudit(order, sellerId, oldStatus, order.getStatus(), "OTP_MATCH");
        return order;
    }

    public Order confirmReceipt(UUID orderId, UUID buyerId) {
        Order order = getOrder(orderId);
        if (order.getStatus() != OrderStatus.HANDOFF_PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Receipt cannot be confirmed from state: " + order.getStatus());
        }
        if (!order.getBuyerId().equals(buyerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the buyer can confirm receipt");
        }

        OrderStatus oldStatus = order.getStatus();
        order.setBuyerConfirmedReceipt(true);
        
        if (order.isSellerHandedOff() && order.isBuyerConfirmedReceipt()) {
            order.setStatus(OrderStatus.COMPLETED);
        }
        
        order = orderRepository.save(order);
        logAudit(order, buyerId, oldStatus, order.getStatus(), "MANUAL");
        return order;
    }

    public Order requestRefund(UUID orderId, UUID buyerId, String reason, String evidence) {
        Order order = getOrder(orderId);
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund cannot be requested from state: " + order.getStatus());
        }
        if (!order.getBuyerId().equals(buyerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the buyer can request refund");
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.REFUND_REQUESTED);
        order = orderRepository.save(order);
        logAudit(order, buyerId, oldStatus, order.getStatus(), "MANUAL - Reason: " + reason + " Evidence: " + evidence);
        return order;
    }
}
