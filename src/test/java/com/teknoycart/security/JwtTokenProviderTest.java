package com.teknoycart.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the two bearer-token families accepted by the backend:
 *   - Backend-issued HS256 tokens signed with JWT_SECRET.
 *   - Supabase legacy HS256 tokens signed with SUPABASE_JWT_SECRET.
 * ES256 Supabase tokens are exercised by the JWKS path and are not built here.
 */
class JwtTokenProviderTest {

    private static final String BACKEND_SECRET =
            "TEST_BACKEND_SECRET_KEY_AT_LEAST_32_BYTES_LONG_1234567890";
    private static final String SUPABASE_SECRET =
            "TEST_SUPABASE_SECRET_KEY_AT_LEAST_32_BYTES_LONG_0987654321";
    private static final String ISSUER = "https://test.supabase.co/auth/v1";

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", BACKEND_SECRET);
        ReflectionTestUtils.setField(provider, "supabaseJwtSecret", SUPABASE_SECRET);
        ReflectionTestUtils.setField(provider, "expectedIssuer", ISSUER);
        ReflectionTestUtils.setField(provider, "jwtExpirationMs", 3_600_000L);
    }

    private String supabaseToken(UUID userId, String email, String issuer, Date expiry) {
        SecretKey key = Keys.hmacShaKeyFor(SUPABASE_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .setSubject(userId.toString())
                .claim("email", email)
                .claim("role", "authenticated")
                .setIssuer(issuer)
                .setIssuedAt(new Date())
                .setExpiration(expiry)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    @DisplayName("Backend-issued HS256 token validates and resolves the user")
    void backendTokenValidates() {
        UUID userId = UUID.randomUUID();
        String token = provider.generateToken(userId, "student@cit.edu", "BUYER");

        assertTrue(provider.validateToken(token));
        assertEquals(userId, provider.getUserIdFromToken(token));
        assertEquals("student@cit.edu", provider.getEmailFromToken(token));
    }

    @Test
    @DisplayName("Supabase HS256 token validates with SUPABASE_JWT_SECRET")
    void supabaseHs256TokenValidates() {
        UUID userId = UUID.randomUUID();
        String token = supabaseToken(userId, "buyer@cit.edu", ISSUER,
                new Date(System.currentTimeMillis() + 600_000));

        assertTrue(provider.validateToken(token));
        assertEquals(userId, provider.getUserIdFromToken(token));
        assertEquals("buyer@cit.edu", provider.getEmailFromToken(token));
    }

    @Test
    @DisplayName("Supabase HS256 token with a foreign issuer is rejected")
    void supabaseHs256TokenWithWrongIssuerIsRejected() {
        String token = supabaseToken(UUID.randomUUID(), "buyer@cit.edu",
                "https://attacker.example/auth/v1",
                new Date(System.currentTimeMillis() + 600_000));

        assertFalse(provider.validateToken(token));
    }

    @Test
    @DisplayName("Token signed with an unknown secret is rejected")
    void tokenSignedWithUnknownSecretIsRejected() {
        SecretKey attackerKey = Keys.hmacShaKeyFor(
                "ATTACKER_SECRET_KEY_AT_LEAST_32_BYTES_LONG_000000000".getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .setSubject(UUID.randomUUID().toString())
                .setIssuer(ISSUER)
                .setExpiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(attackerKey, SignatureAlgorithm.HS256)
                .compact();

        assertFalse(provider.validateToken(token));
    }

    @Test
    @DisplayName("Expired Supabase HS256 token is rejected")
    void expiredSupabaseTokenIsRejected() {
        String token = supabaseToken(UUID.randomUUID(), "buyer@cit.edu", ISSUER,
                new Date(System.currentTimeMillis() - 1_000));

        assertFalse(provider.validateToken(token));
    }

    @Test
    @DisplayName("Supabase HS256 token is rejected when no SUPABASE_JWT_SECRET is configured")
    void supabaseTokenRejectedWithoutConfiguredSecret() {
        ReflectionTestUtils.setField(provider, "supabaseJwtSecret", "");
        String token = supabaseToken(UUID.randomUUID(), "buyer@cit.edu", ISSUER,
                new Date(System.currentTimeMillis() + 600_000));

        assertFalse(provider.validateToken(token));
    }
}
