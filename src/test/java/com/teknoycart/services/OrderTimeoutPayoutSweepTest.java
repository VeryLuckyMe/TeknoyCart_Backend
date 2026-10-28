package com.teknoycart.services;

import com.teknoycart.models.Order;
import com.teknoycart.models.OrderStatus;
import com.teknoycart.repositories.OrderAuditLogRepository;
import com.teknoycart.repositories.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderTimeoutPayoutSweepTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderAuditLogRepository auditLogRepository;

    @Mock
    private OrderService orderService;

    @InjectMocks
    private OrderTimeoutService timeoutService;

    @Test
    @DisplayName("Hourly sweep releases payout for completed orders confirmed via the SQL RPC")
    void sweepReleasesUnreleasedPayouts() {
        Order completed = new Order();
        completed.setStatus(OrderStatus.COMPLETED);

        when(orderRepository.findByStatus(any())).thenReturn(List.of());
        when(orderRepository.findCompletedWithoutPayout()).thenReturn(List.of(completed));

        timeoutService.processStuckOrders();

        verify(orderService).releasePayout(completed);
    }

    @Test
    @DisplayName("One failed payout does not stop the rest of the sweep")
    void sweepContinuesAfterIndividualFailure() {
        Order failing = new Order();
        Order succeeding = new Order();

        when(orderRepository.findByStatus(any())).thenReturn(List.of());
        when(orderRepository.findCompletedWithoutPayout()).thenReturn(List.of(failing, succeeding));
        doThrow(new RuntimeException("payout provider down")).when(orderService).releasePayout(failing);

        timeoutService.processStuckOrders();

        verify(orderService).releasePayout(succeeding);
    }
}
