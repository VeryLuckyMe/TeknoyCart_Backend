package com.teknoycart.services;

import com.teknoycart.models.UserFcmToken;
import com.teknoycart.repositories.UserFcmTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class PushNotificationServiceTest {

    @Test
    @DisplayName("sendToUser skips push and returns true when user has no registered FCM tokens")
    void testSendToUserWithNoTokens() {
        UserFcmTokenRepository repo = mock(UserFcmTokenRepository.class);
        when(repo.findByUserId(any(UUID.class))).thenReturn(Collections.emptyList());

        PushNotificationService service = new PushNotificationService();
        service.setUserFcmTokenRepository(repo);

        UUID userId = UUID.randomUUID();
        boolean result = service.sendToUser(userId, "Test Title", "Test Body", "ORDER", "order-123");

        assertTrue(result, "Expected true when no tokens exist");
        verify(repo, times(1)).findByUserId(userId);
    }

    @Test
    @DisplayName("sendToUser runs in mock simulation mode when Firebase is not initialized")
    void testSendToUserSimulationMode() {
        UserFcmTokenRepository repo = mock(UserFcmTokenRepository.class);
        UUID userId = UUID.randomUUID();
        UserFcmToken token = new UserFcmToken(userId, "fcm-mock-token-abc", "ANDROID");
        when(repo.findByUserId(userId)).thenReturn(List.of(token));

        PushNotificationService service = new PushNotificationService();
        service.setUserFcmTokenRepository(repo);
        // Firebase is uninitialized
        assertFalse(service.isInitialized(), "Service should be uninitialized without credentials");

        boolean result = service.sendToUser(userId, "Order Placed", "Your order has been placed", "ORDER", "order-999");
        assertTrue(result, "Simulation mode should return true without throwing exceptions");
    }

    @Test
    @DisplayName("PushNotificationService handles null repository gracefully")
    void testNullRepositoryGraceful() {
        PushNotificationService service = new PushNotificationService();
        service.setUserFcmTokenRepository(null);

        boolean result = service.sendToUser(UUID.randomUUID(), "Test", "Test", null, null);
        assertTrue(result);
    }
}
