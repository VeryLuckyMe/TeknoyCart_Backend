package com.teknoycart.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "orders")
@org.hibernate.annotations.DynamicUpdate
public class Order {

    @Id
    @Column(name = "order_id")
    private UUID id;

    @Column(name = "buyer_id")
    private UUID buyerId;

    @Column(name = "seller_id")
    private UUID sellerId;

    @Convert(converter = OrderStatusConverter.class)
    @Column(name = "status")
    private OrderStatus status;

    @Column(name = "seller_handed_off")
    private boolean sellerHandedOff = false;

    @Column(name = "buyer_confirmed_receipt")
    private boolean buyerConfirmedReceipt = false;

    @Column(name = "handoff_otp")
    private String handoffOtp;

    @Column(name = "otp_created_at")
    private Instant otpCreatedAt;

    @Column(name = "otp_failed_attempts")
    private int otpFailedAttempts = 0;

    @Column(name = "handoff_completed_at")
    private Instant handoffCompletedAt;

    @Column(name = "return_otp")
    private String returnOtp;

    @Column(name = "return_otp_created_at")
    private Instant returnOtpCreatedAt;

    @Column(name = "return_otp_failed_attempts")
    private int returnOtpFailedAttempts = 0;

    @Column(name = "return_completed_at")
    private Instant returnCompletedAt;

    @Column(name = "refund_reference")
    private String refundReference;

    @Column(name = "dispute_reason")
    private String disputeReason;

    @Column(name = "dispute_ruling")
    private String disputeRuling;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    @Column(name = "payment_reference")
    private String paymentReference;

    @Column(name = "payment_proof_url")
    private String paymentProofUrl;

    @Column(name = "pickup_location")
    private String pickupLocation;

    @Column(name = "pickup_day")
    private String pickupDay;

    @Column(name = "pickup_time")
    private String pickupTime;

    // ── PayMongo GCash Payment Hold fields (Rev 2) ──────────────────────────────

    /** PayMongo Checkout Session ID; non-null while AWAITING_PAYMENT / ESCROWED. */
    @Column(name = "paymongo_checkout_session_id")
    private String paymongoCheckoutSessionId;

    /** Hosted checkout URL returned by PayMongo; cleared on expiry. */
    @Column(name = "paymongo_checkout_url")
    private String paymongoCheckoutUrl;

    /** Absolute instant when the checkout session expires (set by checkout-ttl-minutes). */
    @Column(name = "payment_expires_at")
    private Instant paymentExpiresAt;

    /**
     * Order amount in Philippine centavos (e.g. PHP 100.50 = 10050).
     * Stored as Long to avoid floating-point rounding bugs at the PayMongo boundary.
     * Populated when checkout session is created; null for Cash on Pickup orders.
     */
    @Column(name = "amount_centavos")
    private Long amountCentavos;

    /**
     * Guard flag set to true once PayoutService.release() has been called for this order.
     * Prevents duplicate payout when both confirmReceipt() and processStaleHandoffs() can
     * independently trigger COMPLETED (C-6 fix).
     */
    @Column(name = "payout_released", nullable = false)
    private boolean payoutReleased = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── PayMongo Getters / Setters ──────────────────────────────────────────────

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public String getPaymongoCheckoutSessionId() { return paymongoCheckoutSessionId; }
    public void setPaymongoCheckoutSessionId(String id) { this.paymongoCheckoutSessionId = id; }

    public String getPaymongoCheckoutUrl() { return paymongoCheckoutUrl; }
    public void setPaymongoCheckoutUrl(String url) { this.paymongoCheckoutUrl = url; }

    public Instant getPaymentExpiresAt() { return paymentExpiresAt; }
    public void setPaymentExpiresAt(Instant paymentExpiresAt) { this.paymentExpiresAt = paymentExpiresAt; }

    public Long getAmountCentavos() { return amountCentavos; }
    public void setAmountCentavos(Long amountCentavos) { this.amountCentavos = amountCentavos; }

    public boolean isPayoutReleased() { return payoutReleased; }
    public void setPayoutReleased(boolean payoutReleased) { this.payoutReleased = payoutReleased; }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getBuyerId() {
        return buyerId;
    }

    public void setBuyerId(UUID buyerId) {
        this.buyerId = buyerId;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public void setSellerId(UUID sellerId) {
        this.sellerId = sellerId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public boolean isSellerHandedOff() {
        return sellerHandedOff;
    }

    public void setSellerHandedOff(boolean sellerHandedOff) {
        this.sellerHandedOff = sellerHandedOff;
    }

    public boolean isBuyerConfirmedReceipt() {
        return buyerConfirmedReceipt;
    }

    public void setBuyerConfirmedReceipt(boolean buyerConfirmedReceipt) {
        this.buyerConfirmedReceipt = buyerConfirmedReceipt;
    }

    public String getHandoffOtp() {
        return handoffOtp;
    }

    public void setHandoffOtp(String handoffOtp) {
        this.handoffOtp = handoffOtp;
    }

    public Instant getOtpCreatedAt() {
        return otpCreatedAt;
    }

    public void setOtpCreatedAt(Instant otpCreatedAt) {
        this.otpCreatedAt = otpCreatedAt;
    }

    public int getOtpFailedAttempts() {
        return otpFailedAttempts;
    }

    public void setOtpFailedAttempts(int otpFailedAttempts) {
        this.otpFailedAttempts = otpFailedAttempts;
    }

    public Instant getHandoffCompletedAt() {
        return handoffCompletedAt;
    }

    public void setHandoffCompletedAt(Instant handoffCompletedAt) {
        this.handoffCompletedAt = handoffCompletedAt;
    }

    public String getReturnOtp() {
        return returnOtp;
    }

    public void setReturnOtp(String returnOtp) {
        this.returnOtp = returnOtp;
    }

    public Instant getReturnOtpCreatedAt() {
        return returnOtpCreatedAt;
    }

    public void setReturnOtpCreatedAt(Instant returnOtpCreatedAt) {
        this.returnOtpCreatedAt = returnOtpCreatedAt;
    }

    public int getReturnOtpFailedAttempts() {
        return returnOtpFailedAttempts;
    }

    public void setReturnOtpFailedAttempts(int returnOtpFailedAttempts) {
        this.returnOtpFailedAttempts = returnOtpFailedAttempts;
    }

    public Instant getReturnCompletedAt() {
        return returnCompletedAt;
    }

    public void setReturnCompletedAt(Instant returnCompletedAt) {
        this.returnCompletedAt = returnCompletedAt;
    }

    public String getRefundReference() {
        return refundReference;
    }

    public void setRefundReference(String refundReference) {
        this.refundReference = refundReference;
    }

    public String getDisputeReason() {
        return disputeReason;
    }

    public void setDisputeReason(String disputeReason) {
        this.disputeReason = disputeReason;
    }

    public String getDisputeRuling() {
        return disputeRuling;
    }

    public void setDisputeRuling(String disputeRuling) {
        this.disputeRuling = disputeRuling;
    }

    public Long getVersion() {
        return version != null ? version : 0L;
    }

    public void setVersion(Long version) {
        this.version = version != null ? version : 0L;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }

    public String getPaymentProofUrl() {
        return paymentProofUrl;
    }

    public void setPaymentProofUrl(String paymentProofUrl) {
        this.paymentProofUrl = paymentProofUrl;
    }

    public String getPickupLocation() {
        return pickupLocation;
    }

    public void setPickupLocation(String pickupLocation) {
        this.pickupLocation = pickupLocation;
    }

    public String getPickupDay() {
        return pickupDay;
    }

    public void setPickupDay(String pickupDay) {
        this.pickupDay = pickupDay;
    }

    public String getPickupTime() {
        return pickupTime;
    }

    public void setPickupTime(String pickupTime) {
        this.pickupTime = pickupTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Column(name = "is_preorder")
    private boolean isPreorder = false;

    public boolean isPreorder() {
        return isPreorder;
    }

    public void setPreorder(boolean preorder) {
        isPreorder = preorder;
    }
}
