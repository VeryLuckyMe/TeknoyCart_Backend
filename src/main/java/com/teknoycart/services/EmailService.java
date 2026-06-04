package com.teknoycart.services;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import org.springframework.scheduling.annotation.Async;

@Service
public class EmailService {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:noreply@teknoycart.com}")
    private String senderEmail;

    @Async
    public void sendVerificationEmail(String recipientEmail, String recipientName, String verificationToken) {
        if (mailSender == null) {
            System.out.println("SMTP Mail Sender not configured. Verification Link: http://localhost:8080/api/auth/verify?token=" + verificationToken);
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(senderEmail, "TeknoyCart CIT-U");
            helper.setTo(recipientEmail);
            helper.setSubject("Verify Your TeknoyCart Account");

            // Premium HTML Email Template with CSS branding matching CIT-U
        String verifyUrl = "https://teknoycart-backend.onrender.com/api/auth/verify?token=" + verificationToken;
        System.out.println("=========================================================================");
        System.out.println("VERIFICATION LINK GENERATED FOR " + recipientEmail + ":");
        System.out.println(verifyUrl);
        System.out.println("=========================================================================");

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(senderEmail, "TeknoyCart CIT-U");
            helper.setTo(recipientEmail);
            helper.setSubject("Verify Your TeknoyCart Account");

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

            helper.setText(htmlContent, true);
            mailSender.send(message);

        } catch (Exception e) {
            System.err.println("SMTP deliverability failed (expected on cloud host restrictions). Click verification URL directly from the console logs instead: " + verifyUrl);
        }
    }
}
