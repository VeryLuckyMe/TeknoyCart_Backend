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
 * - HANDOFF_PENDING for more than 48 hours without buyer confirmation → COMPLETED (auto-release)
 */
@Service
public class OrderTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutService.class);

    /** If a meetup OTP is older than 24h, the meetup is probably stale */
    private static final Duration MEETUP_STALE_THRESHOLD = Duration.ofHours(24);

    /** If buyer hasn't confirmed receipt within 48h of handoff, auto-complete */
    private static final Duration HANDOFF_AUTO_COMPLETE_THRESHOLD = Duration.ofHours(48);

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderAuditLogRepository auditLogRepository;

    /**
     * Runs every hour to check for stuck orders.
     */
    @Scheduled(fixedRate = 3600000) // every 60 minutes
    @Transactional
    public void processStuckOrders() {
        log.info("Running stuck-order timeout check...");

        int staleMeetups = processStaleScheduledMeetups();
        int autoCompleted = processStaleHandoffs();

        if (staleMeetups > 0 || autoCompleted > 0) {
            log.info("Timeout job completed: {} meetups flagged for review, {} handoffs auto-completed",
                    staleMeetups, autoCompleted);
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
            OrderAuditLog latestLog = auditLogRepository.findTopByOrderIdOrderByCreatedAtDesc(order.getId());
            if (latestLog == null || latestLog.getCreatedAt() == null) continue;

            Instant handoffTime = latestLog.getCreatedAt();

            if (handoffTime.plus(HANDOFF_AUTO_COMPLETE_THRESHOLD).isBefore(Instant.now())) {
                OrderStatus oldStatus = order.getStatus();
                order.setBuyerConfirmedReceipt(true); // Auto-confirm
                order.setStatus(OrderStatus.COMPLETED);
                orderRepository.save(order);

                logSystemAudit(order, oldStatus, OrderStatus.COMPLETED,
                        "SYSTEM_AUTO_COMPLETE - Buyer did not dispute within 48h of handoff");
                count++;
                log.info("Order {} auto-completed (48h handoff timeout)", order.getId());
            }
        }
        return count;
    }

    private void logSystemAudit(Order order, OrderStatus oldStatus, OrderStatus newStatus, String method) {
        OrderAuditLog auditLog = new OrderAuditLog();
        auditLog.setOrderId(order.getId());
        auditLog.setActorId(null); // SYSTEM action
        auditLog.setPreviousStatus(oldStatus.name());
        auditLog.setNewStatus(newStatus.name());
        auditLog.setMethod(method);
        auditLogRepository.save(auditLog);
    }
}
