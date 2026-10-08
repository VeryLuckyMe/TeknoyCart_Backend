package com.teknoycart.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * Verifies the {@code Paymongo-Signature} header on incoming webhook requests.
 *
 * <h3>Header format (from PayMongo docs, verified Oct 2026):</h3>
 * <pre>
 *   t=1496734173,te=&lt;test-hmac&gt;,li=&lt;live-hmac&gt;
 * </pre>
 * <ul>
 *   <li>{@code t}  – Unix timestamp (seconds) at which PayMongo sent the request.</li>
 *   <li>{@code te} – HMAC-SHA256 for test mode.</li>
 *   <li>{@code li} – HMAC-SHA256 for live mode (empty in sandbox).</li>
 * </ul>
 *
 * <h3>Signed payload:</h3>
 * <pre>{@code timestamp + "." + rawBody}</pre>
 *
 * <h3>Security properties:</h3>
 * <ul>
 *   <li>Constant-time comparison prevents timing-oracle attacks.</li>
 *   <li>Timestamp tolerance (default 5 min) prevents replay attacks.</li>
 *   <li>Raw body must be passed — do NOT use a parsed/reformatted version.</li>
 * </ul>
 */
@Component
public class PayMongoSignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long DEFAULT_TOLERANCE_SECONDS = 300L; // 5 minutes

    /**
     * Verifies a PayMongo webhook signature header.
     *
     * @param signatureHeader full value of the {@code Paymongo-Signature} header
     * @param rawBody         raw (unmodified) request body bytes
     * @param webhookSecret   webhook secret key from PayMongo dashboard
     * @param testMode        {@code true} to verify against the {@code te} field,
     *                        {@code false} to verify against the {@code li} field
     * @param toleranceSeconds max age of the timestamp in seconds; use 300 for 5 min
     * @throws PayMongoSignatureException if verification fails for any reason
     */
    public void verify(String signatureHeader, byte[] rawBody, String webhookSecret,
                       boolean testMode, long toleranceSeconds) {

        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new PayMongoSignatureException("Missing Paymongo-Signature header");
        }

        // ── 1. Parse the header ───────────────────────────────────────────────
        String timestamp = null;
        String testSig   = null;
        String liveSig   = null;

        for (String part : signatureHeader.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("t="))  timestamp = trimmed.substring(2);
            if (trimmed.startsWith("te=")) testSig   = trimmed.substring(3);
            if (trimmed.startsWith("li=")) liveSig   = trimmed.substring(3);
        }

        if (timestamp == null) {
            throw new PayMongoSignatureException("Missing timestamp in Paymongo-Signature header");
        }

        // ── 2. Timestamp tolerance check ─────────────────────────────────────
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new PayMongoSignatureException("Invalid timestamp in Paymongo-Signature header");
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > toleranceSeconds) {
            throw new PayMongoSignatureException(
                    "Webhook timestamp is outside tolerance window (" + toleranceSeconds + "s)");
        }

        // ── 3. Compute expected HMAC ──────────────────────────────────────────
        String signedPayload = timestamp + "." + new String(rawBody, StandardCharsets.UTF_8);
        String expectedHmac  = computeHmac(signedPayload, webhookSecret);

        // ── 4. Select the right signature field ──────────────────────────────
        String providedHmac = testMode ? testSig : liveSig;
        if (providedHmac == null || providedHmac.isBlank()) {
            throw new PayMongoSignatureException(
                    "Missing " + (testMode ? "te" : "li") + " signature in Paymongo-Signature header");
        }

        // ── 5. Constant-time compare ──────────────────────────────────────────
        if (!MessageDigest.isEqual(
                expectedHmac.getBytes(StandardCharsets.UTF_8),
                providedHmac.getBytes(StandardCharsets.UTF_8))) {
            throw new PayMongoSignatureException("Signature mismatch — webhook rejected");
        }
    }

    /** Convenience overload using the default 5-minute tolerance. */
    public void verify(String signatureHeader, byte[] rawBody, String webhookSecret,
                       boolean testMode) {
        verify(signatureHeader, rawBody, webhookSecret, testMode, DEFAULT_TOLERANCE_SECONDS);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private String computeHmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(raw);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Thrown when webhook signature verification fails.
     * Callers should respond with HTTP 403 and make no state changes.
     */
    public static class PayMongoSignatureException extends RuntimeException {
        public PayMongoSignatureException(String message) {
            super(message);
        }
    }
}
