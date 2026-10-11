package com.teknoycart.services;

import com.teknoycart.models.Order;
import com.teknoycart.models.OrderAuditLog;
import com.teknoycart.models.OrderStatus;
import com.teknoycart.models.PaymentEvent;
import com.teknoycart.models.User;
import com.teknoycart.repositories.OrderAuditLogRepository;
import com.teknoycart.repositories.OrderRepository;
import com.teknoycart.repositories.PaymentEventRepository;
import com.teknoycart.repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** OTP expires after 15 minutes */
    private static final Duration OTP_EXPIRY = Duration.ofMinutes(15);

    /** Lock out after 5 consecutive failed OTP attempts */
    private static final int MAX_OTP_ATTEMPTS = 5;

    /** 24-Hour TeknoyCart Guarantee Inspection Window */
    private static final Duration GUARANTEE_INSPECTION_WINDOW = Duration.ofHours(24);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${paymongo.checkout-ttl-minutes:30}")
    private int checkoutTtlMinutes;

    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderAuditLogRepository auditLogRepository;
    @Autowired private OrderOtpService orderOtpService;
    @Autowired private UserRepository userRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private PayMongoClient payMongoClient;
    @Autowired private PayoutService payoutService;

    private void logAudit(Order order, UUID actorId, OrderStatus oldStatus, OrderStatus newStatus, String method) {
        OrderAuditLog log = new OrderAuditLog();
        log.setOrderId(order.getId());
        log.setActorId(actorId);
        log.setPreviousStatus(oldStatus != null ? oldStatus.name() : null);
        log.setNewStatus(newStatus != null ? newStatus.name() : null);
        log.setMethod(method);
        auditLogRepository.save(log);
    }

    /**
     * Returns true if real money is associated with this order and unilateral cancellation
     * would risk stranding funds.
     * C-7 fix: now also covers ESCROWED (PayMongo-held funds) and any order with amount_centavos set.
     */
    private boolean isOrderPaymentAttached(Order order) {
        return order.getStatus() == OrderStatus.PAYMENT_VERIFIED ||
                order.getStatus() == OrderStatus.PAYMENT_SUBMITTED ||
                order.getStatus() == OrderStatus.ESCROWED ||
                order.getAmountCentavos() != null ||
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
        if (actorId == null || order.getSellerId() == null || !order.getSellerId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the seller can accept this order");
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.ACCEPTED);
        if (order.getVersion() == null) {
            order.setVersion(0L);
        }
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, order.getStatus(), "MANUAL");
        log.info("Order {} successfully accepted by seller {}", orderId, actorId);
        return order;
    }

    @Transactional
    public Order cancelOrder(UUID orderId, UUID actorId, String reason) {
        Order order = getOrder(orderId);
        if (actorId == null || (!actorId.equals(order.getBuyerId()) && !actorId.equals(order.getSellerId()))) {
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
                order.getStatus() == OrderStatus.REFUND_COMPLETED ||
                order.getStatus() == OrderStatus.REFUND_PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order cannot be cancelled from state: " + order.getStatus());
        }

        // B-2 fix: ESCROWED = real money held by PayMongo. Route to REFUND_PENDING for admin action.
        if (order.getStatus() == OrderStatus.ESCROWED) {
            OrderStatus oldStatus = order.getStatus();
            order.setStatus(OrderStatus.REFUND_PENDING);
            order = orderRepository.save(order);
            logAudit(order, actorId, oldStatus, OrderStatus.REFUND_PENDING,
                    "CANCELLED_WHILE_ESCROWED - Admin must issue manual refund via PayMongo dashboard. Reason: " + reason.trim());
            return order;
        }

        // B-2 fix: AWAITING_PAYMENT = checkout session exists. Buyer cancelled before paying.
        // Revert to ACCEPTED and clear the checkout session fields.
        if (order.getStatus() == OrderStatus.AWAITING_PAYMENT) {
            OrderStatus oldStatus = order.getStatus();
            order.setStatus(OrderStatus.ACCEPTED);
            order.setPaymongoCheckoutSessionId(null);
            order.setPaymongoCheckoutUrl(null);
            order.setPaymentExpiresAt(null);
            order = orderRepository.save(order);
            logAudit(order, actorId, oldStatus, OrderStatus.ACCEPTED,
                    "BUYER_CANCELLED_AWAITING_PAYMENT - Checkout session cleared. Reason: " + reason.trim());
            return order;
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

        // C-6 fix: trigger payout after status commit if this was a GCash/ESCROWED order.
        releasePayout(order);

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

    // ── PayMongo GCash Payment Hold methods (Phase 5/6/7) ──────────────────────

    /**
     * Phase 5: Initiates a GCash checkout session for an ACCEPTED order.
     *
     * <p>If the order is already AWAITING_PAYMENT and not expired, returns the stored URL
     * without creating a second session (no double-charge risk).
     *
     * @param orderId  order to initiate payment for
     * @param actorId  must equal the order's buyerId
     * @param successUrl URL PayMongo redirects buyer to after successful payment
     * @param cancelUrl  URL PayMongo redirects buyer to if they cancel
     * @return {@link InitiatePaymentResult} with the checkout URL and expiry
     */
    @Transactional
    public InitiatePaymentResult initiateGcashPayment(UUID orderId, UUID actorId,
                                                       String successUrl, String cancelUrl) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        // Auth: only buyer can initiate payment.
        if (!actorId.equals(order.getBuyerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the buyer can initiate GCash payment");
        }

        // Gate: must be a GCash order.
        if (!"GCASH".equalsIgnoreCase(order.getPaymentMethod())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This order's payment method is not GCash");
        }

        Instant now = Instant.now();

        // If already AWAITING_PAYMENT and not expired, return existing URL (no second session).
        if (order.getStatus() == OrderStatus.AWAITING_PAYMENT
                && order.getPaymentExpiresAt() != null
                && now.isBefore(order.getPaymentExpiresAt())) {
            return new InitiatePaymentResult(order.getPaymongoCheckoutUrl(), order.getPaymentExpiresAt());
        }

        // Must be ACCEPTED to create a new session.
        if (order.getStatus() != OrderStatus.ACCEPTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot initiate payment from state: " + order.getStatus());
        }

        // Server-side amount computation — never trust client-supplied amounts.
        // total_amount is stored in the DB as a double (existing schema); convert to centavos as long.
        // Using BigDecimal at the boundary prevents rounding drift (D2).
        long amountCentavos = computeCentavos(orderId);

        Instant expiresAt = now.plus(Duration.ofMinutes(checkoutTtlMinutes));

        // Call PayMongo — if this throws, nothing is saved and order stays ACCEPTED.
        PayMongoClient.CheckoutSessionResult session = payMongoClient.createGcashCheckoutSession(
                amountCentavos,
                orderId.toString(),
                "TeknoyCart Order " + orderId,
                successUrl,
                cancelUrl,
                expiresAt.getEpochSecond());

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.AWAITING_PAYMENT);
        order.setPaymongoCheckoutSessionId(session.sessionId());
        order.setPaymongoCheckoutUrl(session.checkoutUrl());
        order.setPaymentExpiresAt(expiresAt);
        order.setAmountCentavos(amountCentavos);
        if (order.getVersion() == null) {
            order.setVersion(0L);
        }
        order = orderRepository.save(order);
        logAudit(order, actorId, oldStatus, OrderStatus.AWAITING_PAYMENT,
                "GCASH_CHECKOUT_INITIATED - Session: " + session.sessionId()
                        + ", expires: " + expiresAt);

        return new InitiatePaymentResult(session.checkoutUrl(), expiresAt);
    }

    /**
     * Phase 4: Called by the webhook controller after signature + idempotency checks pass.
     * Moves the order from AWAITING_PAYMENT to ESCROWED.
     *
     * <p>If already ESCROWED (duplicate webhook): no-op, returns quietly.
     * If in any other state: flags for admin (money taken but order in wrong state).
     *
     * @param orderId   order UUID from webhook metadata
     * @param sessionId PayMongo session ID from webhook; must match the one stored on the order
     */
    @Transactional
    public void markEscrowed(UUID orderId, String sessionId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.error("Webhook: order {} not found for session {}", orderId, sessionId);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
                });

        if (order.getStatus() == OrderStatus.ESCROWED) {
            // Duplicate webhook after we already processed it — idempotent no-op.
            log.info("Webhook: order {} already ESCROWED; ignoring duplicate.", orderId);
            return;
        }

        if (order.getStatus() == OrderStatus.AWAITING_PAYMENT) {
            OrderStatus oldStatus = order.getStatus();
            order.setStatus(OrderStatus.ESCROWED);
            order = orderRepository.save(order);
            logAudit(order, null, oldStatus, OrderStatus.ESCROWED,
                    "PAYMONGO_WEBHOOK - payment confirmed, session " + sessionId);
            log.info("Order {} moved to ESCROWED via webhook, session {}", orderId, sessionId);
            // TODO: notify seller (email/push) — integrate with EmailService here.
            return;
        }

        // Money arrived but order is in an unexpected state (expired, cancelled, etc.)
        // Flag for admin manual refund via PayMongo dashboard.
        log.error("PAYMENT_RECEIVED_IN_UNEXPECTED_STATE: order {} status={}, session={}",
                orderId, order.getStatus(), sessionId);
        logAudit(order, null, order.getStatus(), order.getStatus(),
                "PAYMENT_RECEIVED_IN_UNEXPECTED_STATE - session " + sessionId
                        + " - Admin must issue manual refund via PayMongo dashboard");
    }

    /**
     * Phase 6: Scheduled expiry sweep.
     * Moves AWAITING_PAYMENT orders with expired checkout sessions back to ACCEPTED.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedRate = 300_000)
    @Transactional
    public void expireAwaitingPayments() {
        List<Order> expired = orderRepository.findExpiredAwaitingPayment(Instant.now());
        for (Order order : expired) {
            if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) continue; // race guard
            OrderStatus oldStatus = order.getStatus();
            order.setStatus(OrderStatus.ACCEPTED);
            order.setPaymongoCheckoutSessionId(null);
            order.setPaymongoCheckoutUrl(null);
            order.setPaymentExpiresAt(null);
            orderRepository.save(order);
            logAudit(order, null, oldStatus, OrderStatus.ACCEPTED,
                    "PAYMENT_EXPIRED - checkout session expired, reverted to ACCEPTED");
            log.info("Order {} payment expired; reverted to ACCEPTED", order.getId());
        }
    }

    /**
     * Phase 7: Triggers the payout after a GCash/ESCROWED order reaches COMPLETED.
     * Guards on {@code payout_released} flag for idempotency (C-6 fix).
     * Must be called AFTER the COMPLETED status is committed.
     *
     * @param order order in COMPLETED status
     */
    public void releasePayout(Order order) {
        if (order.getAmountCentavos() == null) {
            // Not a GCash order — no payout needed.
            return;
        }
        if (order.isPayoutReleased()) {
            log.info("Payout already released for order {}", order.getId());
            return;
        }
        payoutService.release(order);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Queries the DB for the order's total_amount and converts to centavos using BigDecimal
     * to avoid double-rounding drift (D2).
     */
    private long computeCentavos(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        // Fallback: if amount_centavos already set (re-initiation path), reuse it.
        if (order.getAmountCentavos() != null) return order.getAmountCentavos();
        if (order.getTotalAmount() != null) {
            return order.getTotalAmount().multiply(new java.math.BigDecimal(100)).longValueExact();
        }
        throw new IllegalStateException("Order " + orderId + " has neither amountCentavos nor totalAmount set");
    }

    /** Lightweight result object for the initiate-payment endpoint response. */
    public record InitiatePaymentResult(String checkoutUrl, Instant expiresAt) {}
}
