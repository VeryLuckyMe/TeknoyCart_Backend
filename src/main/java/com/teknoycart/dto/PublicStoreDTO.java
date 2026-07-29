package com.teknoycart.dto;

import java.util.UUID;

public class PublicStoreDTO {
    private UUID storeId;
    private String storeName;
    private String publicSupportEmail;
    private String bannerUrl;
    private String logoUrl;
    private Double rating;

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
}
