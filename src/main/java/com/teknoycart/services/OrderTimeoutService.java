package com.teknoycart.services;

import com.teknoycart.models.Order;
import com.teknoycart.models.OrderAuditLog;
import com.teknoycart.models.OrderStatus;
import com.teknoycart.repositories.OrderAuditLogRepository;
import com.teknoycart.repositories.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Scheduled job that detects and transitions orders stuck in intermediate states.
 *
 * - MEETUP_SCHEDULED with an OTP older than 24 hours → NEEDS_REVIEW
 * - HANDOFF_PENDING for more than 24 hours without buyer return claim → COMPLETED (auto-release)
 * - REFUND_REQUESTED for more than 24 hours without seller action → DISPUTED (auto-escalation)
 */
@Service
public class OrderTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutService.class);

    /** If a meetup OTP is older than 24h, the meetup is stale and needs rescheduling */
    private static final Duration MEETUP_STALE_THRESHOLD = Duration.ofHours(24);

    /** Canonical 24-Hour TeknoyCart Guarantee Inspection Window */
    private static final Duration HANDOFF_AUTO_COMPLETE_THRESHOLD = Duration.ofHours(24);

    /** If seller hasn't resolved refund within 24h of request, auto-escalate to DISPUTED */
    private static final Duration REFUND_STALE_THRESHOLD = Duration.ofHours(24);

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderAuditLogRepository auditLogRepository;

    @Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /**
     * Sweeps expired reservations every 5 minutes (300,000 ms).
     * Single Source of Truth: release_expired_reservations() ONLY updates orders to CANCELLED.
     * The DB trigger trg_order_status_inventory_sync decrements reserved_qty.
     */
    @Scheduled(fixedRate = 300000)
    @Transactional
    public void sweepExpiredReservations() {
        if (jdbcTemplate != null) {
            try {
                Integer released = jdbcTemplate.queryForObject("SELECT release_expired_reservations();", Integer.class);
                if (released != null && released > 0) {
                    log.info("Sweep successfully released {} expired reservations.", released);
                }
            } catch (Exception e) {
                log.debug("Notice on release_expired_reservations: {}", e.getMessage());
            }
        }
    }

    /**
     * Runs every hour to check for stuck orders.
     */
    @Scheduled(fixedRate = 3600000) // every 60 minutes
    @Transactional
    public void processStuckOrders() {
        log.info("Running stuck-order timeout check...");

        int staleMeetups = processStaleScheduledMeetups();
        int autoCompleted = processStaleHandoffs();
        int escalatedRefunds = processStaleRefundRequests();

        if (staleMeetups > 0 || autoCompleted > 0 || escalatedRefunds > 0) {
            log.info("Timeout job completed: {} meetups flagged for review, {} handoffs auto-completed, {} refunds escalated to dispute",
                    staleMeetups, autoCompleted, escalatedRefunds);
        }
    }

    private int processStaleScheduledMeetups() {
        List<Order> staleOrders = orderRepository.findByStatus(OrderStatus.MEETUP_SCHEDULED);
        int count = 0;

        Instant cutoff = Instant.now().minus(MEETUP_STALE_THRESHOLD);

        for (Order order : staleOrders) {
            // Use otp_created_at as the timestamp of when the meetup was scheduled
            Instant meetupTime = order.getOtpCreatedAt();
            if (meetupTime == null) {
                // If no otp_created_at, check the latest audit log as fallback
                OrderAuditLog latestLog = auditLogRepository.findTopByOrderIdOrderByCreatedAtDesc(order.getId());
                if (latestLog != null && latestLog.getCreatedAt() != null) {
                    meetupTime = latestLog.getCreatedAt();
                } else {
                    continue; // Can't determine age, skip
                }
            }

            if (meetupTime.isBefore(cutoff)) {
                OrderStatus oldStatus = order.getStatus();
                order.setStatus(OrderStatus.NEEDS_REVIEW);
                // Clear stale OTP data
                order.setHandoffOtp(null);
                order.setOtpCreatedAt(null);
                order.setOtpFailedAttempts(0);
                orderRepository.save(order);

                logSystemAudit(order, oldStatus, OrderStatus.NEEDS_REVIEW,
                        "SYSTEM_TIMEOUT - Meetup scheduled over 24h ago without handoff");
                count++;
                log.info("Order {} moved to NEEDS_REVIEW (stale meetup)", order.getId());
            }
        }
        return count;
    }

    private int processStaleHandoffs() {
        List<Order> pendingOrders = orderRepository.findByStatus(OrderStatus.HANDOFF_PENDING);
        int count = 0;

        for (Order order : pendingOrders) {
            if (order.getStatus() != OrderStatus.HANDOFF_PENDING) {
                continue;
            }

            Instant handoffTime = order.getHandoffCompletedAt();
            if (handoffTime == null) {
                OrderAuditLog latestLog = auditLogRepository.findTopByOrderIdOrderByCreatedAtDesc(order.getId());
                if (latestLog != null && latestLog.getCreatedAt() != null) {
                    handoffTime = latestLog.getCreatedAt();
                }
            }

            if (handoffTime == null) {
                continue;
            }

            if (handoffTime.plus(HANDOFF_AUTO_COMPLETE_THRESHOLD).isBefore(Instant.now())) {
                OrderStatus oldStatus = order.getStatus();
                order.setBuyerConfirmedReceipt(true); // Auto-confirm
                order.setStatus(OrderStatus.COMPLETED);
                orderRepository.save(order);

                logSystemAudit(order, oldStatus, OrderStatus.COMPLETED,
                        "SYSTEM_AUTO_COMPLETE - Buyer did not dispute within 24h of handoff");
                count++;
                log.info("Order {} auto-completed (24h handoff inspection guarantee timeout)", order.getId());
            }
        }
        return count;
    }

    private int processStaleRefundRequests() {
        List<Order> staleRefunds = orderRepository.findByStatus(OrderStatus.REFUND_REQUESTED);
        int count = 0;
        Instant cutoff = Instant.now().minus(REFUND_STALE_THRESHOLD);

        for (Order order : staleRefunds) {
            if (order.getStatus() != OrderStatus.REFUND_REQUESTED) {
                continue;
            }

            OrderAuditLog latestLog = auditLogRepository.findTopByOrderIdOrderByCreatedAtDesc(order.getId());
            if (latestLog == null || latestLog.getCreatedAt() == null) continue;

            if (latestLog.getCreatedAt().isBefore(cutoff)) {
                OrderStatus oldStatus = order.getStatus();
                order.setStatus(OrderStatus.DISPUTED);
                order.setDisputeReason("AUTO_ESCALATED: Seller did not issue refund within 24 hours of request");
                orderRepository.save(order);

                logSystemAudit(order, oldStatus, OrderStatus.DISPUTED,
                        "SYSTEM_AUTO_DISPUTE - Seller did not issue refund within 24h of request");
                count++;
                log.info("Order {} auto-escalated to DISPUTED (stale refund request)", order.getId());
            }
        }
        return count;
    }

    private void logSystemAudit(Order order, OrderStatus oldStatus, OrderStatus newStatus, String method) {
        OrderAuditLog auditLog = new OrderAuditLog();
        auditLog.setOrderId(order.getId());
        auditLog.setActorId(null); // SYSTEM action
        auditLog.setPreviousStatus(oldStatus != null ? oldStatus.name() : null);
        auditLog.setNewStatus(newStatus.name());
        auditLog.setMethod(method);
        auditLogRepository.save(auditLog);
    }
}
