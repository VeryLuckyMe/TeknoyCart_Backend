package com.teknoycart.services;

import com.teknoycart.models.Order;

/**
 * Abstraction over the PayMongo payout/disbursement API.
 *
 * <p>Only {@link SimulatedPayoutService} is shipped in sandbox mode.
 * A live implementation would call the PayMongo Disbursement API and
 * requires PayMongo KYC approval for the merchant account.
 *
 * <p>Implementations MUST be idempotent: calling {@link #release(Order)} multiple
 * times on the same order must NOT result in a duplicate payout. The
 * {@code payout_released} flag on {@link Order} is the primary guard.
 */
public interface PayoutService {

    /**
     * Triggers a payout to the seller for a completed order.
     *
     * <p>Callers must ensure:
     * <ul>
     *   <li>Order status is {@code COMPLETED} before calling this method.</li>
     *   <li>The call happens <em>after</em> the status commit is persisted.</li>
     *   <li>{@code order.isPayoutReleased()} is checked before calling (idempotency).</li>
     * </ul>
     *
     * @param order the completed order for which the payout should be released
     */
    void release(Order order);
}
