package com.teknoycart.models;

import jakarta.persistence.*;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private OrderStatus status;

    @Column(name = "seller_handed_off")
    private boolean sellerHandedOff = false;

    @Column(name = "buyer_confirmed_receipt")
    private boolean buyerConfirmedReceipt = false;

    @Column(name = "handoff_otp")
    private String handoffOtp;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getBuyerId() { return buyerId; }
    public void setBuyerId(UUID buyerId) { this.buyerId = buyerId; }

    public UUID getSellerId() { return sellerId; }
    public void setSellerId(UUID sellerId) { this.sellerId = sellerId; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public boolean isSellerHandedOff() { return sellerHandedOff; }
    public void setSellerHandedOff(boolean sellerHandedOff) { this.sellerHandedOff = sellerHandedOff; }

    public boolean isBuyerConfirmedReceipt() { return buyerConfirmedReceipt; }
    public void setBuyerConfirmedReceipt(boolean buyerConfirmedReceipt) { this.buyerConfirmedReceipt = buyerConfirmedReceipt; }

    public String getHandoffOtp() { return handoffOtp; }
    public void setHandoffOtp(String handoffOtp) { this.handoffOtp = handoffOtp; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
