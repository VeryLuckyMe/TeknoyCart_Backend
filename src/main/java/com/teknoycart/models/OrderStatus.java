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
