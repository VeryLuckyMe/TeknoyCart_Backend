package com.teknoycart.controllers;

import com.teknoycart.models.User;
import com.teknoycart.repositories.UserRepository;
import com.teknoycart.services.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@CrossOrigin
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.teknoycart.repositories.StoreRepository storeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EmailService emailService;

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody User user) {
        String email = user.getEmail().toLowerCase().trim();
        if (!email.endsWith("@cit.edu")) {
            return ResponseEntity.badRequest()
                .body("Registration restricted to official Cebu Institute of Technology - University accounts.");
        }

        if (userRepository.findByEmail(email).isPresent()) {
            return ResponseEntity.badRequest().body("Error: Email is already in use!");
        }

        if ("SELLER".equalsIgnoreCase(user.getRole())) {
            if (user.getStoreName() == null || user.getStoreName().trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Error: Store Name is mandatory for seller registration.");
            }
            if (storeRepository.findByStoreName(user.getStoreName().trim()).isPresent()) {
                return ResponseEntity.badRequest().body("Error: Store Name is already in use.");
            }
        }

        // Generate dynamic verification token with 5-minute expiration
        String token = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);
        
        user.setPasswordHash(passwordEncoder.encode(user.getPasswordHash()));
        user.setEmail(email);
        user.setVerified(false); // Force all accounts to verify email before logging in
        user.setVerificationToken(token);
        user.setTokenExpiresAt(expiresAt);

        User savedUser = userRepository.save(user);

        if ("SELLER".equalsIgnoreCase(savedUser.getRole())) {
            com.teknoycart.models.Store store = new com.teknoycart.models.Store();
            store.setStoreName(user.getStoreName().trim());
            store.setOwner(savedUser);
            store.setPublicSupportEmail("support." + user.getStoreName().toLowerCase().replaceAll("\\s+", "") + "@cit.edu");
            storeRepository.save(store);
        }

        // Send HTML verification email via Outlook SMTP
        emailService.sendVerificationEmail(savedUser.getEmail(), savedUser.getFullName(), token);

        return ResponseEntity.ok("Registration successful! A verification email has been sent to your Outlook account.");
    }

    @PostMapping("/send-verification")
    public ResponseEntity<?> sendVerificationEmail(@RequestParam("email") String email, @RequestParam("fullName") String fullName) {
        String trimmedEmail = email.toLowerCase().trim();
        Optional<User> userOpt = userRepository.findByEmail(trimmedEmail);

        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("Error: Registered user profile not found in database.");
        }

        User user = userOpt.get();
        String token = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);
        user.setVerificationToken(token);
        user.setTokenExpiresAt(expiresAt);
        userRepository.save(user);

        emailService.sendVerificationEmail(user.getEmail(), user.getFullName(), token);
        return ResponseEntity.ok(Map.of(
            "message", "Verification email sent successfully!",
            "expiresAt", expiresAt.toString()
        ));
    }

    @GetMapping("/verify")
    public ResponseEntity<String> verifyUser(@RequestParam("token") String token) {
        Optional<User> userOpt = userRepository.findByVerificationToken(token);

        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest()
                .body("<html><body style=\"font-family: Arial; text-align: center; margin-top: 100px;\">"
                    + "<h2 style=\"color: red;\">Invalid Verification Link</h2>"
                    + "<p>This verification link is invalid or has already expired.</p>"
                    + "</body></html>");
        }

        User user = userOpt.get();

        // Check if token has expired
        if (user.getTokenExpiresAt() != null && LocalDateTime.now().isAfter(user.getTokenExpiresAt())) {
            return ResponseEntity.badRequest()
                .body("<html><body style=\"font-family: 'Segoe UI', Arial, sans-serif; text-align: center; margin-top: 80px; background-color: #FAFAFA;\">"
                    + "<div style=\"max-width: 500px; margin: 0 auto; padding: 40px; border: 1px solid #ECECEF; border-radius: 16px; background: white; box-shadow: 0 4px 12px rgba(0,0,0,0.08);\">"
                    + "<h2 style=\"color: #E65100;\">⏱️ Verification Link Expired</h2>"
                    + "<p style=\"color: #555; font-size: 15px; line-height: 1.6;\">This verification link has expired. Please go back to the TeknoyCart app and request a new verification email.</p>"
                    + "<p style=\"color: #888; font-size: 12px; margin-top: 24px;\">Verification links are valid for 5 minutes for security purposes.</p>"
                    + "</div>"
                    + "</body></html>");
        }

        user.setVerified(true);
        user.setVerificationToken(null); // Clear the token once verified
        user.setTokenExpiresAt(null);
        userRepository.save(user);

        return ResponseEntity.ok()
            .body("<html><body style=\"font-family: Arial; text-align: center; margin-top: 100px;\">"
                + "<div style=\"max-width: 500px; margin: 0 auto; padding: 30px; border: 1px solid #ddd; border-radius: 12px; box-shadow: 0 4px 10px rgba(0,0,0,0.1);\">"
                + "<h2 style=\"color: #B22222;\">Verification Successful!</h2>"
                + "<p style=\"color: #555;\">Thank you, <b>" + user.getFullName() + "</b>. Your institutional email has been verified.</p>"
                + "<p>You can now open the TeknoyCart mobile app and sign in with your credentials.</p>"
                + "</div>"
                + "</body></html>");
    }

    @GetMapping("/check-verification")
    public ResponseEntity<?> checkVerificationStatus(@RequestParam("email") String email) {
        String trimmedEmail = email.toLowerCase().trim();
        Optional<User> userOpt = userRepository.findByEmail(trimmedEmail);
        if (userOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("verified", false));
        }
        return ResponseEntity.ok(Map.of("verified", userOpt.get().isVerified()));
    }

    @Autowired
    private com.teknoycart.security.JwtTokenProvider tokenProvider;

    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@RequestBody User loginRequest) {
        String email = loginRequest.getEmail().toLowerCase().trim();
        Optional<User> userOpt = userRepository.findByEmail(email);

        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("Error: Incorrect email or password.");
        }

        User user = userOpt.get();

        // 1. Enforce Email Verification Guard
        if (!user.isVerified()) {
            return ResponseEntity.status(403)
                .body("Your account email has not been verified yet. Please check your Outlook inbox.");
        }

        // 2. Check account lockout state
        if (user.isLocked()) {
            if (user.getLockUntil() != null && LocalDateTime.now(java.time.ZoneId.of("UTC")).isBefore(user.getLockUntil())) {
                return ResponseEntity.status(403)
                    .body("Account is temporarily locked. Try again in 15 minutes.");
            } else {
                user.setLocked(false);
                user.setFailedAttempts(0);
                user.setLockUntil(null);
                userRepository.save(user);
            }
        }

        // 3. Validate Password
        if (!passwordEncoder.matches(loginRequest.getPasswordHash(), user.getPasswordHash())) {
            int attempts = user.getFailedAttempts() + 1;
            user.setFailedAttempts(attempts);

            if (attempts >= 5) {
                user.setLocked(true);
                user.setLockUntil(LocalDateTime.now(java.time.ZoneId.of("UTC")).plusMinutes(15));
                userRepository.save(user);
                return ResponseEntity.status(403)
                    .body("Too many failed attempts. Account locked for 15 minutes.");
            }

            userRepository.save(user);
            int remaining = 5 - attempts;
            return ResponseEntity.badRequest()
                .body("Error: Incorrect password. " + remaining + " attempts remaining before lockout.");
        }

        user.setFailedAttempts(0);
        user.setLocked(false);
        user.setLockUntil(null);
        userRepository.save(user);

        // Generate stateless JWT session token signed using HMAC SHA-256
        String token = tokenProvider.generateToken(user.getEmail(), user.getRole());

        return ResponseEntity.ok(Map.of(
            "token", token,
            "user", user
        ));
    }
}
