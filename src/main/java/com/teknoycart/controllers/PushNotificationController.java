package com.teknoycart.controllers;

import com.teknoycart.models.UserFcmToken;
import com.teknoycart.repositories.UserFcmTokenRepository;
import com.teknoycart.security.UserPrincipal;
import com.teknoycart.services.NotificationOutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/notifications")
public class PushNotificationController {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationController.class);

    @Autowired
    private UserFcmTokenRepository userFcmTokenRepository;

    @Autowired
    private NotificationOutboxService notificationOutboxService;

    private UUID getAuthenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        UUID userId = null;
        if (auth.getPrincipal() instanceof UserPrincipal) {
            userId = ((UserPrincipal) auth.getPrincipal()).getId();
        }
        if (userId == null) {
            String principal = auth.getName();
            try {
                userId = UUID.fromString(principal);
            } catch (IllegalArgumentException ignored) {}
        }
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid user identifier in security context");
        }
        return userId;
    }

    @PostMapping("/fcm-token")
    public ResponseEntity<Map<String, Object>> registerFcmToken(@RequestBody Map<String, String> payload) {
        UUID actorId = getAuthenticatedUserId();
        String token = payload != null ? payload.get("token") : null;
        String platform = payload != null ? payload.getOrDefault("platform", "ANDROID") : "ANDROID";

        if (token == null || token.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "FCM token is required");
        }

        token = token.trim();
        Optional<UserFcmToken> existingOpt = userFcmTokenRepository.findByToken(token);
        UserFcmToken record;
        if (existingOpt.isPresent()) {
            record = existingOpt.get();
            record.setUserId(actorId);
            record.setPlatform(platform);
            record.setUpdatedAt(Instant.now());
        } else {
            record = new UserFcmToken(actorId, token, platform);
        }

        userFcmTokenRepository.save(record);
        log.info("FCM token registered/updated for user {}: token prefix {}", actorId, token.substring(0, Math.min(8, token.length())));

        return ResponseEntity.ok(Map.of(
                "status", "registered",
                "userId", actorId.toString(),
                "platform", platform
        ));
    }

    @DeleteMapping("/fcm-token")
    public ResponseEntity<Map<String, Object>> unregisterFcmToken(@RequestParam(required = false) String token) {
        UUID actorId = getAuthenticatedUserId();

        if (token != null && !token.trim().isEmpty()) {
            userFcmTokenRepository.deleteByUserIdAndToken(actorId, token.trim());
            log.info("FCM token unregistered for user {}: token prefix {}", actorId, token.substring(0, Math.min(8, token.length())));
        } else {
            // Delete all tokens for this user
            var tokens = userFcmTokenRepository.findByUserId(actorId);
            userFcmTokenRepository.deleteAll(tokens);
            log.info("All FCM tokens ({}) cleared for user {}", tokens.size(), actorId);
        }

        return ResponseEntity.ok(Map.of(
                "status", "deleted",
                "userId", actorId.toString()
        ));
    }

    @PostMapping("/outbox/process")
    public ResponseEntity<Map<String, String>> triggerOutboxProcess() {
        notificationOutboxService.processPendingDeliveries();
        return ResponseEntity.ok(Map.of("status", "processed"));
    }
}
