package com.teknoycart.services;

import com.teknoycart.models.Order;
import com.teknoycart.models.OrderAuditLog;
import com.teknoycart.models.OrderStatus;
import com.teknoycart.models.User;
import com.teknoycart.repositories.OrderAuditLogRepository;
import com.teknoycart.repositories.OrderRepository;
import com.teknoycart.repositories.UserRepository;
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

    /** 24-Hour TeknoyCart Guarantee Inspection Window */
    private static final Duration GUARANTEE_INSPECTION_WINDOW = Duration.ofHours(24);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderAuditLogRepository auditLogRepository;

    @Autowired
    private OrderOtpService orderOtpService;

    @Autowired
    private UserRepository userRepository;

    private void logAudit(Order order, UUID actorId, OrderStatus oldStatus, OrderStatus newStatus, String method) {
        OrderAuditLog log = new OrderAuditLog();
        log.setOrderId(order.getId());
        log.setActorId(actorId);
        log.setPreviousStatus(oldStatus != null ? oldStatus.name() : null);
        log.setNewStatus(newStatus != null ? newStatus.name() : null);
        log.setMethod(method);
        auditLogRepository.save(log);
    }

    private boolean isOrderPaymentAttached(Order order) {
        return order.getStatus() == OrderStatus.PAYMENT_VERIFIED ||
                order.getStatus() == OrderStatus.PAYMENT_SUBMITTED ||
                (order.getPaymentReference() != null && !order.getPaymentReference().trim().isEmpty());
    }

    public Order getOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    @Transactional
    public Order acceptOrder(UUID orderId, UUID actorId) {
        Order order = getOrder(orderId);
        if (order.getStatus() != OrderStatus.PLACED &&
                order.getStatus() != OrderStatus.PAYMENT_SUBMITTED &&
                order.getStatus() != OrderStatus.INQUIRY_SENT &&
                order.getStatus() != OrderStatus.PENDING_SELLER_ACCEPT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order cannot be accepted from state: " + order.getStatus());
        }
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
        if (!actorId.equals(order.getBuyerId()) && !actorId.equals(order.getSellerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the buyer or seller can cancel this order");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cancellation reason is required");
        }
        if (order.getStatus() == OrderStatus.HANDOFF_PENDING ||
                order.getStatus() == OrderStatus.COMPLETED ||
                order.getStatus() == OrderStatus.CANCELLED ||
                order.getStatus() == OrderStatus.DISPUTED ||
                order.getStatus() == OrderStatus.REFUND_REQUESTED ||
                order.getStatus() == OrderStatus.RETURN_REQUESTED ||
                order.getStatus() == OrderStatus.RETURN_APPROVED ||
                order.getStatus() == OrderStatus.RETURN_COMPLETED ||
                order.getStatus() == OrderStatus.REFUND_COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order cannot be cancelled from state: " + order.getStatus());
        }

        // If payment was verified, hard-block unilateral cancellation
        if (order.getStatus() == OrderStatus.PAYMENT_VERIFIED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payment has been verified for this order. Unilateral cancellation is blocked; " +
                            "please request a refund so the return of funds is tracked and audited.");
        }

        // If order has verified/submitted payment in MEETUP_SCHEDULED or NEEDS_REVIEW:
        if ((order.getStatus() == OrderStatus.MEETUP_SCHEDULED || order.getStatus() == OrderStatus.NEEDS_REVIEW) &&
                isOrderPaymentAttached(order)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This order has payment attached. Unilateral cancellation is blocked; " +
                            "please request a refund or dispute to resolve funds.");
        }

        // If payment was submitted (unverified), route to REFUND_REQUESTED
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

        // Cash on Meetup / No money transferred: safe to mark as CANCELLED
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.CANCELLED);
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, order.getStatus(), "MANUAL - Reason: " + reason.trim());
        return order;
    }

    @Transactional
    public Order scheduleMeetup(UUID orderId, UUID actorId) {
        Order order = getOrder(orderId);
        if (!actorId.equals(order.getBuyerId()) && !actorId.equals(order.getSellerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the buyer or seller can schedule this meetup");
        }
        if (order.getStatus() != OrderStatus.ACCEPTED &&
                order.getStatus() != OrderStatus.PAYMENT_VERIFIED &&
                order.getStatus() != OrderStatus.MEETUP_SCHEDULED &&
                order.getStatus() != OrderStatus.NEEDS_REVIEW &&
                order.getStatus() != OrderStatus.APPROVED &&
                order.getStatus() != OrderStatus.SELLER_ACCEPTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Meetup cannot be scheduled from state: " + order.getStatus());
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
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Handoff cannot be verified from state: " + order.getStatus());
        }
        if (order.getSellerId() == null || !order.getSellerId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the seller can verify handoff");
        }

        if (order.getOtpFailedAttempts() >= MAX_OTP_ATTEMPTS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "OTP verification locked after " + MAX_OTP_ATTEMPTS + " failed attempts. " +
                            "Please reschedule the meetup to generate a new code.");
        }

        if (order.getOtpCreatedAt() == null ||
                Instant.now().isAfter(order.getOtpCreatedAt().plus(OTP_EXPIRY))) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "OTP has expired. Please reschedule the meetup to generate a new code.");
        }

        if (order.getHandoffOtp() == null || !order.getHandoffOtp().equals(otp)) {
            int attempts = orderOtpService.recordFailedAttempt(order.getId(), actorId, MAX_OTP_ATTEMPTS);
            int remaining = MAX_OTP_ATTEMPTS - attempts;
            if (remaining <= 0) {
                // Lockout triggered: transition order to NEEDS_REVIEW
                order.setStatus(OrderStatus.NEEDS_REVIEW);
                order.setHandoffOtp(null);
                order.setOtpCreatedAt(null);
                orderRepository.save(order);
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "OTP verification locked after " + MAX_OTP_ATTEMPTS + " failed attempts. " +
                                "Please reschedule the meetup to generate a new code.");
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid OTP. " + remaining + " attempt(s) remaining.");
        }

        // OTP matched — transition order to HANDOFF_PENDING and start 24h guarantee clock
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.HANDOFF_PENDING);
        order.setSellerHandedOff(true);
        order.setHandoffCompletedAt(Instant.now());
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
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Receipt cannot be confirmed from state: " + order.getStatus());
        }
        if (order.getBuyerId() == null || !order.getBuyerId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the buyer can confirm receipt");
        }

        OrderStatus oldStatus = order.getStatus();
        order.setBuyerConfirmedReceipt(true);
        order.setStatus(OrderStatus.COMPLETED);

        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, order.getStatus(), "MANUAL");
        return order;
    }

    @Transactional
    public Order reportNoShow(UUID orderId, UUID actorId, String reason) {
        Order order = getOrder(orderId);
        if (!actorId.equals(order.getBuyerId()) && !actorId.equals(order.getSellerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only buyer or seller can report a no-show");
        }
        if (order.getStatus() != OrderStatus.MEETUP_SCHEDULED && order.getStatus() != OrderStatus.NEEDS_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No-show can only be reported from MEETUP_SCHEDULED or NEEDS_REVIEW (current: " + order.getStatus() + ")");
        }

        String note = (reason != null && !reason.trim().isEmpty()) ? reason.trim() : "Party did not appear at landmark";
        OrderStatus oldStatus = order.getStatus();

        // If payment was verified, route to DISPUTED to mediate funds
        if (isOrderPaymentAttached(order)) {
            order.setStatus(OrderStatus.DISPUTED);
            order.setDisputeReason("NO_SHOW: " + note);
            order = orderRepository.save(order);
            logAudit(order, actorId, oldStatus, OrderStatus.DISPUTED, "NO_SHOW_REPORTED - Dispute opened: " + note);
        } else {
            // Cash on Meetup: no funds at risk, cancel cleanly
            order.setStatus(OrderStatus.CANCELLED);
            order = orderRepository.save(order);
            logAudit(order, actorId, oldStatus, OrderStatus.CANCELLED, "NO_SHOW_REPORTED - Order cancelled: " + note);
        }
        return order;
    }

    @Transactional
    public Order requestRefund(UUID orderId, UUID actorId, String reason, String evidence) {
        Order order = getOrder(orderId);
        if (order.getBuyerId() == null || !order.getBuyerId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the buyer can request return/refund");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund reason is required");
        }
        if (evidence == null || evidence.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund evidence is required");
        }

        // 1. Post-delivery return claim during 24-hour guarantee inspection window
        if (order.getStatus() == OrderStatus.HANDOFF_PENDING || order.getStatus() == OrderStatus.COMPLETED) {
            Instant handoffTime = order.getHandoffCompletedAt();
            if (handoffTime == null) {
                OrderAuditLog latestLog = auditLogRepository.findTopByOrderIdOrderByCreatedAtDesc(order.getId());
                if (latestLog != null) handoffTime = latestLog.getCreatedAt();
            }

            if (handoffTime != null && Instant.now().isAfter(handoffTime.plus(GUARANTEE_INSPECTION_WINDOW))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "24-Hour Inspection Guarantee Window has expired for this order.");
            }

            OrderStatus oldStatus = order.getStatus();
            order.setStatus(OrderStatus.RETURN_REQUESTED);
            order.setDisputeReason(reason.trim());
            order = orderRepository.save(order);
            logAudit(order, actorId, oldStatus, OrderStatus.RETURN_REQUESTED,
                    "RETURN_CLAIM - Reason: " + reason.trim() + " Evidence: " + evidence.trim());
            return order;
        }

        // 2. Pre-delivery cancellation refund request (e.g. after PAYMENT_SUBMITTED / PAYMENT_VERIFIED)
        if (isOrderPaymentAttached(order) || order.getStatus() == OrderStatus.REFUND_REQUESTED) {
            OrderStatus oldStatus = order.getStatus();
            order.setStatus(OrderStatus.REFUND_REQUESTED);
            order.setDisputeReason(reason.trim());
            order = orderRepository.save(order);
            logAudit(order, actorId, oldStatus, OrderStatus.REFUND_REQUESTED,
                    "PRE_DELIVERY_REFUND - Reason: " + reason.trim());
            return order;
        }

        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Return/Refund cannot be requested from state: " + order.getStatus());
    }

    @Transactional
    public Order approveReturn(UUID orderId, UUID actorId) {
        Order order = getOrder(orderId);
        if (order.getSellerId() == null || !order.getSellerId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the seller can approve return");
        }
        if (order.getStatus() != OrderStatus.RETURN_REQUESTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Return cannot be approved from state: " + order.getStatus());
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.RETURN_APPROVED);

        // Generate 6-digit return OTP for the return meetup
        int otp = 100000 + SECURE_RANDOM.nextInt(900000);
        order.setReturnOtp(String.valueOf(otp));
        order.setReturnOtpCreatedAt(Instant.now());
        order.setReturnOtpFailedAttempts(0);

        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, OrderStatus.RETURN_APPROVED, "SELLER_APPROVED_RETURN");
        return order;
    }

    @Transactional
    public Order declineReturn(UUID orderId, UUID actorId, String reason) {
        Order order = getOrder(orderId);
        if (order.getSellerId() == null || !order.getSellerId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the seller can decline return");
        }
        if (order.getStatus() != OrderStatus.RETURN_REQUESTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Return cannot be declined from state: " + order.getStatus());
        }

        String note = (reason != null && !reason.trim().isEmpty()) ? reason.trim() : "Seller declined return request";
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.DISPUTED);
        order.setDisputeReason("SELLER_DECLINED_RETURN: " + note);

        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, OrderStatus.DISPUTED, "SELLER_DECLINED_RETURN - Escalated to dispute: " + note);
        return order;
    }

    @Transactional
    public Order verifyReturnHandoff(UUID orderId, UUID actorId, String otp, String refundReference) {
        Order order = getOrder(orderId);
        if (order.getStatus() != OrderStatus.RETURN_APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Return handoff cannot be verified from state: " + order.getStatus());
        }
        if (order.getSellerId() == null || !order.getSellerId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the seller can verify return handoff");
        }

        if (order.getReturnOtpFailedAttempts() >= MAX_OTP_ATTEMPTS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Return OTP verification locked after " + MAX_OTP_ATTEMPTS + " failed attempts.");
        }

        if (order.getReturnOtpCreatedAt() == null ||
                Instant.now().isAfter(order.getReturnOtpCreatedAt().plus(OTP_EXPIRY))) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "Return OTP has expired. Please approve/reschedule return to generate a new code.");
        }

        if (order.getReturnOtp() == null || !order.getReturnOtp().equals(otp)) {
            int attempts = orderOtpService.recordFailedReturnAttempt(order.getId(), actorId, MAX_OTP_ATTEMPTS);
            int remaining = MAX_OTP_ATTEMPTS - attempts;
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid Return OTP. " + remaining + " attempt(s) remaining.");
        }

        OrderStatus oldStatus = order.getStatus();
        order.setReturnCompletedAt(Instant.now());
        order.setReturnOtp(null);
        order.setReturnOtpCreatedAt(null);
        order.setReturnOtpFailedAttempts(0);

        // Terminal State Split:
        if (isOrderPaymentAttached(order)) {
            if (refundReference != null && !refundReference.trim().isEmpty()) {
                order.setRefundReference(refundReference.trim());
            }
            order.setStatus(OrderStatus.REFUND_COMPLETED);
            order = orderRepository.save(order);
            logAudit(order, actorId, oldStatus, OrderStatus.REFUND_COMPLETED,
                    "RETURN_HANDOFF_VERIFIED - GCash Refund Completed. Ref: " +
                            (order.getRefundReference() != null ? order.getRefundReference() : "N/A"));
        } else {
            // Cash on Meetup
            order.setStatus(OrderStatus.RETURN_COMPLETED);
            order = orderRepository.save(order);
            logAudit(order, actorId, oldStatus, OrderStatus.RETURN_COMPLETED,
                    "RETURN_HANDOFF_VERIFIED - COM Physical cash returned at meetup");
        }

        return order;
    }

    @Transactional
    public Order confirmRefund(UUID orderId, UUID actorId, String refundReference) {
        Order order = getOrder(orderId);
        boolean isSeller = actorId.equals(order.getSellerId());
        boolean isBuyer = actorId.equals(order.getBuyerId());
        if (!isSeller && !isBuyer) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the buyer or seller can resolve a refund");
        }
        if (order.getStatus() != OrderStatus.REFUND_REQUESTED && order.getStatus() != OrderStatus.DISPUTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order is not in a refundable status (current: " + order.getStatus() + ")");
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.REFUND_COMPLETED);
        // Note: return_completed_at is intentionally left null because no physical return meetup occurred on this digital refund path
        if (refundReference != null && !refundReference.trim().isEmpty()) {
            order.setRefundReference(refundReference.trim());
        }
        order = orderRepository.save(order);
        String refInfo = (order.getRefundReference() != null) ? " - Refund Ref: " + order.getRefundReference() : "";
        logAudit(order, actorId, oldStatus, OrderStatus.REFUND_COMPLETED,
                (isSeller ? "SELLER_REFUND_ISSUED" : "BUYER_REFUND_CONFIRMED") + refInfo);
        return order;
    }

    @Transactional
    public Order escalateDispute(UUID orderId, UUID actorId, String reason) {
        Order order = getOrder(orderId);
        if (!actorId.equals(order.getBuyerId()) && !actorId.equals(order.getSellerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the buyer or seller can escalate an order to dispute");
        }
        if (order.getStatus() != OrderStatus.REFUND_REQUESTED &&
                order.getStatus() != OrderStatus.RETURN_REQUESTED &&
                order.getStatus() != OrderStatus.NEEDS_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order cannot be escalated to dispute from state: " + order.getStatus());
        }

        String note = (reason != null && !reason.trim().isEmpty()) ? reason.trim() : "Party requested Admin Dispute Mediation";
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.DISPUTED);
        order.setDisputeReason("MANUAL_ESCALATION: " + note);
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, OrderStatus.DISPUTED, "DISPUTE_ESCALATED - " + note);
        return order;
    }

    @Transactional
    public Order resolveDispute(UUID orderId, UUID adminId, String ruling, String resolutionNotes) {
        Order order = getOrder(orderId);
        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin profile not found"));

        if (!"ADMIN".equalsIgnoreCase(admin.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator can mediate disputes");
        }

        if (order.getStatus() != OrderStatus.DISPUTED && order.getStatus() != OrderStatus.NEEDS_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Dispute resolution requires DISPUTED or NEEDS_REVIEW status (current: " + order.getStatus() + ")");
        }

        OrderStatus oldStatus = order.getStatus();
        order.setDisputeRuling(ruling != null ? ruling.trim().toUpperCase() : "RESOLVED");

        String upperRuling = ruling != null ? ruling.trim().toUpperCase() : "";

        if ("RELEASE_TO_SELLER".equals(upperRuling) || "OVERRULE".equals(upperRuling)) {
            order.setStatus(OrderStatus.COMPLETED);
        } else if ("REFUND_BUYER".equals(upperRuling) || "DIRECT_REFUND".equals(upperRuling)) {
            order.setStatus(OrderStatus.REFUND_COMPLETED);
            // Note: return_completed_at is left null on direct refund rulings; physical return completion is only stamped by verifyReturnHandoff()
        } else if ("RESCHEDULE_RETURN".equals(upperRuling) || "RETURN_ITEM".equals(upperRuling) || "APPROVE_RETURN".equals(upperRuling)) {
            order.setStatus(OrderStatus.RETURN_APPROVED);
            int otp = 100000 + SECURE_RANDOM.nextInt(900000);
            order.setReturnOtp(String.valueOf(otp));
            order.setReturnOtpCreatedAt(Instant.now());
            order.setReturnOtpFailedAttempts(0);
        } else if ("CANCEL".equals(upperRuling)) {
            if (order.getHandoffCompletedAt() != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cannot CANCEL post-handoff dispute: goods were already transferred. Use REFUND_BUYER or RETURN_ITEM instead.");
            }
            order.setStatus(OrderStatus.CANCELLED);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown dispute ruling: " + ruling);
        }

        order = orderRepository.save(order);
        logAudit(order, adminId, oldStatus, order.getStatus(),
                "ADMIN_MEDIATION - Ruling: " + upperRuling + ". Notes: " + (resolutionNotes != null ? resolutionNotes.trim() : ""));
        return order;
    }

    @Transactional
    public Order submitPayment(UUID orderId, UUID actorId, String reference, String proofUrl) {
        Order order = getOrder(orderId);
        if (order.getBuyerId() == null || !order.getBuyerId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the buyer can submit payment reference");
        }
        if (reference == null || reference.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment reference is required");
        }

        if (order.getStatus() != OrderStatus.PLACED &&
                order.getStatus() != OrderStatus.ACCEPTED &&
                order.getStatus() != OrderStatus.INQUIRY_SENT &&
                order.getStatus() != OrderStatus.PENDING_SELLER_ACCEPT &&
                order.getStatus() != OrderStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payment cannot be submitted from state: " + order.getStatus());
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
        if (order.getSellerId() == null || !order.getSellerId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the seller can verify payment");
        }
        if (order.getStatus() != OrderStatus.PAYMENT_SUBMITTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payment cannot be verified from state: " + order.getStatus());
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.PAYMENT_VERIFIED);
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, order.getStatus(), "PAYMENT_VERIFIED");
        return order;
    }
}
