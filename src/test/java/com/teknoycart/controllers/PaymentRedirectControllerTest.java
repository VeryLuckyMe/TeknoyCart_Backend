package com.teknoycart.controllers;

import com.teknoycart.models.Order;
import com.teknoycart.models.OrderStatus;
import com.teknoycart.services.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PaymentRedirectControllerTest {

    @Mock
    private OrderService orderService;

    @InjectMocks
    private PaymentRedirectController controller;

    @Test
    @DisplayName("Payment success redirect renders branded HTML and attempts auto sync")
    void testHandlePaymentSuccess() {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();

        Order order = new Order();
        order.setId(orderId);
        order.setBuyerId(buyerId);
        order.setStatus(OrderStatus.AWAITING_PAYMENT);

        when(orderService.getOrder(orderId)).thenReturn(order);
        when(orderService.syncPaymongoPayment(eq(orderId), eq(buyerId))).thenReturn(order);

        ResponseEntity<String> response = controller.handlePaymentSuccess(orderId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Payment Confirmed!"));
        assertTrue(response.getBody().contains("teknoycart://order?id=" + orderId));
        assertTrue(response.getBody().contains("TeknoyCart Escrow"));

        verify(orderService, times(1)).syncPaymongoPayment(eq(orderId), eq(buyerId));
    }

    @Test
    @DisplayName("Payment cancel redirect renders clean incomplete HTML")
    void testHandlePaymentCancel() {
        UUID orderId = UUID.randomUUID();

        ResponseEntity<String> response = controller.handlePaymentCancel(orderId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Payment Incomplete"));
        assertTrue(response.getBody().contains("teknoycart://order?id=" + orderId));
    }
}
