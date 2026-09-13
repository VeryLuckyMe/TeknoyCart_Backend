package com.teknoycart.controllers;

import com.teknoycart.models.Order;
import com.teknoycart.services.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@CrossOrigin
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping("/{id}/accept")
    public ResponseEntity<Order> acceptOrder(@PathVariable UUID id, @RequestBody Map<String, String> payload) {
        UUID sellerId = UUID.fromString(payload.get("sellerId"));
        return ResponseEntity.ok(orderService.acceptOrder(id, sellerId));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Order> cancelOrder(@PathVariable UUID id, @RequestBody Map<String, String> payload) {
        UUID actorId = UUID.fromString(payload.get("actorId"));
        String reason = payload.get("reason");
        return ResponseEntity.ok(orderService.cancelOrder(id, actorId, reason));
    }

    @PostMapping("/{id}/schedule")
    public ResponseEntity<Order> scheduleMeetup(@PathVariable UUID id, @RequestBody Map<String, String> payload) {
        UUID actorId = UUID.fromString(payload.get("actorId"));
        return ResponseEntity.ok(orderService.scheduleMeetup(id, actorId));
    }

    @PostMapping("/{id}/verify-handoff")
    public ResponseEntity<Order> verifyHandoff(@PathVariable UUID id, @RequestBody Map<String, String> payload) {
        UUID sellerId = UUID.fromString(payload.get("sellerId"));
        String otp = payload.get("otp");
        return ResponseEntity.ok(orderService.verifyHandoff(id, sellerId, otp));
    }

    @PostMapping("/{id}/confirm-receipt")
    public ResponseEntity<Order> confirmReceipt(@PathVariable UUID id, @RequestBody Map<String, String> payload) {
        UUID buyerId = UUID.fromString(payload.get("buyerId"));
        return ResponseEntity.ok(orderService.confirmReceipt(id, buyerId));
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<Order> requestRefund(@PathVariable UUID id, @RequestBody Map<String, String> payload) {
        UUID buyerId = UUID.fromString(payload.get("buyerId"));
        String reason = payload.get("reason");
        String evidence = payload.get("evidence");
        return ResponseEntity.ok(orderService.requestRefund(id, buyerId, reason, evidence));
    }
}
