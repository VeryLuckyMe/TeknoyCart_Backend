package com.teknoycart.controllers;

import com.teknoycart.models.User;
import com.teknoycart.repositories.StoreRepository;
import com.teknoycart.repositories.UserRepository;
import com.teknoycart.security.JwtTokenProvider;
import com.teknoycart.security.UserPrincipal;
import com.teknoycart.services.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import({AuthController.class, BCryptPasswordEncoder.class})
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testupgradedb;MODE=PostgreSQL;TIME ZONE=UTC",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false",
    "supabase.url=https://mock.supabase.co",
    "supabase.anon-key=mock_key"
})
public class SellerUpgradeTest {

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

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        userRepository.deleteAll();
    }

    private void authenticateAs(User user) {
        UserPrincipal principal = new UserPrincipal(user.getUserId(), user.getEmail());
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("Unauthenticated request returns 401 UNAUTHORIZED")
    void testUpgradeWithoutAuthReturns401() {
        ResponseEntity<?> response = authController.requestSellerUpgrade();

        assertEquals(401, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals("UNAUTHORIZED", body.get("type"));
    }

    @Test
    @DisplayName("Verified BUYER can successfully request seller upgrade to unverified SELLER")
    void testVerifiedBuyerUpgradeSuccess() {
        User user = new User();
        user.setEmail("buyer@cit.edu");
        user.setFullName("Juan Buyer");
        user.setPasswordHash("hashed_pw");
        user.setRole("BUYER");
        user.setVerified(true);
        user.setSellerVerified(false);
        user = userRepository.save(user);

        authenticateAs(user);

        ResponseEntity<?> response = authController.requestSellerUpgrade();

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertTrue(body.get("message").toString().contains("Seller upgrade request submitted"));

        // Verify in database that role changed to SELLER and is_seller_verified is false
        User updated = userRepository.findByEmail("buyer@cit.edu").orElseThrow();
        assertEquals("SELLER", updated.getRole());
        assertFalse(updated.isSellerVerified());
    }

    @Test
    @DisplayName("Already SELLER cannot request seller upgrade (returns 400 INVALID_ROLE)")
    void testAlreadySellerCannotUpgrade() {
        User user = new User();
        user.setEmail("seller@cit.edu");
        user.setFullName("Maria Seller");
        user.setPasswordHash("hashed_pw");
        user.setRole("SELLER");
        user.setVerified(true);
        user.setSellerVerified(true);
        user = userRepository.save(user);

        authenticateAs(user);

        ResponseEntity<?> response = authController.requestSellerUpgrade();

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals("INVALID_ROLE", body.get("type"));
    }

    @Test
    @DisplayName("Unverified BUYER cannot request seller upgrade (returns 403 EMAIL_UNVERIFIED)")
    void testUnverifiedBuyerCannotUpgrade() {
        User user = new User();
        user.setEmail("unverified@cit.edu");
        user.setFullName("Unverified User");
        user.setPasswordHash("hashed_pw");
        user.setRole("BUYER");
        user.setVerified(false);
        user = userRepository.save(user);

        authenticateAs(user);

        ResponseEntity<?> response = authController.requestSellerUpgrade();

        assertEquals(403, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals("EMAIL_UNVERIFIED", body.get("type"));
    }

    @Test
    @DisplayName("Locked account cannot request seller upgrade (returns 403 ACCOUNT_LOCKED)")
    void testLockedUserCannotUpgrade() {
        User user = new User();
        user.setEmail("locked@cit.edu");
        user.setFullName("Locked User");
        user.setPasswordHash("hashed_pw");
        user.setRole("BUYER");
        user.setVerified(true);
        user.setLocked(true);
        user.setLockUntil(LocalDateTime.now(ZoneId.of("UTC")).plusHours(1));
        user = userRepository.save(user);

        authenticateAs(user);

        ResponseEntity<?> response = authController.requestSellerUpgrade();

        assertEquals(403, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals("ACCOUNT_LOCKED", body.get("type"));
    }
}
