package com.teknoycart.controllers;

import com.teknoycart.models.User;
import com.teknoycart.repositories.StoreRepository;
import com.teknoycart.repositories.UserRepository;
import com.teknoycart.security.JwtTokenProvider;
import com.teknoycart.services.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({AuthController.class, BCryptPasswordEncoder.class})
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testauthdb;MODE=PostgreSQL;TIME ZONE=UTC",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false",
    "supabase.url=https://mock.supabase.co",
    "supabase.anon-key=mock_key",
    "jwt.secret=TEST_ONLY_JWT_SECRET_KEY_FOR_AUTH_TESTING_MINIMUM_256_BITS"
})
public class AuthControllerLoginTest {

    @Autowired
    private AuthController authController;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private StoreRepository storeRepository;

    @MockBean
    private EmailService emailService;

    @MockBean
    private JwtTokenProvider tokenProvider;

    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Verification provider failure is reported instead of claiming the email was sent")
    void verificationEmailFailureReturnsServiceUnavailable() {
        User user = new User();
        user.setEmail("new@cit.edu");
        user.setFullName("New Student");
        user.setPasswordHash("SUPABASE_AUTH_MANAGED");
        user.setRole("BUYER");
        userRepository.save(user);
        when(emailService.sendVerificationEmail(eq("new@cit.edu"), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(false));

        ResponseEntity<?> response = authController.sendVerificationEmail(
            Map.of("email", "new@cit.edu"), null, null);

        assertEquals(503, response.getStatusCode().value());
        assertEquals("EMAIL_DELIVERY_FAILED", ((Map<?, ?>) response.getBody()).get("type"));
        assertNull(userRepository.findByEmail("new@cit.edu").orElseThrow().getVerificationToken());
    }

    @Test
    @DisplayName("Verification token is hashed at rest and the emailed token verifies the account")
    void verificationTokenIsHashedAndCanVerify() {
        User user = new User();
        user.setEmail("verified@cit.edu");
        user.setFullName("Verified Student");
        user.setPasswordHash("SUPABASE_AUTH_MANAGED");
        user.setRole("BUYER");
        userRepository.save(user);
        var tokenCaptor = forClass(String.class);
        when(emailService.sendVerificationEmail(eq("verified@cit.edu"), anyString(), tokenCaptor.capture()))
            .thenReturn(CompletableFuture.completedFuture(true));

        ResponseEntity<?> sent = authController.sendVerificationEmail(
            Map.of("email", "verified@cit.edu"), null, null);

        assertEquals(200, sent.getStatusCode().value());
        String rawToken = tokenCaptor.getValue();
        assertNotEquals(rawToken,
            userRepository.findByEmail("verified@cit.edu").orElseThrow().getVerificationToken());

        ResponseEntity<String> verified = authController.verifyUser(rawToken);

        assertEquals(200, verified.getStatusCode().value());
        assertTrue(userRepository.findByEmail("verified@cit.edu").orElseThrow().isVerified());
    }

    @Test
    @DisplayName("Anti-enumeration: Unknown email returns generic INVALID_CREDENTIALS")
    void testUnknownEmailReturnsGenericError() {
        ResponseEntity<?> response = authController.authenticateUser(Map.of(
            "email", "nonexistent@cit.edu",
            "password", "secret123"
        ));

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("INVALID_CREDENTIALS", body.get("type"));
        assertEquals("Invalid email or password.", body.get("message"));
    }

    @Test
    @DisplayName("Unverified email with correct password returns 403 EMAIL_UNVERIFIED without fullName leak")
    void testUnverifiedUserWithCorrectPasswordReturns403() {
        User user = new User();
        user.setEmail("unverified@cit.edu");
        user.setFullName("Juan Dela Cruz");
        user.setPasswordHash(passwordEncoder.encode("correct_pass"));
        user.setVerified(false);
        user.setRole("BUYER");
        userRepository.save(user);

        ResponseEntity<?> response = authController.authenticateUser(Map.of(
            "email", "unverified@cit.edu",
            "password", "correct_pass"
        ));

        assertEquals(403, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("EMAIL_UNVERIFIED", body.get("type"));
        assertEquals("unverified@cit.edu", body.get("email"));
        assertNull(body.get("fullName"), "fullName must not be leaked to clients");
    }

    @Test
    @DisplayName("Unverified email with wrong password returns 400 INVALID_CREDENTIALS (anti-enumeration)")
    void testUnverifiedUserWithWrongPasswordReturnsGenericError() {
        User user = new User();
        user.setEmail("unverified2@cit.edu");
        user.setFullName("Juan Dela Cruz");
        user.setPasswordHash(passwordEncoder.encode("correct_pass"));
        user.setVerified(false);
        user.setRole("BUYER");
        userRepository.save(user);

        ResponseEntity<?> response = authController.authenticateUser(Map.of(
            "email", "unverified2@cit.edu",
            "password", "wrong_pass"
        ));

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("INVALID_CREDENTIALS", body.get("type"));
        assertNull(body.get("fullName"));
    }

    @Test
    @DisplayName("Failed attempts count up to 5 and trigger ACCOUNT_LOCKED on 5th attempt")
    void testFailedAttemptsTriggerLockout() {
        User user = new User();
        user.setEmail("student@cit.edu");
        user.setFullName("Maria Clara");
        user.setPasswordHash(passwordEncoder.encode("correct_pass"));
        user.setVerified(true);
        user.setRole("BUYER");
        user.setFailedAttempts(0);
        user.setLocked(false);
        userRepository.save(user);

        // Attempts 1 to 4
        for (int i = 1; i <= 4; i++) {
            ResponseEntity<?> response = authController.authenticateUser(Map.of(
                "email", "student@cit.edu",
                "password", "wrong_pass"
            ));

            assertEquals(400, response.getStatusCode().value());
            Map<?, ?> body = (Map<?, ?>) response.getBody();
            assertEquals("INVALID_CREDENTIALS", body.get("type"));
            int expectedRemaining = 5 - i;
            assertEquals(expectedRemaining, body.get("attemptsRemaining"));

            User dbUser = userRepository.findByEmail("student@cit.edu").orElseThrow();
            assertEquals(i, dbUser.getFailedAttempts());
            assertFalse(dbUser.isLocked());
        }

        // Attempt 5: triggers lockout
        ResponseEntity<?> response5 = authController.authenticateUser(Map.of(
            "email", "student@cit.edu",
            "password", "wrong_pass"
        ));

        assertEquals(403, response5.getStatusCode().value());
        Map<?, ?> body5 = (Map<?, ?>) response5.getBody();
        assertEquals("ACCOUNT_LOCKED", body5.get("type"));
        assertNotNull(body5.get("lockUntil"));
        assertTrue(body5.get("lockUntil").toString().endsWith("Z"), "lockUntil must be ISO-8601 UTC with trailing Z");

        User lockedUser = userRepository.findByEmail("student@cit.edu").orElseThrow();
        assertEquals(5, lockedUser.getFailedAttempts());
        assertTrue(lockedUser.isLocked());
        assertNotNull(lockedUser.getLockUntil());

        // Attempt 6 while locked: blocked immediately
        ResponseEntity<?> response6 = authController.authenticateUser(Map.of(
            "email", "student@cit.edu",
            "password", "correct_pass" // Even correct pass is blocked while locked!
        ));

        assertEquals(403, response6.getStatusCode().value());
        Map<?, ?> body6 = (Map<?, ?>) response6.getBody();
        assertEquals("ACCOUNT_LOCKED", body6.get("type"));
    }

    @Test
    @DisplayName("Expired lock automatically clears on next login attempt")
    void testExpiredLockClearsAutomatically() {        User user = new User();
        user.setEmail("expiredlock@cit.edu");
        user.setFullName("Expired User");
        user.setPasswordHash(passwordEncoder.encode("correct_pass"));
        user.setVerified(true);
        user.setRole("BUYER");
        user.setFailedAttempts(5);
        user.setLocked(true);
        // Lock expired 5 minutes ago
        user.setLockUntil(java.time.Instant.now().minus(java.time.Duration.ofMinutes(5)));
        userRepository.save(user);

        // Attempt with wrong pass: should reset lock, then increment attempts to 1
        ResponseEntity<?> response = authController.authenticateUser(Map.of(
            "email", "expiredlock@cit.edu",
            "password", "wrong_pass"
        ));

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("INVALID_CREDENTIALS", body.get("type"));
        assertEquals(4, body.get("attemptsRemaining"));

        User dbUser = userRepository.findByEmail("expiredlock@cit.edu").orElseThrow();
        assertFalse(dbUser.isLocked());
        assertEquals(1, dbUser.getFailedAttempts());
    }

    @Test
    @DisplayName("Successful login returns a backend-issued JWT as token, not the Supabase access token")
    void testSuccessfulLoginReturnsBackendJwt() {
        User user = new User();
        user.setEmail("verified.login@cit.edu");
        user.setFullName("Verified Student");
        user.setPasswordHash(passwordEncoder.encode("correct_pass"));
        user.setVerified(true);
        user.setRole("SELLER");
        userRepository.save(user);

        when(tokenProvider.generateToken(any(UUID.class), anyString(), anyString()))
                .thenReturn("backend.jwt.token");

        ResponseEntity<?> response = authController.authenticateUser(Map.of(
            "email", "verified.login@cit.edu",
            "password", "correct_pass"
        ));

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("backend.jwt.token", body.get("token"),
            "Spring Boot API calls must receive a backend-signed JWT");
        assertNotNull(body.get("user"));
    }
}
