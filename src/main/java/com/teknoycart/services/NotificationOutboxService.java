package com.teknoycart.services;

import com.teknoycart.models.Notification;
import com.teknoycart.repositories.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class NotificationOutboxService {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxService.class);

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private PushNotificationService pushNotificationService;

    @Autowired
    private EmailService emailService;

    public void setNotificationRepository(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public void setPushNotificationService(PushNotificationService pushNotificationService) {
        this.pushNotificationService = pushNotificationService;
    }

    public void setEmailService(EmailService emailService) {
        this.emailService = emailService;
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void processPendingDeliveries() {
        if (notificationRepository == null) {
            return;
        }

        List<Notification> pending;
        try {
            pending = notificationRepository.findPendingForUpdateSkipLocked(50);
        } catch (Exception e) {
            log.error("Failed to query pending outbox notifications: {}", e.getMessage());
            return;
        }

        if (pending.isEmpty()) {
            return;
        }

        log.info("Processing {} pending notification outbox deliveries...", pending.size());

        for (Notification notif : pending) {
            boolean statusUpdated = false;

            // 1. Fan-out Push Notifications via FCM
            if ("PENDING".equals(notif.getPushStatus())) {
                try {
                    boolean success = pushNotificationService.sendToUser(
                            notif.getUserId(),
                            notif.getTitle(),
                            notif.getBody(),
                            notif.getReferenceType(),
                            notif.getReferenceId()
                    );

                    if (success) {
                        notif.setPushStatus("SENT");
                        notif.setPushSentAt(Instant.now());
                        notif.setPushError(null);
                    } else {
                        notif.setPushError("FCM dispatch incomplete or failed");
                        notif.setPushStatus(notif.getRetryCount() >= 2 ? "FAILED" : "PENDING");
                    }
                } catch (Exception e) {
                    notif.setPushError(e.getMessage());
                    notif.setPushStatus(notif.getRetryCount() >= 2 ? "FAILED" : "PENDING");
                }
                statusUpdated = true;
            }

            // 2. Fan-out Transactional Email via Brevo
            if ("PENDING".equals(notif.getEmailStatus())) {
                try {
                    boolean success = emailService.sendNotificationEmail(
                            notif.getUserId(),
                            notif.getTitle(),
                            notif.getBody(),
                            notif.getReferenceType(),
                            notif.getReferenceId()
                    );

                    if (success) {
                        notif.setEmailStatus("SENT");
                        notif.setEmailSentAt(Instant.now());
                        notif.setEmailError(null);
                    } else {
                        notif.setEmailError("Brevo email dispatch failed or key unconfigured");
                        notif.setEmailStatus(notif.getRetryCount() >= 2 ? "FAILED" : "PENDING");
                    }
                } catch (Exception e) {
                    notif.setEmailError(e.getMessage());
                    notif.setEmailStatus(notif.getRetryCount() >= 2 ? "FAILED" : "PENDING");
                }
                statusUpdated = true;
            }

            if (statusUpdated) {
                notif.setRetryCount(notif.getRetryCount() + 1);
                notificationRepository.save(notif);
            }
        }
    }
}
