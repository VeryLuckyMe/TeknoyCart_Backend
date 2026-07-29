package com.teknoycart.models;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "stores")
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "store_id", updatable = false, nullable = false)
    private UUID storeId;

    @Column(name = "store_name", unique = true, nullable = false)
    private String storeName;

    @Column(name = "public_support_email")
    private String publicSupportEmail;

    @Column(name = "banner_url")
    private String bannerUrl;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "rating")
    private Double rating = 5.0;

    @OneToOne
    @JoinColumn(name = "owner_id", referencedColumnName = "user_id")
    private User owner;

    // Getters and Setters
    public UUID getStoreId() { return storeId; }
    public void setStoreId(UUID storeId) { this.storeId = storeId; }

    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }

    public String getPublicSupportEmail() { return publicSupportEmail; }
    public void setPublicSupportEmail(String publicSupportEmail) { this.publicSupportEmail = publicSupportEmail; }

    public String getBannerUrl() { return bannerUrl; }
    public void setBannerUrl(String bannerUrl) { this.bannerUrl = bannerUrl; }

    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }

    public Double getRating() { return rating; }
    public void setRating(Double rating) { this.rating = rating; }

    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
}
