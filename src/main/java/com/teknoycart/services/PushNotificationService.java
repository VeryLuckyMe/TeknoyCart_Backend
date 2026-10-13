package com.teknoycart.services;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.teknoycart.models.UserFcmToken;
import com.teknoycart.repositories.UserFcmTokenRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    @Value("${firebase.credentials.json:${FIREBASE_CREDENTIALS_JSON:}}")
    private String firebaseCredentialsJson;

    @Value("${firebase.credentials.path:${FIREBASE_CREDENTIALS_PATH:}}")
    private String firebaseCredentialsPath;

    @Autowired
    private UserFcmTokenRepository userFcmTokenRepository;

    private boolean initialized = false;

    public void setUserFcmTokenRepository(UserFcmTokenRepository userFcmTokenRepository) {
        this.userFcmTokenRepository = userFcmTokenRepository;
    }

    @PostConstruct
    public void init() {
        try {
            if (!FirebaseApp.getApps().isEmpty()) {
                initialized = true;
                log.info("FirebaseApp is already initialized.");
                return;
            }

            InputStream serviceAccount = null;
            if (firebaseCredentialsJson != null && !firebaseCredentialsJson.trim().isEmpty() && !firebaseCredentialsJson.startsWith("${")) {
                serviceAccount = new ByteArrayInputStream(firebaseCredentialsJson.getBytes(StandardCharsets.UTF_8));
                log.info("Initializing Firebase from inline credentials JSON.");
            } else if (firebaseCredentialsPath != null && !firebaseCredentialsPath.trim().isEmpty() && !firebaseCredentialsPath.startsWith("${")) {
                if (firebaseCredentialsPath.startsWith("classpath:")) {
                    String cpPath = firebaseCredentialsPath.substring("classpath:".length());
                    serviceAccount = getClass().getClassLoader().getResourceAsStream(cpPath);
                } else {
                    serviceAccount = new FileInputStream(firebaseCredentialsPath);
                }
                log.info("Initializing Firebase from credentials path: {}", firebaseCredentialsPath);
            }

            if (serviceAccount == null) {
                InputStream cpStream = getClass().getClassLoader().getResourceAsStream("firebase-service-account.json");
                if (cpStream != null) {
                    serviceAccount = cpStream;
                    log.info("Initializing Firebase from classpath: firebase-service-account.json");
                }
            }

            if (serviceAccount != null) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .build();
                FirebaseApp.initializeApp(options);
                initialized = true;
                log.info("Firebase Admin SDK successfully initialized for TeknoyCart Push Notifications.");
            } else {
                log.warn("=========================================================================");
                log.warn("FIREBASE CREDENTIALS NOT CONFIGURED: PushNotificationService running in mock/simulation mode.");
                log.warn("Configure 'FIREBASE_CREDENTIALS_JSON' or 'FIREBASE_CREDENTIALS_PATH' in production.");
                log.warn("=========================================================================");
            }
        } catch (Exception e) {
            log.error("Failed to initialize Firebase Admin SDK: {}. Falling back to simulation mode.", e.getMessage());
            initialized = false;
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setFirebaseCredentialsJson(String json) {
        this.firebaseCredentialsJson = json;
    }

    public boolean sendToUser(UUID userId, String title, String body, String referenceType, String referenceId) {
        if (userFcmTokenRepository == null) {
            log.warn("UserFcmTokenRepository is null, push skipped.");
            return true;
        }

        List<UserFcmToken> tokens = userFcmTokenRepository.findByUserId(userId);
        if (tokens.isEmpty()) {
            log.info("No registered FCM tokens found for user: {}. Push skipped.", userId);
            return true;
        }

        if (!initialized) {
            log.info("[FCM SIMULATION] Mock Push to {} device(s) for user {}: title='{}', body='{}', refType={}, refId={}",
                    tokens.size(), userId, title, body, referenceType, referenceId);
            return true;
        }

        boolean allSucceeded = true;
        for (UserFcmToken tokenRecord : tokens) {
            String token = tokenRecord.getToken();
            try {
                com.google.firebase.messaging.Notification fcmNotif = com.google.firebase.messaging.Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build();

                Message.Builder messageBuilder = Message.builder()
                        .setToken(token)
                        .setNotification(fcmNotif)
                        .putData("title", title != null ? title : "")
                        .putData("body", body != null ? body : "")
                        .putData("click_action", "FLUTTER_NOTIFICATION_CLICK");

                if (referenceType != null) {
                    messageBuilder.putData("referenceType", referenceType);
                }
                if (referenceId != null) {
                    messageBuilder.putData("referenceId", referenceId);
                }

                String response = FirebaseMessaging.getInstance().send(messageBuilder.build());
                log.info("FCM push successfully sent to user {} (token prefix: {}...): messageId={}",
                        userId, token.substring(0, Math.min(8, token.length())), response);

            } catch (FirebaseMessagingException e) {
                log.warn("FCM push error for user {} token: {}: {}", userId, e.getMessagingErrorCode(), e.getMessage());
                // Prune stale / unregistered device tokens automatically
                if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED ||
                    (e.getErrorCode() != null && "UNREGISTERED".equalsIgnoreCase(e.getErrorCode().name()))) {
                    log.info("Pruning unregistered FCM token for user {}: {}", userId, token);
                    try {
                        userFcmTokenRepository.deleteByToken(token);
                    } catch (Exception ex) {
                        log.warn("Failed to prune stale token: {}", ex.getMessage());
                    }
                }
                allSucceeded = false;
            } catch (Exception e) {
                log.error("Unexpected exception sending FCM push to user {}: {}", userId, e.getMessage());
                allSucceeded = false;
            }
        }

        return allSucceeded;
    }
}
