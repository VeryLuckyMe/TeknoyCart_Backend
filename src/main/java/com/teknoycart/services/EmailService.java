package com.teknoycart.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Value("${brevo.api.key:}")
    private String brevoApiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public void setBrevoApiKey(String brevoApiKey) {
        this.brevoApiKey = brevoApiKey;
    }

    @Async
    public CompletableFuture<Boolean> sendVerificationEmail(String recipientEmail, String recipientName, String verificationToken) {
        boolean success = sendVerificationEmailSync(recipientEmail, recipientName, verificationToken);
        return CompletableFuture.completedFuture(success);
    }

    public boolean sendVerificationEmailSync(String recipientEmail, String recipientName, String verificationToken) {
        String verifyUrl = "https://teknoycart-backend.onrender.com/api/auth/verify?token=" + verificationToken;
        log.info("=========================================================================");
        log.info("VERIFICATION DISPATCH TRIGGERED FOR {}: [TOKEN REDACTED]", recipientEmail);
        log.info("=========================================================================");

        if (brevoApiKey == null || brevoApiKey.trim().isEmpty() || "PLACEHOLDER".equalsIgnoreCase(brevoApiKey.trim())) {
            log.error("********************************************************************************");
            log.error("BREVO EMAIL DISPATCH FAILED: 'brevo.api.key' (BREVO_API_KEY) is not configured!");
            log.error("Recipient: [REDACTED] <{}>", recipientEmail);
            log.error("Notice: Email dispatch was skipped to prevent blocking user registration.");
            log.error("Please ensure BREVO_API_KEY is configured in your application environment.");
            log.error("********************************************************************************");
            return false;
        }

        try {
            String safeRecipientName = org.springframework.web.util.HtmlUtils.htmlEscape(
                    recipientName != null ? recipientName : "Student"
            );

            // Premium HTML Email Template with CSS branding matching CIT-U
            String htmlContent = "<div style=\"font-family: 'Outfit', 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; max-width: 600px; margin: 0 auto; padding: 24px; border: 1px solid #ECECEF; border-radius: 16px;\">"
                    + "  <div style=\"text-align: center; margin-bottom: 24px;\">"
                    + "    <h2 style=\"color: #B22222; margin: 0; font-size: 26px;\">Wildcat Marketplace</h2>"
                    + "    <p style=\"color: #FFC107; font-weight: bold; margin: 4px 0 0 0; letter-spacing: 1px;\">CEBU INSTITUTE OF TECHNOLOGY - UNIVERSITY</p>"
                    + "  </div>"
                    + "  <hr style=\"border: none; border-top: 1px solid #ECECEF; margin-bottom: 24px;\"/>"
                    + "  <h3 style=\"color: #1A1A1E;\">Hello " + safeRecipientName + ",</h3>"
                    + "  <p style=\"color: #5A413D; font-size: 15px; line-height: 1.6;\">"
                    + "    Thank you for registering at TeknoyCart! To finalize your secure campus account and start browsing pre-loved textbooks, uniforms, and student deals, please verify your email address."
                    + "  </p>"
                    + "  <div style=\"text-align: center; margin: 32px 0;\">"
                    + "    <a href=\"" + verifyUrl + "\" style=\"background-color: #B22222; color: #FFFFFF; text-decoration: none; padding: 14px 28px; font-weight: bold; border-radius: 10px; font-size: 15px; display: inline-block; box-shadow: 0 4px 12px rgba(178, 34, 34, 0.2);\">Verify Your Account</a>"
                    + "  </div>"
                    + "  <p style=\"color: #5A413D; font-size: 13px; line-height: 1.6;\">"
                    + "    If the button doesn't work, copy and paste the following link into your browser:<br/>"
                    + "    <a href=\"" + verifyUrl + "\" style=\"color: #B22222;\">" + verifyUrl + "</a>"
                    + "  </p>"
                    + "  <hr style=\"border: none; border-top: 1px solid #ECECEF; margin: 24px 0;\"/>"
                    + "  <p style=\"font-size: 11px; color: #8A8A94; text-align: center; line-height: 1.4;\">"
                    + "    This is an automated security email. If you did not register for a TeknoyCart account, please ignore this email.<br/>"
                    + "    &copy; 2026 TeknoyCart CIT-U. All Rights Reserved."
                    + "  </p>"
                    + "</div>";

            // Prepare JSON payload for Brevo Transactional Email REST API
            Map<String, Object> payload = Map.of(
                "sender", Map.of("name", "TeknoyCart CIT-U", "email", "clarencekirkmc@gmail.com"),
                "to", List.of(Map.of("email", recipientEmail, "name", recipientName)),
                "subject", "Verify Your TeknoyCart Account",
                "htmlContent", htmlContent
            );

            String requestBody = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.brevo.com/v3/smtp/email"))
                    .header("api-key", brevoApiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201 || response.statusCode() == 202) {
                log.info("Email successfully dispatched via Brevo REST API to {}", recipientEmail);
                return true;
            } else {
                log.error("********************************************************************************");
                log.error("BREVO API ERROR: Failed to dispatch email. HTTP Status: {}, Response: {}", response.statusCode(), response.body());
                log.error("Recipient: {} <{}>", recipientName, recipientEmail);
                log.error("********************************************************************************");
                return false;
            }

        } catch (Exception e) {
            log.error("********************************************************************************");
            log.error("BREVO API EXCEPTION: Failed to send verification email to {}: {}", recipientEmail, e.getMessage());
            log.error("********************************************************************************");
            return false;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.teknoycart.repositories.UserRepository userRepository;

    public void setUserRepository(com.teknoycart.repositories.UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean sendNotificationEmail(java.util.UUID userId, String title, String body, String referenceType, String referenceId) {
        if (userRepository == null) {
            log.warn("UserRepository is not wired into EmailService, cannot resolve email for user: {}", userId);
            return false;
        }
        var userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            log.warn("User {} not found in database for email notification dispatch", userId);
            return false;
        }
        var user = userOpt.get();
        return sendNotificationEmailSync(user.getEmail(), user.getFullName(), title, body, referenceType, referenceId);
    }

    public boolean sendNotificationEmailSync(String recipientEmail, String recipientName, String title, String body, String referenceType, String referenceId) {
        log.info("=========================================================================");
        log.info("NOTIFICATION EMAIL DISPATCH TRIGGERED FOR {}: Title='{}'", recipientEmail, title);
        log.info("=========================================================================");

        if (brevoApiKey == null || brevoApiKey.trim().isEmpty() || "PLACEHOLDER".equalsIgnoreCase(brevoApiKey.trim())) {
            log.error("********************************************************************************");
            log.error("BREVO EMAIL DISPATCH SKIPPED: 'brevo.api.key' (BREVO_API_KEY) is not configured!");
            log.error("Recipient: [REDACTED] <{}>, Subject: {}", recipientEmail, title);
            log.error("********************************************************************************");
            return false;
        }

        try {
            String safeRecipientName = org.springframework.web.util.HtmlUtils.htmlEscape(
                    recipientName != null ? recipientName : "Wildcat"
            );
            String safeTitle = org.springframework.web.util.HtmlUtils.htmlEscape(title != null ? title : "TeknoyCart Notification");
            String safeBody = org.springframework.web.util.HtmlUtils.htmlEscape(body != null ? body : "");
            String safeRefType = org.springframework.web.util.HtmlUtils.htmlEscape(referenceType != null ? referenceType : "");
            String safeRefId = org.springframework.web.util.HtmlUtils.htmlEscape(referenceId != null ? referenceId : "");

            String actionUrl = "https://teknoy-cart-web.vercel.app";
            String actionText = "Open TeknoyCart";
            if ("ORDER".equalsIgnoreCase(safeRefType) && !safeRefId.isEmpty()) {
                actionUrl = "https://teknoy-cart-web.vercel.app/orders/" + safeRefId;
                actionText = "View Order Details";
            } else if ("KYC".equalsIgnoreCase(safeRefType)) {
                actionUrl = "https://teknoy-cart-web.vercel.app/profile";
                actionText = "View Verification Status";
            }

            String htmlContent = "<div style=\"font-family: 'Outfit', 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; max-width: 600px; margin: 0 auto; padding: 24px; border: 1px solid #ECECEF; border-radius: 16px;\">"
                    + "  <div style=\"text-align: center; margin-bottom: 24px;\">"
                    + "    <h2 style=\"color: #B22222; margin: 0; font-size: 26px;\">Wildcat Marketplace</h2>"
                    + "    <p style=\"color: #FFC107; font-weight: bold; margin: 4px 0 0 0; letter-spacing: 1px;\">CEBU INSTITUTE OF TECHNOLOGY - UNIVERSITY</p>"
                    + "  </div>"
                    + "  <hr style=\"border: none; border-top: 1px solid #ECECEF; margin-bottom: 24px;\"/>"
                    + "  <h3 style=\"color: #1A1A1E;\">Hello " + safeRecipientName + ",</h3>"
                    + "  <div style=\"background-color: #FDF4F4; border-left: 4px solid #B22222; padding: 16px; border-radius: 8px; margin: 20px 0;\">"
                    + "    <h4 style=\"margin: 0 0 8px 0; color: #800000; font-size: 17px;\">" + safeTitle + "</h4>"
                    + "    <p style=\"margin: 0; color: #3A3A40; font-size: 15px; line-height: 1.5;\">" + safeBody + "</p>"
                    + "  </div>"
                    + "  <div style=\"text-align: center; margin: 32px 0;\">"
                    + "    <a href=\"" + actionUrl + "\" style=\"background-color: #800000; color: #FFFFFF; text-decoration: none; padding: 14px 28px; font-weight: bold; border-radius: 10px; font-size: 15px; display: inline-block; box-shadow: 0 4px 12px rgba(128, 0, 0, 0.25);\">" + actionText + "</a>"
                    + "  </div>"
                    + "  <hr style=\"border: none; border-top: 1px solid #ECECEF; margin: 24px 0;\"/>"
                    + "  <p style=\"font-size: 11px; color: #8A8A94; text-align: center; line-height: 1.4;\">"
                    + "    This is an automated transaction update from the CIT-U Wildcat Marketplace.<br/>"
                    + "    &copy; 2026 TeknoyCart CIT-U. All Rights Reserved."
                    + "  </p>"
                    + "</div>";

            Map<String, Object> payload = Map.of(
                "sender", Map.of("name", "TeknoyCart CIT-U", "email", "clarencekirkmc@gmail.com"),
                "to", List.of(Map.of("email", recipientEmail, "name", recipientName != null ? recipientName : "Wildcat")),
                "subject", "[TeknoyCart] " + title,
                "htmlContent", htmlContent
            );

            String requestBody = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.brevo.com/v3/smtp/email"))
                    .header("api-key", brevoApiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201 || response.statusCode() == 202) {
                log.info("Notification email successfully dispatched via Brevo REST API to {}", recipientEmail);
                return true;
            } else {
                log.error("BREVO API ERROR: Failed to dispatch notification email. HTTP Status: {}, Response: {}", response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            log.error("BREVO API EXCEPTION: Failed to send notification email to {}: {}", recipientEmail, e.getMessage());
            return false;
        }
    }
}
