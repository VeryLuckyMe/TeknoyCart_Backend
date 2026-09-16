package com.teknoycart.controllers;

import com.teknoycart.models.Order;
import com.teknoycart.security.UserPrincipal;
import com.teknoycart.services.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    private UUID getAuthenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        if (auth.getPrincipal() instanceof UserPrincipal) {
            return ((UserPrincipal) auth.getPrincipal()).getId();
        }
        String principal = auth.getName();
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid user identifier in security context");
        }
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<Order> acceptOrder(@PathVariable UUID id) {
        UUID actorId = getAuthenticatedUserId();
        return ResponseEntity.ok(orderService.acceptOrder(id, actorId));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Order> cancelOrder(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> payload) {
        UUID actorId = getAuthenticatedUserId();
        String reason = payload != null ? payload.get("reason") : null;
        return ResponseEntity.ok(orderService.cancelOrder(id, actorId, reason));
    }

    @PostMapping("/{id}/schedule")
    public ResponseEntity<Order> scheduleMeetup(@PathVariable UUID id) {
        UUID actorId = getAuthenticatedUserId();
        return ResponseEntity.ok(orderService.scheduleMeetup(id, actorId));
    }

    @PostMapping("/{id}/verify-handoff")
    public ResponseEntity<Order> verifyHandoff(@PathVariable UUID id, @RequestBody Map<String, String> payload) {
        UUID actorId = getAuthenticatedUserId();
        String otp = payload != null ? payload.get("otp") : null;
        return ResponseEntity.ok(orderService.verifyHandoff(id, actorId, otp));
    }

    @PostMapping("/{id}/confirm-receipt")
    public ResponseEntity<Order> confirmReceipt(@PathVariable UUID id) {
        UUID actorId = getAuthenticatedUserId();
        return ResponseEntity.ok(orderService.confirmReceipt(id, actorId));
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<Order> requestRefund(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> payload) {
        UUID actorId = getAuthenticatedUserId();
        String reason = payload != null ? payload.get("reason") : null;
        String evidence = payload != null ? payload.get("evidence") : null;
        return ResponseEntity.ok(orderService.requestRefund(id, actorId, reason, evidence));
    }

    @PostMapping("/{id}/submit-payment")
    public ResponseEntity<Order> submitPayment(@PathVariable UUID id, @RequestBody Map<String, String> payload) {
        UUID actorId = getAuthenticatedUserId();
        String reference = payload != null ? payload.getOrDefault("payment_reference", payload.get("gcash_reference")) : null;
        String proofUrl = payload != null ? payload.get("payment_proof_url") : null;
        return ResponseEntity.ok(orderService.submitPayment(id, actorId, reference, proofUrl));
    }

    @PostMapping("/{id}/verify-payment")
    public ResponseEntity<Order> verifyPayment(@PathVariable UUID id) {
        UUID actorId = getAuthenticatedUserId();
        return ResponseEntity.ok(orderService.verifyPayment(id, actorId));
    }

    @PostMapping("/{id}/confirm-refund")
    public ResponseEntity<Order> confirmRefund(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> payload) {
        UUID actorId = getAuthenticatedUserId();
        String refundReference = payload != null ? payload.get("refund_reference") : null;
        return ResponseEntity.ok(orderService.confirmRefund(id, actorId, refundReference));
    }
}
