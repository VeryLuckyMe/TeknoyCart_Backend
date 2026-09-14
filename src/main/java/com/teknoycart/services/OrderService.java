package com.teknoycart.services;

import com.teknoycart.models.Order;
import com.teknoycart.models.OrderAuditLog;
import com.teknoycart.models.OrderStatus;
import com.teknoycart.repositories.OrderAuditLogRepository;
import com.teknoycart.repositories.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class OrderService {

    /** OTP expires after 15 minutes */
    private static final Duration OTP_EXPIRY = Duration.ofMinutes(15);

    /** Lock out after 5 consecutive failed OTP attempts */
    private static final int MAX_OTP_ATTEMPTS = 5;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderAuditLogRepository auditLogRepository;

    @Autowired
    private OrderOtpService orderOtpService;

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

    @Transactional
    public Order acceptOrder(UUID orderId, UUID actorId) {
        Order order = getOrder(orderId);
        // Accept both new (PLACED, PAYMENT_SUBMITTED) and legacy (INQUIRY_SENT, PENDING_SELLER_ACCEPT) statuses
        if (order.getStatus() != OrderStatus.PLACED && 
            order.getStatus() != OrderStatus.PAYMENT_SUBMITTED && 
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

    @Transactional
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

        // GUARD 1: If payment was verified, hard-block unilateral cancellation.
        // Money has been verified in seller's account; must route through refund/dispute.
        if (order.getStatus() == OrderStatus.PAYMENT_VERIFIED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payment has been verified for this order. Unilateral cancellation is blocked; " +
                    "please request a refund so the return of funds is tracked and audited.");
        }

        // GUARD 2: If order is scheduled for meetup but has a verified/submitted payment reference:
        if ((order.getStatus() == OrderStatus.MEETUP_SCHEDULED || order.getStatus() == OrderStatus.NEEDS_REVIEW) &&
            order.getPaymentReference() != null && !order.getPaymentReference().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This order has payment attached (Ref: " + order.getPaymentReference() + "). " +
                    "Unilateral cancellation is blocked; please request a refund to resolve funds.");
        }

        // GUARD 3: If payment was submitted (unverified), route to REFUND_REQUESTED
        // so the obligation to verify/return funds is recorded, rather than silently disappearing as CANCELLED.
        if (order.getStatus() == OrderStatus.PAYMENT_SUBMITTED) {
            OrderStatus oldStatus = order.getStatus();
            order.setStatus(OrderStatus.REFUND_REQUESTED);
            order = orderRepository.save(order);
            String actorRole = actorId.equals(order.getSellerId()) ? "SELLER" : "BUYER";
            String auditMsg = actorRole + "_CANCELLED_AFTER_PAYMENT_SUBMITTED - Payment ref: " + 
                    (order.getPaymentReference() != null ? order.getPaymentReference() : "N/A") + 
                    ". Reason: " + reason.trim() + ". Refund verification required.";
            logAudit(order, actorId, oldStatus, OrderStatus.REFUND_REQUESTED, auditMsg);
            return order;
        }

        // No money was transferred: safe to mark as ordinary CANCELLED
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.CANCELLED);
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, order.getStatus(), "MANUAL - Reason: " + reason.trim());
        return order;
    }

    @Transactional
    public Order scheduleMeetup(UUID orderId, UUID actorId) {
        Order order = getOrder(orderId);
        // Enforce: only the buyer or seller can schedule/generate meetup code
        if (!actorId.equals(order.getBuyerId()) && !actorId.equals(order.getSellerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the buyer or seller can schedule this meetup");
        }
        // Accept both new (ACCEPTED, PAYMENT_VERIFIED, MEETUP_SCHEDULED, NEEDS_REVIEW) and legacy (APPROVED, SELLER_ACCEPTED) statuses
        if (order.getStatus() != OrderStatus.ACCEPTED && 
            order.getStatus() != OrderStatus.PAYMENT_VERIFIED && 
            order.getStatus() != OrderStatus.MEETUP_SCHEDULED && 
            order.getStatus() != OrderStatus.NEEDS_REVIEW && 
            order.getStatus() != OrderStatus.APPROVED && 
            order.getStatus() != OrderStatus.SELLER_ACCEPTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Meetup cannot be scheduled from state: " + order.getStatus());
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.MEETUP_SCHEDULED);
        
        // Generate cryptographically secure 6-digit OTP
        int otp = 100000 + SECURE_RANDOM.nextInt(900000);
        order.setHandoffOtp(String.valueOf(otp));
        order.setOtpCreatedAt(Instant.now());
        order.setOtpFailedAttempts(0); // Reset failed attempts on new OTP
        
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, order.getStatus(), "MANUAL");
        return order;
    }

    @Transactional
    public Order verifyHandoff(UUID orderId, UUID actorId, String otp) {
        Order order = getOrder(orderId);
        if (order.getStatus() != OrderStatus.MEETUP_SCHEDULED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Handoff cannot be verified from state: " + order.getStatus());
        }
        // Enforce: only the seller can verify handoff using the OTP
        if (order.getSellerId() == null || !order.getSellerId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the seller can verify handoff");
        }

        // Check if OTP has been locked out due to too many failed attempts
        if (order.getOtpFailedAttempts() >= MAX_OTP_ATTEMPTS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "OTP verification locked after " + MAX_OTP_ATTEMPTS + " failed attempts. " +
                    "Please reschedule the meetup to generate a new code.");
        }

        // Check if OTP has expired
        if (order.getOtpCreatedAt() == null || 
            Instant.now().isAfter(order.getOtpCreatedAt().plus(OTP_EXPIRY))) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "OTP has expired. Please reschedule the meetup to generate a new code.");
        }

        // Check OTP value
        if (order.getHandoffOtp() == null || !order.getHandoffOtp().equals(otp)) {
            // Record failed attempt in an autonomous transaction (Propagation.REQUIRES_NEW)
            // so it commits even when ResponseStatusException rolls back this transaction
            int attempts = orderOtpService.recordFailedAttempt(order.getId(), actorId, MAX_OTP_ATTEMPTS);
            int remaining = MAX_OTP_ATTEMPTS - attempts;
            if (remaining <= 0) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "OTP verification locked after " + MAX_OTP_ATTEMPTS + " failed attempts. " +
                        "Please reschedule the meetup to generate a new code.");
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid OTP. " + remaining + " attempt(s) remaining.");
        }

        // OTP matched — transition order and clear OTP data
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.HANDOFF_PENDING);
        order.setSellerHandedOff(true);
        // Clear OTP after successful verification so it can't be replayed
        order.setHandoffOtp(null);
        order.setOtpCreatedAt(null);
        order.setOtpFailedAttempts(0);
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, order.getStatus(), "OTP_MATCH");
        return order;
    }

    @Transactional
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

    @Transactional
    public Order requestRefund(UUID orderId, UUID actorId, String reason, String evidence) {
        Order order = getOrder(orderId);
        // Allow refund requests from COMPLETED, or from any state where payment was attached/verified
        boolean hasPayment = order.getStatus() == OrderStatus.PAYMENT_VERIFIED ||
                             order.getStatus() == OrderStatus.PAYMENT_SUBMITTED ||
                             (order.getPaymentReference() != null && !order.getPaymentReference().trim().isEmpty());

        if (order.getStatus() != OrderStatus.COMPLETED && !hasPayment) {
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
        // Enforce: non-blank evidence for dispute adjudication
        if (evidence == null || evidence.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund evidence is required");
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.REFUND_REQUESTED);
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, order.getStatus(), "MANUAL - Reason: " + reason.trim() + (evidence != null ? " Evidence: " + evidence.trim() : ""));
        return order;
    }

    @Transactional
    public Order confirmRefund(UUID orderId, UUID actorId, String refundReference) {
        Order order = getOrder(orderId);
        // Only the seller (returning funds) or buyer (confirming receipt of funds) can act
        boolean isSeller = actorId.equals(order.getSellerId());
        boolean isBuyer = actorId.equals(order.getBuyerId());
        if (!isSeller && !isBuyer) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the buyer or seller can resolve a refund");
        }
        if (order.getStatus() != OrderStatus.REFUND_REQUESTED && order.getStatus() != OrderStatus.DISPUTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not in a refundable status (current: " + order.getStatus() + ")");
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.CANCELLED);
        order = orderRepository.save(order);
        String refInfo = (refundReference != null && !refundReference.trim().isEmpty()) ? " - Refund Ref: " + refundReference.trim() : "";
        logAudit(order, actorId, oldStatus, OrderStatus.CANCELLED, (isSeller ? "SELLER_REFUND_ISSUED" : "BUYER_REFUND_CONFIRMED") + refInfo);
        return order;
    }

    @Transactional
    public Order submitPayment(UUID orderId, UUID actorId, String reference, String proofUrl) {
        Order order = getOrder(orderId);
        // Enforce: only the buyer can submit payment details
        if (order.getBuyerId() == null || !order.getBuyerId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the buyer can submit payment reference");
        }
        if (reference == null || reference.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment reference is required");
        }

        // Allow submitting payment from initial order states
        if (order.getStatus() != OrderStatus.PLACED &&
            order.getStatus() != OrderStatus.ACCEPTED &&
            order.getStatus() != OrderStatus.INQUIRY_SENT &&
            order.getStatus() != OrderStatus.PENDING_SELLER_ACCEPT &&
            order.getStatus() != OrderStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment cannot be submitted from state: " + order.getStatus());
        }

        OrderStatus oldStatus = order.getStatus();
        order.setPaymentReference(reference.trim());
        if (proofUrl != null && !proofUrl.trim().isEmpty()) {
            order.setPaymentProofUrl(proofUrl.trim());
        }
        order.setStatus(OrderStatus.PAYMENT_SUBMITTED);
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, order.getStatus(), "PAYMENT_SUBMITTED - Ref: " + reference.trim());
        return order;
    }

    @Transactional
    public Order verifyPayment(UUID orderId, UUID actorId) {
        Order order = getOrder(orderId);
        // Enforce: only the seller can verify payment
        if (order.getSellerId() == null || !order.getSellerId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the seller can verify payment");
        }
        if (order.getStatus() != OrderStatus.PAYMENT_SUBMITTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment cannot be verified from state: " + order.getStatus());
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.PAYMENT_VERIFIED);
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, order.getStatus(), "PAYMENT_VERIFIED");
        return order;
    }
}
