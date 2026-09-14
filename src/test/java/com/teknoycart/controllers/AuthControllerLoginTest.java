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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import({AuthController.class, BCryptPasswordEncoder.class})
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testauthdb;MODE=PostgreSQL;TIME ZONE=UTC",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false",
    "supabase.url=https://mock.supabase.co",
    "supabase.anon-key=mock_key"
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
    @DisplayName("Unverified email returns 403 EMAIL_UNVERIFIED with email & fullName for resend UX")
    void testUnverifiedUserReturns403() {
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
        assertEquals("Juan Dela Cruz", body.get("fullName"));
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
    void testExpiredLockClearsAutomatically() {
        User user = new User();
        user.setEmail("expiredlock@cit.edu");
        user.setFullName("Expired User");
        user.setPasswordHash(passwordEncoder.encode("correct_pass"));
        user.setVerified(true);
        user.setRole("BUYER");
        user.setFailedAttempts(5);
        user.setLocked(true);
        // Lock expired 5 minutes ago
        user.setLockUntil(LocalDateTime.now(ZoneId.of("UTC")).minusMinutes(5));
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
}
