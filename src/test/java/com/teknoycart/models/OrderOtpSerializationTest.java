package com.teknoycart.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class OrderOtpSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Order API responses never expose the handoff or return OTP")
    void otpValuesAreNotSerialized() throws Exception {
        Order order = new Order();
        order.setHandoffOtp("123456");
        order.setReturnOtp("654321");

        String json = mapper.writeValueAsString(order);

        assertFalse(json.contains("123456"), "handoff OTP leaked into the API response");
        assertFalse(json.contains("654321"), "return OTP leaked into the API response");
        assertFalse(json.contains("\"handoffOtp\":"), "handoffOtp field is still serialized");
        assertFalse(json.contains("\"returnOtp\":"), "returnOtp field is still serialized");
    }
}
