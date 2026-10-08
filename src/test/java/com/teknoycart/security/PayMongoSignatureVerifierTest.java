package com.teknoycart.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class PayMongoSignatureVerifierTest {

    private PayMongoSignatureVerifier verifier;
    private final String secret = "whsk_test_secret_key_12345";

    @BeforeEach
    void setUp() {
        verifier = new PayMongoSignatureVerifier();
    }

    private String calculateHmac(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    @DisplayName("Valid test signature with current timestamp passes verification")
    void testValidSignatureInTestMode() throws Exception {
        byte[] body = "{\"data\":{\"id\":\"evt_123\"}}".getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        String payloadToSign = now + "." + new String(body, StandardCharsets.UTF_8);
        String hmac = calculateHmac(payloadToSign, secret);

        String header = "t=" + now + ",te=" + hmac + ",li=";

        assertDoesNotThrow(() -> verifier.verify(header, body, secret, true));
    }

    @Test
    @DisplayName("Valid live signature with current timestamp passes verification")
    void testValidSignatureInLiveMode() throws Exception {
        byte[] body = "{\"data\":{\"id\":\"evt_live_123\"}}".getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        String payloadToSign = now + "." + new String(body, StandardCharsets.UTF_8);
        String hmac = calculateHmac(payloadToSign, secret);

        String header = "t=" + now + ",te=,li=" + hmac;

        assertDoesNotThrow(() -> verifier.verify(header, body, secret, false));
    }

    @Test
    @DisplayName("Missing header throws PayMongoSignatureException")
    void testMissingHeaderThrows() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThrows(PayMongoSignatureVerifier.PayMongoSignatureException.class,
                () -> verifier.verify(null, body, secret, true));
        assertThrows(PayMongoSignatureVerifier.PayMongoSignatureException.class,
                () -> verifier.verify("   ", body, secret, true));
    }

    @Test
    @DisplayName("Timestamp outside 300s tolerance throws PayMongoSignatureException (anti-replay)")
    void testExpiredTimestampThrows() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long oldTs = Instant.now().getEpochSecond() - 301;
        String payloadToSign = oldTs + "." + new String(body, StandardCharsets.UTF_8);
        String hmac = calculateHmac(payloadToSign, secret);

        String header = "t=" + oldTs + ",te=" + hmac + ",li=";

        PayMongoSignatureVerifier.PayMongoSignatureException ex = assertThrows(
                PayMongoSignatureVerifier.PayMongoSignatureException.class,
                () -> verifier.verify(header, body, secret, true)
        );
        assertTrue(ex.getMessage().contains("outside tolerance window"));
    }

    @Test
    @DisplayName("Tampered body payload fails signature check")
    void testTamperedBodyThrows() throws Exception {
        byte[] originalBody = "{\"data\":{\"amount\":10000}}".getBytes(StandardCharsets.UTF_8);
        byte[] tamperedBody = "{\"data\":{\"amount\":1}}".getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        String payloadToSign = now + "." + new String(originalBody, StandardCharsets.UTF_8);
        String hmac = calculateHmac(payloadToSign, secret);

        String header = "t=" + now + ",te=" + hmac + ",li=";

        assertThrows(PayMongoSignatureVerifier.PayMongoSignatureException.class,
                () -> verifier.verify(header, tamperedBody, secret, true));
    }

    @Test
    @DisplayName("Missing te in test mode throws PayMongoSignatureException")
    void testMissingTestHmacThrows() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        String header = "t=" + now + ",li=some_live_hmac";

        assertThrows(PayMongoSignatureVerifier.PayMongoSignatureException.class,
                () -> verifier.verify(header, body, secret, true));
    }
}
