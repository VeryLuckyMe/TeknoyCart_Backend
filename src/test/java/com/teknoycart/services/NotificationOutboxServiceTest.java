package com.teknoycart.services;

import com.teknoycart.models.Notification;
import com.teknoycart.repositories.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

public class NotificationOutboxServiceTest {

    @Test
    @DisplayName("processPendingDeliveries does nothing when no outbox entries are pending")
    void testNoPendingEntries() {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findPendingForUpdateSkipLocked(anyInt())).thenReturn(Collections.emptyList());

        PushNotificationService pushService = mock(PushNotificationService.class);
        EmailService emailService = mock(EmailService.class);

        NotificationOutboxService outbox = new NotificationOutboxService();
        outbox.setNotificationRepository(repo);
        outbox.setPushNotificationService(pushService);
        outbox.setEmailService(emailService);

        outbox.processPendingDeliveries();

        verify(pushService, never()).sendToUser(any(), any(), any(), any(), any());
        verify(emailService, never()).sendNotificationEmail(any(), any(), any(), any(), any());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("processPendingDeliveries dispatches PENDING push successfully and updates state to SENT")
    void testSuccessfulPushDispatch() {
        UUID userId = UUID.randomUUID();
        Notification notif = new Notification(userId, "Order Placed", "Your order has been placed", "ORDER_STATUS", "ORDER", "order-1");
        notif.setPushStatus("PENDING");
        notif.setEmailStatus("NOT_APPLICABLE");
        notif.setRetryCount(0);

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findPendingForUpdateSkipLocked(50)).thenReturn(List.of(notif));

        PushNotificationService pushService = mock(PushNotificationService.class);
        when(pushService.sendToUser(eq(userId), eq("Order Placed"), eq("Your order has been placed"), eq("ORDER"), eq("order-1")))
                .thenReturn(true);

        EmailService emailService = mock(EmailService.class);

        NotificationOutboxService outbox = new NotificationOutboxService();
        outbox.setNotificationRepository(repo);
        outbox.setPushNotificationService(pushService);
        outbox.setEmailService(emailService);

        outbox.processPendingDeliveries();

        verify(pushService, times(1)).sendToUser(userId, "Order Placed", "Your order has been placed", "ORDER", "order-1");
        verify(emailService, never()).sendNotificationEmail(any(), any(), any(), any(), any());

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repo, times(1)).save(captor.capture());

        Notification saved = captor.getValue();
        assertEquals("SENT", saved.getPushStatus());
        assertNotNull(saved.getPushSentAt());
        assertNull(saved.getPushError());
        assertEquals(1, saved.getRetryCount());
    }

    @Test
    @DisplayName("processPendingDeliveries dispatches both push and email when both are PENDING")
    void testSuccessfulPushAndEmailDispatch() {
        UUID userId = UUID.randomUUID();
        Notification notif = new Notification(userId, "Payment Escrowed", "Payment secured in escrow", "PAYMENT", "ORDER", "order-2");
        notif.setPushStatus("PENDING");
        notif.setEmailStatus("PENDING");
        notif.setRetryCount(0);

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findPendingForUpdateSkipLocked(50)).thenReturn(List.of(notif));

        PushNotificationService pushService = mock(PushNotificationService.class);
        when(pushService.sendToUser(any(), any(), any(), any(), any())).thenReturn(true);

        EmailService emailService = mock(EmailService.class);
        when(emailService.sendNotificationEmail(any(), any(), any(), any(), any())).thenReturn(true);

        NotificationOutboxService outbox = new NotificationOutboxService();
        outbox.setNotificationRepository(repo);
        outbox.setPushNotificationService(pushService);
        outbox.setEmailService(emailService);

        outbox.processPendingDeliveries();

        verify(pushService, times(1)).sendToUser(userId, "Payment Escrowed", "Payment secured in escrow", "ORDER", "order-2");
        verify(emailService, times(1)).sendNotificationEmail(userId, "Payment Escrowed", "Payment secured in escrow", "ORDER", "order-2");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repo, times(1)).save(captor.capture());

        Notification saved = captor.getValue();
        assertEquals("SENT", saved.getPushStatus());
        assertEquals("SENT", saved.getEmailStatus());
        assertNotNull(saved.getPushSentAt());
        assertNotNull(saved.getEmailSentAt());
        assertEquals(1, saved.getRetryCount());
    }

    @Test
    @DisplayName("processPendingDeliveries marks delivery as FAILED when max retries exceeded on error")
    void testMaxRetriesFailed() {
        UUID userId = UUID.randomUUID();
        Notification notif = new Notification(userId, "Order Cancelled", "Order cancelled", "ORDER_STATUS", "ORDER", "order-3");
        notif.setPushStatus("PENDING");
        notif.setEmailStatus("NOT_APPLICABLE");
        notif.setRetryCount(2); // Already retried twice

        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.findPendingForUpdateSkipLocked(50)).thenReturn(List.of(notif));

        PushNotificationService pushService = mock(PushNotificationService.class);
        when(pushService.sendToUser(any(), any(), any(), any(), any())).thenThrow(new RuntimeException("Connection timeout to FCM"));

        EmailService emailService = mock(EmailService.class);

        NotificationOutboxService outbox = new NotificationOutboxService();
        outbox.setNotificationRepository(repo);
        outbox.setPushNotificationService(pushService);
        outbox.setEmailService(emailService);

        outbox.processPendingDeliveries();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repo, times(1)).save(captor.capture());

        Notification saved = captor.getValue();
        assertEquals("FAILED", saved.getPushStatus(), "Should be marked FAILED on 3rd attempt");
        assertEquals("Connection timeout to FCM", saved.getPushError());
        assertEquals(3, saved.getRetryCount());
    }
}
