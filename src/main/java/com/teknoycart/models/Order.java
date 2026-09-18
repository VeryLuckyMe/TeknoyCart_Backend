package com.teknoycart.models;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "orders")
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

    @Column(name = "payment_reference")
    private String paymentReference;

    @Column(name = "payment_proof_url")
    private String paymentProofUrl;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

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
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
