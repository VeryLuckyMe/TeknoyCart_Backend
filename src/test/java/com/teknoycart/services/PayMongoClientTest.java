package com.teknoycart.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

public class PayMongoClientTest {

    private PayMongoClient payMongoClient;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        payMongoClient = new PayMongoClient();
        ReflectionTestUtils.setField(payMongoClient, "secretKey", "sk_test_mock_key_123");
        ReflectionTestUtils.setField(payMongoClient, "baseUrl", "https://api.paymongo.com");

        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(payMongoClient, "restTemplate");
        assertNotNull(restTemplate);
        mockServer = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    @DisplayName("createGcashCheckoutSession sends line_items array as required by PayMongo API")
    void testCreateGcashCheckoutSessionPayload() {
        String mockResponse = """
            {
              "data": {
                "id": "cs_test_session_999",
                "type": "checkout_session",
                "attributes": {
                  "checkout_url": "https://checkout.paymongo.com/cs_test_session_999"
                }
              }
            }
            """;

        mockServer.expect(requestTo("https://api.paymongo.com/v1/checkout_sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.data.attributes.line_items[0].amount").value(15000))
                .andExpect(jsonPath("$.data.attributes.line_items[0].currency").value("PHP"))
                .andExpect(jsonPath("$.data.attributes.line_items[0].quantity").value(1))
                .andExpect(jsonPath("$.data.attributes.payment_method_types[0]").value("gcash"))
                .andExpect(jsonPath("$.data.attributes.reference_number").value("ord-uuid-123"))
                .andRespond(withSuccess(mockResponse, MediaType.APPLICATION_JSON));

        PayMongoClient.CheckoutSessionResult result = payMongoClient.createGcashCheckoutSession(
                15000L,
                "ord-uuid-123",
                "TeknoyCart Order",
                "https://teknoycart.app/success",
                "https://teknoycart.app/cancel",
                1800000000L);

        mockServer.verify();
        assertNotNull(result);
        assertEquals("cs_test_session_999", result.sessionId());
        assertEquals("https://checkout.paymongo.com/cs_test_session_999", result.checkoutUrl());
    }

    @Test
    @DisplayName("createGcashCheckoutSession parses error details on 400 Bad Request")
    void testCreateGcashCheckoutSession400Error() {
        String errorResponse = """
            {
              "errors": [
                {
                  "code": "invalid_request_body",
                  "detail": "Parameter line_items is required"
                }
              ]
            }
            """;

        mockServer.expect(requestTo("https://api.paymongo.com/v1/checkout_sessions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withBadRequest().body(errorResponse).contentType(MediaType.APPLICATION_JSON));

        PayMongoClient.PayMongoException ex = assertThrows(PayMongoClient.PayMongoException.class, () -> {
            payMongoClient.createGcashCheckoutSession(
                    15000L,
                    "ord-uuid-123",
                    "TeknoyCart Order",
                    "https://teknoycart.app/success",
                    "https://teknoycart.app/cancel",
                    1800000000L);
        });

        assertTrue(ex.getMessage().contains("Parameter line_items is required"));
        mockServer.verify();
    }
}
