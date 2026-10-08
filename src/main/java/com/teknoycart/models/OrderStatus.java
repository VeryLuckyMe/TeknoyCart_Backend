package com.teknoycart.models;

public enum OrderStatus {
    // Core state machine statuses
    PLACED,
    ACCEPTED,
    PAYMENT_SUBMITTED,
    PAYMENT_VERIFIED,
    MEETUP_SCHEDULED,
    NEEDS_REVIEW,
    HANDOFF_PENDING,
    COMPLETED,
    REFUND_REQUESTED,
    RETURN_REQUESTED,
    RETURN_APPROVED,
    DISPUTED,
    CANCELLED,
    RETURN_COMPLETED,
    REFUND_COMPLETED,

    // PayMongo GCash payment hold statuses (Rev 2 — 2026)
    // AWAITING_PAYMENT: checkout session created, buyer not yet paid
    // ESCROWED:         webhook confirmed payment; funds held until handoff
    // REFUND_PENDING:   admin-triggered; manual refund needed via PayMongo dashboard
    AWAITING_PAYMENT,
    ESCROWED,
    REFUND_PENDING,

    // Legacy statuses from the old Supabase-driven flow (retained strictly for backward compatibility deserialization)
    @Deprecated
    INQUIRY_SENT,
    @Deprecated
    PENDING_SELLER_ACCEPT,
    @Deprecated
    APPROVED,
    @Deprecated
    SELLER_ACCEPTED
}
