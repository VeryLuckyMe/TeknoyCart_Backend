package com.teknoycart.config;

import com.teknoycart.security.JwtAuthenticationFilter;
import com.teknoycart.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
@TestPropertySource(properties = {
    "cors.allowed-origins=https://teknoy-cart-web.vercel.app",
    "jwt.secret=TEST_ONLY_JWT_SECRET_KEY_FOR_CORS_TESTING_MINIMUM_256_BITS",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    "spring.jpa.hibernate.ddl-auto=none"
})
public class CorsSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @MockBean
    private com.teknoycart.repositories.UserRepository userRepository;

    @MockBean
    private com.teknoycart.repositories.StoreRepository storeRepository;

    @MockBean
    private com.teknoycart.repositories.OrderRepository orderRepository;

    @MockBean
    private com.teknoycart.repositories.OrderAuditLogRepository orderAuditLogRepository;

    @MockBean
    private com.teknoycart.services.EmailService emailService;

    @Test
    @DisplayName("CORS preflight from allowed origin (Vercel) returns 200 with correct headers")
    void testAllowedOriginReturnsCorrectCorsHeaders() throws Exception {
        mockMvc.perform(options("/auth/login")
                .header("Origin", "https://teknoy-cart-web.vercel.app")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://teknoy-cart-web.vercel.app"))
            .andExpect(header().exists("Access-Control-Allow-Methods"));
    }

    @Test
    @DisplayName("CORS preflight from disallowed origin is rejected (no Access-Control-Allow-Origin header)")
    void testDisallowedOriginIsRejected() throws Exception {
        mockMvc.perform(options("/auth/login")
                .header("Origin", "https://evil-site.com")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
            .andExpect(status().isForbidden());
    }
}
