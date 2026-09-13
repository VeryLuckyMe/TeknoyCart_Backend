package com.teknoycart.models;

public enum OrderStatus {
    // New state machine statuses
    PLACED,
    ACCEPTED,
    MEETUP_SCHEDULED,
    HANDOFF_PENDING,
    COMPLETED,
    CANCELLED,
    DISPUTED,
    REFUND_REQUESTED,

    // Legacy statuses from the old Supabase-driven flow (kept for backward compatibility)
    INQUIRY_SENT,
    PENDING_SELLER_ACCEPT,
    APPROVED,
    SELLER_ACCEPTED,
    PAYMENT_SUBMITTED,
    PAYMENT_VERIFIED
}
