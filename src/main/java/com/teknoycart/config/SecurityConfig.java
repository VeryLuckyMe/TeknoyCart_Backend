package com.teknoycart.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.teknoycart.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${cors.allowed-origins}")
    private String[] allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Enforce secure Bcrypt hashing with 12 rounds as mandated by the SRS/SDD
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable) // Global disable - acceptable for JWT API; CSRF-only exemption for webhook is moot
            .cors(cors -> cors.configurationSource(corsConfigurationSource())) // Enable Spring Security CORS handling to respect @CrossOrigin
            .authorizeHttpRequests(authorize -> authorize
                // Seller upgrade requires authentication (must come before /auth/** permitAll)
                .requestMatchers("/auth/request-seller-upgrade").authenticated()
                // Allow public access to authentication, error, and potential future developer APIs
                .requestMatchers("/auth/**", "/error", "/stores/**").permitAll()
                // PayMongo webhook & payment return redirects: unauthenticated
                // Full public URL is /api/webhooks/** and /api/payments/** (context-path /api prepended by server config)
                .requestMatchers("/webhooks/**", "/payments/**").permitAll()
                .requestMatchers("/orders/**").authenticated()
                // Actuator health probe for container/orchestrator healthchecks: unauthenticated
                .requestMatchers("/actuator/health").permitAll()
                // DEVELOPER TIP: If you add new business REST controllers (e.g., /api/products)
                // in the future, you must either:
                // 1. Add them to the permitAll list above (e.g., "/auth/**", "/error", "/api/**")
                // 2. Or implement a JWT Authentication Filter to authenticate incoming requests!
                .anyRequest().authenticated()
            );

        // Register JWT validation filter before standard Spring authentication check
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        if (allowedOrigins != null && allowedOrigins.length > 0 && !Arrays.asList(allowedOrigins).contains("*")) {
            configuration.setAllowedOriginPatterns(Arrays.asList(allowedOrigins));
        } else {
            configuration.setAllowedOriginPatterns(List.of("*"));
        }
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setExposedHeaders(Arrays.asList("Authorization"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

