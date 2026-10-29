package com.teknoycart.controllers;

import com.teknoycart.models.User;
import com.teknoycart.repositories.UserRepository;
import com.teknoycart.services.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

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

    @org.springframework.beans.factory.annotation.Value("${jwt.secret:}")
    private String verificationTokenSecret;

    @Autowired(required = false)
    private com.teknoycart.repositories.NotificationRepository notificationRepository;

    @Autowired(required = false)
    private com.teknoycart.services.PushNotificationService pushNotificationService;

    @PostMapping("/send-verification")
    public ResponseEntity<?> sendVerificationEmail(
            @RequestBody(required = false) Map<String, String> body,
            @RequestParam(value = "email", required = false) String emailParam,
            @RequestParam(value = "fullName", required = false) String fullNameParam) {
        String email = (body != null && body.containsKey("email")) ? body.get("email") : emailParam;
        String requestedName = (body != null && body.containsKey("fullName")) ? body.get("fullName") : fullNameParam;

        if (email == null || email.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Error: Email is required.");
        }
        String trimmedEmail = email.toLowerCase().trim();
        Optional<User> userOpt = userRepository.findByEmail(trimmedEmail);

        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("Error: Registered user profile not found in database.");
        }

        User user = userOpt.get();
        Instant now = Instant.now();

        Instant expiresAt = user.getTokenExpiresAt();
        boolean reusableToken = expiresAt != null && now.isBefore(expiresAt);
        // Rate limiting cooldown: block re-sending within 60 seconds.
        if (reusableToken && now.isBefore(expiresAt.minus(Duration.ofMinutes(14)))) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "type", "RATE_LIMITED",
                    "message", "Please wait at least 60 seconds before requesting a new verification email."
            ));
        }

        if (!reusableToken) {
            expiresAt = Instant.ofEpochSecond(now.plus(Duration.ofMinutes(15)).getEpochSecond());
        }
        String existingToken = user.getVerificationToken();
        String token = reusableToken && existingToken != null && !isSha256Hash(existingToken)
                ? existingToken
                : createVerificationToken(user.getUserId(), expiresAt);
        user.setVerificationToken(hashVerificationToken(token));
        user.setTokenExpiresAt(expiresAt);
        userRepository.save(user);

        String fullName = (user.getFullName() != null && !user.getFullName().trim().isEmpty())
                ? user.getFullName()
                : (requestedName != null ? requestedName : "Student");

        try {
            boolean sent = emailService.sendVerificationEmail(user.getEmail(), fullName, token)
                    .get(15, TimeUnit.SECONDS);
            if (sent) {
                return ResponseEntity.ok(Map.of(
                        "message", "Verification email sent successfully!",
                        "expiresAt", expiresAt.toString()));
            }
        } catch (TimeoutException e) {
            logger.warn("Verification email dispatch timed out");
        } catch (Exception e) {
            logger.error("Verification email dispatch failed", e);
        }

        // A failed dispatch must not leave a valid link that the user never received.
        if (!reusableToken) {
            user.setVerificationToken(null);
            user.setTokenExpiresAt(null);
            userRepository.save(user);
        }
        return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "type", "EMAIL_DELIVERY_FAILED",
                        "message", "Verification email could not be sent. Please try again later."));
    }

    @GetMapping("/verify")
    public ResponseEntity<String> verifyUser(@RequestParam("token") String token) {
        Optional<User> userOpt = userRepository.findByVerificationToken(hashVerificationToken(token));
        // Accept previously issued plaintext tokens during the one-time migration window.
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByVerificationToken(token);
        }

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.GONE)
                    .body("<html><body style=\"font-family: Arial; text-align: center; margin-top: 100px;\">"
                            + "<h2 style=\"color: red;\">Invalid Verification Link</h2>"
                            + "<p>This verification link is invalid or has already expired.</p>"
                            + "</body></html>");
        }

        User user = userOpt.get();

        // Check if token has expired
        if (user.getTokenExpiresAt() != null && Instant.now().isAfter(user.getTokenExpiresAt())) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.GONE)
                    .body("<html><body style=\"font-family: 'Segoe UI', Arial, sans-serif; text-align: center; margin-top: 80px; background-color: #FAFAFA;\">"
                            + "<div style=\"max-width: 500px; margin: 0 auto; padding: 40px; border: 1px solid #ECECEF; border-radius: 16px; background: white; box-shadow: 0 4px 12px rgba(0,0,0,0.08);\">"
                            + "<h2 style=\"color: #E65100;\">⏱️ Verification Link Expired</h2>"
                            + "<p style=\"color: #555; font-size: 15px; line-height: 1.6;\">This verification link has expired. Please go back to the TeknoyCart app and request a new verification email.</p>"
                            + "<p style=\"color: #888; font-size: 12px; margin-top: 24px;\">Verification links are valid for 15 minutes for security purposes.</p>"
                            + "</div>"
                            + "</body></html>");
        }

        user.setVerified(true);
        user.setVerificationToken(null); // Clear the token once verified
        user.setTokenExpiresAt(null);
        userRepository.save(user);

        String safeFullName = org.springframework.web.util.HtmlUtils.htmlEscape(
                user.getFullName() != null ? user.getFullName() : "Student"
        );

        return ResponseEntity.ok()
                .body("<html><body style=\"font-family: Arial; text-align: center; margin-top: 100px;\">"
                        + "<div style=\"max-width: 500px; margin: 0 auto; padding: 30px; border: 1px solid #ddd; border-radius: 12px; box-shadow: 0 4px 10px rgba(0,0,0,0.1);\">"
                        + "<h2 style=\"color: #B22222;\">Verification Successful!</h2>"
                        + "<p style=\"color: #555;\">Thank you, <b>" + safeFullName
                        + "</b>. Your institutional email has been verified.</p>"
                        + "<p>You can now open the TeknoyCart mobile app and sign in with your credentials.</p>"
                        + "</div>"
                        + "</body></html>");
    }

    private String hashVerificationToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String createVerificationToken(UUID userId, Instant expiresAt) {
        if (verificationTokenSecret == null
                || verificationTokenSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes to issue verification links");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(verificationTokenSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] token = mac.doFinal((userId + ":" + expiresAt.getEpochSecond()).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Unable to create verification token", e);
        }
    }

    private boolean isSha256Hash(String value) {
        return value.matches("[0-9a-fA-F]{64}");
    }

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private com.teknoycart.security.JwtTokenProvider tokenProvider;

    // Supabase credentials MUST be set via environment variables or application.yml (CRIT-01)
    // Do NOT embed production keys as annotation defaults — use SUPABASE_URL / SUPABASE_ANON_KEY env vars
    @org.springframework.beans.factory.annotation.Value("${supabase.url:}")
    private String supabaseUrl;

    @org.springframework.beans.factory.annotation.Value("${supabase.anon-key:}")
    private String supabaseAnonKey;

    private volatile String lastSupabaseError = "none";

    private String cleanUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalStateException(
                "[TeknoyCart] FATAL: supabase.url is not configured. " +
                "Set the SUPABASE_URL environment variable or supabase.url in application.yml. " +
                "Refusing to start with missing Supabase configuration.");
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://[^\\s)\\]\"']+").matcher(rawUrl);
        if (m.find()) {
            return m.group(0).replaceAll("/+$", "");
        }
        return rawUrl.trim().replaceAll("/+$", "");
    }

    private String cleanJwt(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new IllegalStateException(
                "[TeknoyCart] FATAL: supabase.anon-key is not configured. " +
                "Set the SUPABASE_ANON_KEY environment variable or supabase.anon-key in application.yml. " +
                "Refusing to start with missing Supabase configuration.");
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_.-]+").matcher(rawKey);
        if (m.find()) {
            return m.group(0);
        }
        return rawKey.replaceAll("[\\r\\n\\t\\s\"]", "");
    }

    private Map<String, Object> authenticateWithSupabase(String email, String password) {
        try {
            String baseUrl = cleanUrl(supabaseUrl);
            String tokenUrl = baseUrl + "/auth/v1/token?grant_type=password";
            String anonKey = cleanJwt(supabaseAnonKey);

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String requestBody = mapper.writeValueAsString(Map.of(
                    "email", email,
                    "password", password
            ));

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(tokenUrl))
                    .header("Content-Type", "application/json")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer " + anonKey)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                lastSupabaseError = "success (200)";
                return mapper.readValue(response.body(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } else {
                lastSupabaseError = "status=" + response.statusCode() + ", body=" + response.body();
                logger.warn("Supabase Auth rejected login for {}: status={}, body={}", email, response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            lastSupabaseError = "exception=" + e.getClass().getName() + ": " + e.getMessage();
            logger.error("Exception connecting to Supabase Auth: {}", e.getMessage(), e);
            return null;
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@RequestBody Map<String, Object> loginRequest) {
        String email = loginRequest.get("email") != null ? loginRequest.get("email").toString() : null;
        String password = loginRequest.get("password") != null 
                ? loginRequest.get("password").toString() 
                : (loginRequest.get("passwordHash") != null ? loginRequest.get("passwordHash").toString() : null);

        if (email == null || email.trim().isEmpty() || password == null || password.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "type", "INVALID_CREDENTIALS",
                    "message", "Email and password are required."
            ));
        }

        email = email.toLowerCase().trim();
        Optional<User> userOpt = userRepository.findByEmail(email);

        // Anti-enumeration: return identical error if user does not exist
        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "type", "INVALID_CREDENTIALS",
                    "message", "Invalid email or password."
            ));
        }

        User user = userOpt.get();

        // 1. Check account lockout state
        Instant now = Instant.now();
        if (user.isLocked()) {
            if (user.getLockUntil() != null && now.isBefore(user.getLockUntil())) {
                long remainingSeconds = Math.max(0, Duration.between(now, user.getLockUntil()).getSeconds());
                long remainingMinutes = (remainingSeconds / 60) + 1;
                return ResponseEntity.status(403).body(Map.of(
                        "type", "ACCOUNT_LOCKED",
                        "message", "Account is temporarily locked. Try again in " + remainingMinutes + " minutes.",
                        "lockUntil", user.getLockUntil().toString(),
                        "remainingSeconds", remainingSeconds
                ));
            } else {
                // Lock expired - reset parameters
                user.setLocked(false);
                user.setFailedAttempts(0);
                user.setLockUntil(null);
                userRepository.save(user);
            }
        }

        // 2. Validate Password FIRST before checking verification status (prevents user enumeration & info leak - H2)
        boolean authenticated = false;
        Map<String, Object> supabaseSession = authenticateWithSupabase(email, password);

        if (supabaseSession != null) {
            authenticated = true;
            if (!"SUPABASE_AUTH_MANAGED".equals(user.getPasswordHash())) {
                user.setPasswordHash("SUPABASE_AUTH_MANAGED");
            }
        } else if (user.getPasswordHash() != null && user.getPasswordHash().startsWith("$2a$")) {
            // Fallback for legacy seed demo users that only exist in local BCrypt seed
            authenticated = passwordEncoder.matches(password, user.getPasswordHash());
        }

        if (!authenticated) {
            int attempts = user.getFailedAttempts() + 1;
            user.setFailedAttempts(attempts);

            if (attempts >= 5) {
                Instant lockUntil = Instant.now().plus(Duration.ofMinutes(10));
                user.setLocked(true);
                user.setLockUntil(lockUntil);
                userRepository.save(user);

                return ResponseEntity.status(403).body(Map.of(
                        "type", "ACCOUNT_LOCKED",
                        "message", "Too many failed attempts. Account locked for 10 minutes.",
                        "lockUntil", lockUntil.toString(),
                        "remainingSeconds", 600
                ));
            }

            userRepository.save(user);
            int remaining = 5 - attempts;
            // Log the internal auth error server-side only — never expose to clients (CRIT-04)
            logger.debug("Auth failure for {}: supabaseError={}", email, lastSupabaseError);
            java.util.Map<String, Object> err = new java.util.HashMap<>();
            err.put("type", "INVALID_CREDENTIALS");
            err.put("message", "Invalid email or password. " + remaining + " attempts remaining before lockout.");
            err.put("attemptsRemaining", remaining);
            return ResponseEntity.badRequest().body(err);
        }

        // 3. Password verified successfully: reset failed attempts & lockout state
        user.setFailedAttempts(0);
        user.setLocked(false);
        user.setLockUntil(null);
        userRepository.save(user);

        // 4. Enforce Email Verification Guard ONLY after password is authenticated
        if (!user.isVerified()) {
            return ResponseEntity.status(403).body(Map.of(
                    "type", "EMAIL_UNVERIFIED",
                    "message", "Your account email has not been verified yet. Please check your Outlook inbox.",
                    "email", user.getEmail()
                    // fullName omitted to prevent account discovery (Finding H2)
            ));
        }

        // 5. Successful login: issue a backend JWT for Spring Boot API calls.
        // The Supabase session is returned separately under "session" and MUST NOT
        // be used as the backend bearer token: Spring Boot validates its own
        // HS256 tokens (and Supabase ES256 tokens via JWKS).
        String token = null;
        try {
            token = tokenProvider.generateToken(user.getUserId(), user.getEmail(), user.getRole());
        } catch (Exception e) {
            logger.error("Error generating backend JWT token: {}", e.getMessage(), e);
        }

        // Build sanitized user map without sensitive security hashes/tokens
        Map<String, Object> sanitizedUser = Map.of(
                "userId", user.getUserId() != null ? user.getUserId().toString() : "",
                "fullName", user.getFullName() != null ? user.getFullName() : "",
                "email", user.getEmail(),
                "role", user.getRole() != null ? user.getRole() : "BUYER",
                "isVerified", user.isVerified(),
                "isSellerVerified", user.isSellerVerified()
        );

        java.util.HashMap<String, Object> response = new java.util.HashMap<>();
        if (token != null) {
            response.put("token", token);
        }
        response.put("user", sanitizedUser);
        if (supabaseSession != null) {
            response.put("session", supabaseSession);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Server-authoritative seller upgrade endpoint.
     * Replaces the insecure client-side direct Supabase update.
     * Requires authentication via JWT.
     */
    @PostMapping("/request-seller-upgrade")
    public ResponseEntity<?> requestSellerUpgrade() {
        // Extract authenticated user from security context
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(401).body(Map.of(
                    "type", "UNAUTHORIZED",
                    "message", "Authentication required."
            ));
        }

        String email = null;
        if (auth.getPrincipal() instanceof com.teknoycart.security.UserPrincipal) {
            email = ((com.teknoycart.security.UserPrincipal) auth.getPrincipal()).getEmail();
        }

        if (email == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "type", "UNAUTHORIZED",
                    "message", "Unable to identify user."
            ));
        }

        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "type", "USER_NOT_FOUND",
                    "message", "User account not found."
            ));
        }

        User user = userOpt.get();

        // Validation: must be a verified BUYER, not locked
        if (!"BUYER".equalsIgnoreCase(user.getRole())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "type", "INVALID_ROLE",
                    "message", "Only BUYER accounts can request a seller upgrade."
            ));
        }

        if (!user.isVerified()) {
            return ResponseEntity.status(403).body(Map.of(
                    "type", "EMAIL_UNVERIFIED",
                    "message", "Email verification is required before upgrading."
            ));
        }

        if (user.isLocked()) {
            return ResponseEntity.status(403).body(Map.of(
                    "type", "ACCOUNT_LOCKED",
                    "message", "Account is locked. Cannot process upgrade request."
            ));
        }

        // Keep role as BUYER — seller status is pending admin approval (Ponytail Audit H4)
        user.setRole("BUYER");
        user.setSellerVerified(false);
        userRepository.save(user);

        // Dispatch notifications to administrators across In-App, Email, and Push
        notifyAdminsOfSellerApplication(user);

        Map<String, Object> sanitizedUser = Map.of(
                "userId", user.getUserId() != null ? user.getUserId().toString() : "",
                "fullName", user.getFullName() != null ? user.getFullName() : "",
                "email", user.getEmail(),
                "role", user.getRole(),
                "isVerified", user.isVerified(),
                "isSellerVerified", user.isSellerVerified()
        );

        return ResponseEntity.ok(Map.of(
                "message", "Seller upgrade request submitted. Admin will review your account.",
                "user", sanitizedUser
        ));
    }

    private void notifyAdminsOfSellerApplication(User applicant) {
        try {
            java.util.List<User> admins = new java.util.ArrayList<>();
            try {
                admins.addAll(userRepository.findAllByRole("ADMIN"));
            } catch (Exception ignored) {}

            String applicantName = (applicant.getFullName() != null && !applicant.getFullName().trim().isEmpty())
                    ? applicant.getFullName()
                    : (applicant.getEmail() != null ? applicant.getEmail() : "A student");

            String title = "📋 New Seller Application";
            String body = applicantName + " submitted KYC verification (STUDENT) for administrative review.";
            String refId = applicant.getUserId() != null ? applicant.getUserId().toString() : "";

            for (User admin : admins) {
                if (admin.getUserId() == null) continue;

                // 1. In-App Notification (saved to public.notifications table)
                if (notificationRepository != null) {
                    try {
                        com.teknoycart.models.Notification notif = new com.teknoycart.models.Notification(
                                admin.getUserId(),
                                title,
                                body,
                                "KYC",
                                "KYC",
                                refId
                        );
                        long window = System.currentTimeMillis() / (1000 * 60 * 10);
                        notif.setDedupeKey("kyc:apply:" + refId + ":admin:" + admin.getUserId() + ":" + window);
                        notif.setPushStatus("PENDING");
                        notif.setEmailStatus("PENDING");
                        notificationRepository.save(notif);
                    } catch (Exception ignored) {}
                }

                // 2. Direct transactional email dispatch to admin inbox
                if (emailService != null && admin.getEmail() != null) {
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            emailService.sendNotificationEmailSync(
                                    admin.getEmail(),
                                    admin.getFullName() != null ? admin.getFullName() : "Admin",
                                    title,
                                    body,
                                    "KYC",
                                    refId
                            );
                        } catch (Exception e) {
                            org.slf4j.LoggerFactory.getLogger(AuthController.class)
                                    .warn("Async email dispatch notice for {}: {}", admin.getEmail(), e.getMessage());
                        }
                    });
                }

                // 3. Direct FCM device push
                if (pushNotificationService != null) {
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            pushNotificationService.sendToUser(admin.getUserId(), title, body, "KYC", refId);
                        } catch (Exception ignored) {}
                    });
                }
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AuthController.class)
                    .error("Failed to notify admins of seller application: {}", e.getMessage(), e);
        }
    }
}

