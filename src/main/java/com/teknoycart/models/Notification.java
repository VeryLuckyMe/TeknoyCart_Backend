package com.teknoycart.models;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "title", length = 150, nullable = false)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "type", length = 50, nullable = false)
    private String type;

    @Column(name = "reference_id", length = 100)
    private String referenceId;

    @Column(name = "reference_type", length = 50)
    private String referenceType;

    @Column(name = "dedupe_key", length = 150)
    private String dedupeKey;

    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    @Column(name = "push_status", length = 20, nullable = false)
    private String pushStatus = "NOT_APPLICABLE";

    @Column(name = "email_status", length = 20, nullable = false)
    private String emailStatus = "NOT_APPLICABLE";

    @Column(name = "push_sent_at")
    private Instant pushSentAt;

    @Column(name = "email_sent_at")
    private Instant emailSentAt;

    @Column(name = "push_error", columnDefinition = "TEXT")
    private String pushError;

    @Column(name = "email_error", columnDefinition = "TEXT")
    private String emailError;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public Notification() {}

    public Notification(UUID userId, String title, String body, String type, String referenceType, String referenceId) {
        this.userId = userId;
        this.title = title;
        this.body = body;
        this.type = type;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.isRead = false;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(String referenceId) {
        this.referenceId = referenceId;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(String referenceType) {
        this.referenceType = referenceType;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public void setDedupeKey(String dedupeKey) {
        this.dedupeKey = dedupeKey;
    }

    public boolean isRead() {
        return isRead;
    }

    public void setRead(boolean read) {
        isRead = read;
    }

    public String getPushStatus() {
        return pushStatus;
    }

    public void setPushStatus(String pushStatus) {
        this.pushStatus = pushStatus;
    }

    public String getEmailStatus() {
        return emailStatus;
    }

    public void setEmailStatus(String emailStatus) {
        this.emailStatus = emailStatus;
    }

    public Instant getPushSentAt() {
        return pushSentAt;
    }

    public void setPushSentAt(Instant pushSentAt) {
        this.pushSentAt = pushSentAt;
    }

    public Instant getEmailSentAt() {
        return emailSentAt;
    }

    public void setEmailSentAt(Instant emailSentAt) {
        this.emailSentAt = emailSentAt;
    }

    public String getPushError() {
        return pushError;
    }

    public void setPushError(String pushError) {
        this.pushError = pushError;
    }

    public String getEmailError() {
        return emailError;
    }

    public void setEmailError(String emailError) {
        this.emailError = emailError;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
