package com.teknoycart.models;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Idempotency table for incoming PayMongo webhook events.
 *
 * <p>PayMongo retries webhook delivery up to 12 times. Inserting the event_id as a PRIMARY KEY
 * and catching {@link org.springframework.dao.DataIntegrityViolationException} on duplicate ensures
 * exactly-once processing without any secondary unique-index overhead.
 *
 * <p>Lifecycle: records are retained permanently for audit purposes.
 */
@Entity
@Table(name = "payment_events")
public class PaymentEvent {

    /**
     * PayMongo webhook event ID (e.g., "evt_xxx"). Used as the primary key so that duplicate
     * deliveries of the same event are rejected at the DB constraint level.
     */
    @Id
    @Column(name = "event_id")
    private String eventId;

    /** FK to the order this event belongs to. Nullable on SET NULL if the order is deleted. */
    @Column(name = "order_id")
    private UUID orderId;

    /** PayMongo event type string, e.g. "checkout_session.payment.paid". */
    @Column(name = "type", nullable = false)
    private String type;

    /** Instant the webhook was received by our server. */
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
}
