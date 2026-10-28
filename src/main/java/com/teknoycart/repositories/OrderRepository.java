package com.teknoycart.repositories;

import com.teknoycart.models.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Query("SELECT o FROM Order o WHERE o.status = :status")
    List<Order> findByStatus(@Param("status") com.teknoycart.models.OrderStatus status);

    /**
     * Finds orders stuck in AWAITING_PAYMENT with an expired PayMongo checkout session.
     * Used by the Phase 6 expiry sweep job. Filters at the DB level to avoid loading all
     * AWAITING_PAYMENT rows into memory (C-5 fix).
     */
    @Query("SELECT o FROM Order o WHERE o.status = 'AWAITING_PAYMENT' AND o.paymentExpiresAt < :now")
    List<Order> findExpiredAwaitingPayment(@Param("now") Instant now);

    /**
     * Completed GCash orders whose payout was never released. The RPC confirm
     * path transitions the row in SQL without calling the Java payout service,
     * so the hourly sweep is the convergence point.
     */
    @Query("SELECT o FROM Order o WHERE o.status = 'COMPLETED' " +
           "AND o.payoutReleased = false AND o.amountCentavos IS NOT NULL")
    List<Order> findCompletedWithoutPayout();
}

