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
            store.setPublicSupportEmail(
                    "support." + user.getStoreName().toLowerCase().replaceAll("\\s+", "") + "@cit.edu");
            storeRepository.save(store);
        }

        // Send HTML verification email via Outlook SMTP
        emailService.sendVerificationEmail(savedUser.getEmail(), savedUser.getFullName(), token);

        return ResponseEntity
                .ok("Registration successful! A verification email has been sent to your Outlook account.");
    }

    @PostMapping("/send-verification")
    public ResponseEntity<?> sendVerificationEmail(@RequestParam("email") String email,
            @RequestParam("fullName") String fullName) {
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
                "expiresAt", expiresAt.toString()));
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
                        + "<p style=\"color: #555;\">Thank you, <b>" + user.getFullName()
                        + "</b>. Your institutional email has been verified.</p>"
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

    @org.springframework.beans.factory.annotation.Value("${supabase.url:https://chmtvasbhkbrvydbajnd.supabase.co}")
    private String supabaseUrl;

    @org.springframework.beans.factory.annotation.Value("${supabase.anon-key:eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImNobXR2YXNiaGticnZ5ZGJham5kIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzk3NjMwMDgsImV4cCI6MjA5NTMzOTAwOH0.IJJIrh-dr4xRoXPPeBJoN_pVVHrNY4db5E1VY1Czj3I}")
    private String supabaseAnonKey;

    private Map<String, Object> authenticateWithSupabase(String email, String password) {
        try {
            String tokenUrl = supabaseUrl + "/auth/v1/token?grant_type=password";
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String requestBody = mapper.writeValueAsString(Map.of(
                    "email", email,
                    "password", password
            ));

            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(tokenUrl))
                    .header("Content-Type", "application/json")
                    .header("apikey", supabaseAnonKey)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return mapper.readValue(response.body(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            }
            return null;
        } catch (Exception e) {
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
        if (user.isLocked()) {
            if (user.getLockUntil() != null
                    && LocalDateTime.now(java.time.ZoneId.of("UTC")).isBefore(user.getLockUntil())) {
                long remainingMinutes = java.time.Duration.between(
                        LocalDateTime.now(java.time.ZoneId.of("UTC")), user.getLockUntil()
                ).toMinutes() + 1;
                return ResponseEntity.status(403).body(Map.of(
                        "type", "ACCOUNT_LOCKED",
                        "message", "Account is temporarily locked. Try again in " + remainingMinutes + " minutes.",
                        "lockUntil", user.getLockUntil().toString()
                ));
            } else {
                // Lock expired - reset parameters
                user.setLocked(false);
                user.setFailedAttempts(0);
                user.setLockUntil(null);
                userRepository.save(user);
            }
        }

        // 2. Enforce Email Verification Guard
        if (!user.isVerified()) {
            return ResponseEntity.status(403).body(Map.of(
                    "type", "EMAIL_UNVERIFIED",
                    "message", "Your account email has not been verified yet. Please check your Outlook inbox.",
                    "email", user.getEmail(),
                    "fullName", user.getFullName() != null ? user.getFullName() : "Student"
            ));
        }

        // 3. Validate Password with Supabase GoTrue (or fallback BCrypt for seed demo users)
        boolean authenticated = false;
        Map<String, Object> supabaseSession = null;

        if ("SUPABASE_AUTH_MANAGED".equals(user.getPasswordHash())) {
            supabaseSession = authenticateWithSupabase(email, password);
            authenticated = (supabaseSession != null);
        } else if (user.getPasswordHash() != null && user.getPasswordHash().startsWith("$2a$")) {
            authenticated = passwordEncoder.matches(password, user.getPasswordHash());
        } else {
            // Fallback try GoTrue first, then encoder
            supabaseSession = authenticateWithSupabase(email, password);
            authenticated = (supabaseSession != null) || passwordEncoder.matches(password, user.getPasswordHash());
        }

        if (!authenticated) {
            int attempts = user.getFailedAttempts() + 1;
            user.setFailedAttempts(attempts);

            if (attempts >= 5) {
                LocalDateTime lockUntil = LocalDateTime.now(java.time.ZoneId.of("UTC")).plusMinutes(15);
                user.setLocked(true);
                user.setLockUntil(lockUntil);
                userRepository.save(user);

                return ResponseEntity.status(403).body(Map.of(
                        "type", "ACCOUNT_LOCKED",
                        "message", "Too many failed attempts. Account locked for 15 minutes.",
                        "lockUntil", lockUntil.toString()
                ));
            }

            userRepository.save(user);
            int remaining = 5 - attempts;
            return ResponseEntity.badRequest().body(Map.of(
                    "type", "INVALID_CREDENTIALS",
                    "message", "Invalid email or password. " + remaining + " attempts remaining before lockout.",
                    "attemptsRemaining", remaining
            ));
        }

        // 4. Successful login: reset failed attempts & lockout state
        user.setFailedAttempts(0);
        user.setLocked(false);
        user.setLockUntil(null);
        userRepository.save(user);

        // Generate stateless JWT session token signed using HMAC SHA-256
        String token = tokenProvider.generateToken(user.getEmail(), user.getRole());

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
        response.put("token", token);
        response.put("user", sanitizedUser);
        if (supabaseSession != null) {
            response.put("session", supabaseSession);
        }

        return ResponseEntity.ok(response);
    }
}

