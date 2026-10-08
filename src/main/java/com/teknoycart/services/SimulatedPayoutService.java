package com.teknoycart.services;

import com.teknoycart.models.Order;
import com.teknoycart.models.OrderAuditLog;
import com.teknoycart.repositories.OrderAuditLogRepository;
import com.teknoycart.repositories.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Sandbox payout implementation. Logs the simulated disbursement and notifies
 * the seller via audit trail.
 *
 * <p>In live mode this would be replaced by a real PayMongo Disbursement API call.
 * That requires PayMongo merchant KYC approval and is out of scope for the capstone.
 *
 * <p><strong>Idempotency:</strong> Guards on {@link Order#isPayoutReleased()} before
 * emitting the audit entry. If the flag is already set, this method is a no-op.
 */
@Service
public class SimulatedPayoutService implements PayoutService {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPayoutService.class);

    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderAuditLogRepository auditLogRepository;

    @Override
    @Transactional
    public void release(Order order) {
        if (order.isPayoutReleased()) {
            log.info("Payout already released for order {}; skipping duplicate call.", order.getId());
            return;
        }

        // Mark released before saving to prevent duplicate if method is called twice in same tx.
        order.setPayoutReleased(true);
        orderRepository.save(order);

        // Format centavos as PHP for the audit message.
        String phpAmount = order.getAmountCentavos() != null
                ? "PHP " + BigDecimal.valueOf(order.getAmountCentavos())
                          .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).toPlainString()
                : "N/A";

        String auditMsg = "PAYOUT_SIMULATED - would disburse " + phpAmount
                + " to seller " + order.getSellerId()
                + " for order " + order.getId()
                + " [LIVE MODE: replace with PayMongo Disbursement API call]";

        OrderAuditLog log2 = new OrderAuditLog();
        log2.setOrderId(order.getId());
        log2.setActorId(null); // SYSTEM action
        log2.setPreviousStatus("COMPLETED");
        log2.setNewStatus("COMPLETED");
        log2.setMethod(auditMsg);
        auditLogRepository.save(log2);

        log.info("Simulated payout: {} for order {}", phpAmount, order.getId());
    }
}
