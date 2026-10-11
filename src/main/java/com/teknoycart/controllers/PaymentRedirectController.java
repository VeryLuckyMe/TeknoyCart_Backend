package com.teknoycart.controllers;

import com.teknoycart.models.Order;
import com.teknoycart.models.OrderStatus;
import com.teknoycart.services.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Controller to handle browser return redirects from external payment gateways (e.g. PayMongo).
 *
 * <p>When a buyer clicks "Return to Merchant" after completing checkout, PayMongo redirects
 * to this endpoint. This controller automatically syncs the order state with PayMongo
 * and displays a branded TeknoyCart confirmation page that automatically redirects
 * the user back into the mobile app via deep linking (teknoycart://order?id=...).
 */
@RestController
@RequestMapping("/payments")
public class PaymentRedirectController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentRedirectController.class);

    @Autowired
    private OrderService orderService;

    @GetMapping(value = "/success", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> handlePaymentSuccess(@RequestParam(name = "order", required = false) UUID orderId) {
        boolean verified = false;
        if (orderId != null) {
            try {
                Order order = orderService.getOrder(orderId);
                if (order != null) {
                    if (order.getStatus() == OrderStatus.AWAITING_PAYMENT) {
                        order = orderService.syncPaymongoPayment(orderId, order.getBuyerId());
                    }
                    verified = (order.getStatus() == OrderStatus.ESCROWED ||
                                order.getStatus() == OrderStatus.MEETUP_SCHEDULED ||
                                order.getStatus() == OrderStatus.COMPLETED);
                }
            } catch (Exception e) {
                logger.warn("Auto-sync error on payment success redirect for order {}: {}", orderId, e.getMessage());
            }
        }

        String html = buildSuccessHtml(orderId, verified);
        return ResponseEntity.ok(html);
    }

    @GetMapping(value = "/cancel", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> handlePaymentCancel(@RequestParam(name = "order", required = false) UUID orderId) {
        String html = buildCancelHtml(orderId);
        return ResponseEntity.ok(html);
    }

    private String buildSuccessHtml(UUID orderId, boolean verified) {
        String orderIdStr = orderId != null ? orderId.toString() : "";
        String deepLink = orderId != null ? "teknoycart://order?id=" + orderIdStr : "teknoycart://home";

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Payment Confirmed — TeknoyCart</title>
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@600;700;800&family=Inter:wght@400;500;600&display=swap" rel="stylesheet">
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
                        background: linear-gradient(135deg, #0F0F12 0%, #1A1A22 100%);
                        color: #FFFFFF;
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: 24px;
                    }
                    .card {
                        background: #181820;
                        border: 1px solid rgba(255, 255, 255, 0.08);
                        border-radius: 24px;
                        max-width: 440px;
                        width: 100%;
                        padding: 36px 28px;
                        text-align: center;
                        box-shadow: 0 24px 60px rgba(0, 0, 0, 0.5);
                    }
                    .badge-icon {
                        width: 80px;
                        height: 80px;
                        margin: 0 auto 20px;
                        background: linear-gradient(135deg, #10B981 0%, #059669 100%);
                        border-radius: 50%;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        box-shadow: 0 10px 25px rgba(16, 185, 129, 0.35);
                        animation: pop 0.4s cubic-bezier(0.175, 0.885, 0.32, 1.275);
                    }
                    @keyframes pop {
                        0% { transform: scale(0.6); opacity: 0; }
                        100% { transform: scale(1); opacity: 1; }
                    }
                    .badge-icon svg {
                        width: 42px;
                        height: 42px;
                        stroke: #FFFFFF;
                        stroke-width: 3;
                        fill: none;
                        stroke-linecap: round;
                        stroke-linejoin: round;
                    }
                    h1 {
                        font-family: 'Outfit', sans-serif;
                        font-size: 24px;
                        font-weight: 700;
                        color: #FFFFFF;
                        margin-bottom: 8px;
                    }
                    .subtitle {
                        font-size: 14px;
                        color: #9CA3AF;
                        line-height: 1.5;
                        margin-bottom: 24px;
                    }
                    .escrow-pill {
                        background: rgba(16, 185, 129, 0.12);
                        border: 1px solid rgba(16, 185, 129, 0.3);
                        border-radius: 12px;
                        padding: 12px 16px;
                        margin-bottom: 28px;
                        display: flex;
                        align-items: center;
                        text-align: left;
                        gap: 12px;
                    }
                    .escrow-pill svg {
                        width: 22px;
                        height: 22px;
                        stroke: #10B981;
                        stroke-width: 2;
                        fill: none;
                        flex-shrink: 0;
                    }
                    .escrow-pill div {
                        font-size: 12.5px;
                        color: #A7F3D0;
                        line-height: 1.4;
                    }
                    .btn-primary {
                        display: block;
                        width: 100%;
                        padding: 16px 20px;
                        background: linear-gradient(135deg, #800000 0%, #A00000 100%);
                        color: #FFFFFF;
                        font-family: 'Outfit', sans-serif;
                        font-weight: 700;
                        font-size: 16px;
                        border-radius: 14px;
                        text-decoration: none;
                        border: none;
                        cursor: pointer;
                        box-shadow: 0 10px 25px rgba(128, 0, 0, 0.4);
                        transition: transform 0.15s ease;
                    }
                    .btn-primary:active {
                        transform: scale(0.98);
                    }
                    .hint {
                        margin-top: 16px;
                        font-size: 12px;
                        color: #6B7280;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="badge-icon">
                        <svg viewBox="0 0 24 24">
                            <polyline points="20 6 9 17 4 12"></polyline>
                        </svg>
                    </div>
                    <h1>Payment Confirmed!</h1>
                    <p class="subtitle">Your GCash transaction was processed successfully. Funds are held safely in TeknoyCart Escrow.</p>
                    <div class="escrow-pill">
                        <svg viewBox="0 0 24 24">
                            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path>
                        </svg>
                        <div>
                            <strong>Buyer Protection Active</strong><br>
                            The seller will prepare your order for on-campus meetup handoff.
                        </div>
                    </div>
                    <a href="{{DEEP_LINK}}" class="btn-primary" id="returnBtn">Return to TeknoyCart App</a>
                    <p class="hint">Returning to app automatically... If not, tap the button above.</p>
                </div>
                <script>
                    setTimeout(function() {
                        window.location.href = "{{DEEP_LINK}}";
                    }, 1000);
                </script>
            </body>
            </html>
            """.replace("{{DEEP_LINK}}", deepLink);
    }

    private String buildCancelHtml(UUID orderId) {
        String orderIdStr = orderId != null ? orderId.toString() : "";
        String deepLink = orderId != null ? "teknoycart://order?id=" + orderIdStr : "teknoycart://home";

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Payment Cancelled — TeknoyCart</title>
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@600;700&family=Inter:wght@400;500&display=swap" rel="stylesheet">
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
                        background: #0F0F12;
                        color: #FFFFFF;
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: 24px;
                    }
                    .card {
                        background: #181820;
                        border: 1px solid rgba(255, 255, 255, 0.08);
                        border-radius: 24px;
                        max-width: 440px;
                        width: 100%;
                        padding: 36px 28px;
                        text-align: center;
                        box-shadow: 0 24px 60px rgba(0, 0, 0, 0.5);
                    }
                    .badge-icon {
                        width: 80px;
                        height: 80px;
                        margin: 0 auto 20px;
                        background: linear-gradient(135deg, #F59E0B 0%, #D97706 100%);
                        border-radius: 50%;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                    }
                    .badge-icon svg {
                        width: 40px;
                        height: 40px;
                        stroke: #FFFFFF;
                        stroke-width: 2.5;
                        fill: none;
                    }
                    h1 {
                        font-family: 'Outfit', sans-serif;
                        font-size: 22px;
                        font-weight: 700;
                        color: #FFFFFF;
                        margin-bottom: 8px;
                    }
                    .subtitle {
                        font-size: 14px;
                        color: #9CA3AF;
                        line-height: 1.5;
                        margin-bottom: 28px;
                    }
                    .btn-primary {
                        display: block;
                        width: 100%;
                        padding: 16px 20px;
                        background: #374151;
                        color: #FFFFFF;
                        font-family: 'Outfit', sans-serif;
                        font-weight: 700;
                        font-size: 15px;
                        border-radius: 14px;
                        text-decoration: none;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="badge-icon">
                        <svg viewBox="0 0 24 24">
                            <line x1="18" y1="6" x2="6" y2="18"></line>
                            <line x1="6" y1="6" x2="18" y2="18"></line>
                        </svg>
                    </div>
                    <h1>Payment Incomplete</h1>
                    <p class="subtitle">The checkout session was cancelled. You can return to the TeknoyCart app to try again or choose another payment method.</p>
                    <a href="{{DEEP_LINK}}" class="btn-primary">Return to TeknoyCart App</a>
                </div>
            </body>
            </html>
            """.replace("{{DEEP_LINK}}", deepLink);
    }
}
