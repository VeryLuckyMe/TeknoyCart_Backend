package com.teknoycart.security;

import io.jsonwebtoken.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class JwtTokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);

    @Value("${supabase.jwks.url:https://chmtvasbhkbrvydbajnd.supabase.co/auth/v1/.well-known/jwks.json}")
    private String jwksUrl;

    @Value("${supabase.issuer:https://chmtvasbhkbrvydbajnd.supabase.co/auth/v1}")
    private String expectedIssuer;

    private final ConcurrentHashMap<String, PublicKey> keyCache = new ConcurrentHashMap<>();
    private volatile long lastFetchAttemptTime = 0;
    private static final long MIN_REFRESH_INTERVAL_MS = 60_000; // 1 minute throttle on on-demand re-fetch

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        seedKnownFallbackKey();
        refreshPublicKey();
    }

    private void seedKnownFallbackKey() {
        try {
            // Seed current known Supabase public key for instant readiness during cold starts
            String fallbackKid = "03bad522-03a9-4d74-b835-3fbe26bdeda0";
            String fallbackX = "HAUltk-KeVZ2KU7f4gxQGlSODdTEYrJGtsVmhAuWSi8";
            String fallbackY = "QIJzzCpT-KDKEsak8DG-lFoS-Y6pNM4ICRm2LhyzSs0";
            PublicKey key = parseEcPublicKey(fallbackX, fallbackY);
            if (key != null) {
                keyCache.put(fallbackKid, key);
                logger.info("Seeded initial fallback Supabase public key ({})", fallbackKid);
            }
        } catch (Exception e) {
            logger.warn("Could not seed fallback key: {}", e.getMessage());
        }
    }

    @Scheduled(fixedRate = 86_400_000, initialDelay = 86_400_000)
    public void scheduledKeyRefresh() {
        logger.info("Executing scheduled 24-hour Supabase JWKS refresh...");
        refreshPublicKey();
    }

    public synchronized void refreshPublicKey() {
        lastFetchAttemptTime = System.currentTimeMillis();
        try {
            logger.info("Fetching Supabase JWKS keys from {}", jwksUrl);
            JsonNode root;
            try (InputStream in = URI.create(jwksUrl).toURL().openStream()) {
                root = objectMapper.readTree(in);
            }
            JsonNode keys = root.get("keys");
            if (keys != null && keys.isArray()) {
                for (JsonNode keyNode : keys) {
                    String kid = keyNode.path("kid").asText();
                    String x = keyNode.path("x").asText();
                    String y = keyNode.path("y").asText();

                    PublicKey pubKey = parseEcPublicKey(x, y);
                    if (pubKey != null && !kid.isEmpty()) {
                        keyCache.put(kid, pubKey);
                        logger.info("Cached Supabase ES256 key for kid: {}", kid);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch Supabase JWKS from {}: {}. Will rely on cached keys.", jwksUrl, e.getMessage());
        }
    }

    private PublicKey parseEcPublicKey(String xBase64, String yBase64) {
        try {
            byte[] xBytes = Base64.getUrlDecoder().decode(xBase64);
            byte[] yBytes = Base64.getUrlDecoder().decode(yBase64);

            ECPoint point = new ECPoint(new BigInteger(1, xBytes), new BigInteger(1, yBytes));
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);
            ECPublicKeySpec pubSpec = new ECPublicKeySpec(point, ecSpec);

            return KeyFactory.getInstance("EC").generatePublic(pubSpec);
        } catch (Exception e) {
            logger.error("Error constructing EC Public Key: {}", e.getMessage());
            return null;
        }
    }

    private PublicKey getKeyForToken(String kid) {
        PublicKey key = (kid != null) ? keyCache.get(kid) : null;
        if (key == null) {
            long now = System.currentTimeMillis();
            if (now - lastFetchAttemptTime > MIN_REFRESH_INTERVAL_MS) {
                logger.info("Uncached kid '{}' received. Attempting on-demand JWKS refresh...", kid);
                refreshPublicKey();
                key = (kid != null) ? keyCache.get(kid) : null;
            }
        }
        if (key == null && !keyCache.isEmpty()) {
            key = keyCache.values().iterator().next();
        }
        return key;
    }

    public boolean validateToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }

        String kid = null;
        // 1. Explicit pre-check on unverified header: strictly reject any alg != ES256
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            JsonNode headerNode = objectMapper.readTree(headerJson);
            String alg = headerNode.path("alg").asText();
            if (!"ES256".equalsIgnoreCase(alg)) {
                logger.warn("JWT rejected: 'alg' claim must be ES256, but got: '{}'", alg);
                return false;
            }
            kid = headerNode.path("kid").asText(null);
        } catch (Exception e) {
            logger.warn("Failed to parse JWT header: {}", e.getMessage());
            return false;
        }

        PublicKey publicKey = getKeyForToken(kid);
        if (publicKey == null) {
            logger.error("No EC Public Key available to verify Supabase JWT");
            return false;
        }

        // 2. Cryptographic signature and expiration check
        try {
            Jws<Claims> jws = Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .build()
                    .parseClaimsJws(token);

            // 3. Confirm verified algorithm from parsed JWS header
            if (!"ES256".equalsIgnoreCase(jws.getHeader().getAlgorithm())) {
                logger.warn("Verified JWS algorithm mismatch: expected ES256 but got {}", jws.getHeader().getAlgorithm());
                return false;
            }

            Claims claims = jws.getBody();

            // 4. Verify expected issuer
            if (expectedIssuer != null && !expectedIssuer.equals(claims.getIssuer())) {
                logger.warn("JWT issuer mismatch: expected {} but got {}", expectedIssuer, claims.getIssuer());
                return false;
            }

            return true;
        } catch (ExpiredJwtException e) {
            logger.warn("JWT token has expired: {}", e.getMessage());
        } catch (JwtException | IllegalArgumentException e) {
            logger.warn("Cryptographic signature validation failed: {}", e.getMessage());
        }
        return false;
    }

    public UUID getUserIdFromToken(String token) {
        String kid = extractKidFromTokenHeader(token);
        PublicKey publicKey = getKeyForToken(kid);
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(publicKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
        return UUID.fromString(claims.getSubject());
    }

    public String getEmailFromToken(String token) {
        String kid = extractKidFromTokenHeader(token);
        PublicKey publicKey = getKeyForToken(kid);
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(publicKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
        return claims.get("email", String.class);
    }

    private String extractKidFromTokenHeader(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length == 3) {
                String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
                JsonNode headerNode = objectMapper.readTree(headerJson);
                return headerNode.path("kid").asText(null);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
