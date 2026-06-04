package com.teknoycart.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class EmailService {

    @Value("${brevo.api.key}")
    private String brevoApiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Async
    public void sendVerificationEmail(String recipientEmail, String recipientName, String verificationToken) {
        String verifyUrl = "https://teknoycart-backend.onrender.com/api/auth/verify?token=" + verificationToken;
        System.out.println("=========================================================================");
        System.out.println("VERIFICATION LINK GENERATED FOR " + recipientEmail + ":");
        System.out.println(verifyUrl);
        System.out.println("=========================================================================");

        try {
            // Premium HTML Email Template with CSS branding matching CIT-U
            String htmlContent = "<div style=\"font-family: 'Outfit', 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; max-width: 600px; margin: 0 auto; padding: 24px; border: 1px solid #ECECEF; border-radius: 16px;\">"
                    + "  <div style=\"text-align: center; margin-bottom: 24px;\">"
                    + "    <h2 style=\"color: #B22222; margin: 0; font-size: 26px;\">Wildcat Marketplace</h2>"
                    + "    <p style=\"color: #FFC107; font-weight: bold; margin: 4px 0 0 0; letter-spacing: 1px;\">CEBU INSTITUTE OF TECHNOLOGY - UNIVERSITY</p>"
                    + "  </div>"
                    + "  <hr style=\"border: none; border-top: 1px solid #ECECEF; margin-bottom: 24px;\"/>"
                    + "  <h3 style=\"color: #1A1A1E;\">Hello " + recipientName + ",</h3>"
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
            // Sender can be custom-named, but will show as sent via Brevo.
            // Recipient can be ANY email address (no sandbox restrictions!)
            Map<String, Object> payload = Map.of(
                "sender", Map.of("name", "TeknoyCart CIT-U", "email", "noreply@teknoycart.com"),
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
                System.out.println("Email successfully dispatched via Brevo REST API to " + recipientEmail);
            } else {
                System.err.println("Brevo API failed to dispatch email. Status code: " + response.statusCode() + ", Response: " + response.body());
            }

        } catch (Exception e) {
            System.err.println("Failed to send verification email via Brevo API: " + e.getMessage());
        }
    }
}
