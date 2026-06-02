package com.teknoycart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Enforce secure Bcrypt hashing with 12 rounds as mandated by the SRS/SDD
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> {}) // Enable Spring Security CORS handling to respect @CrossOrigin
            .authorizeHttpRequests(authorize -> authorize
                // Allow public access to authentication, error, and potential future developer APIs
                .requestMatchers("/auth/**", "/error").permitAll()
                // DEVELOPER TIP: If you add new business REST controllers (e.g., /api/products)
                // in the future, you must either:
                // 1. Add them to the permitAll list above (e.g., "/auth/**", "/error", "/api/**")
                // 2. Or implement a JWT Authentication Filter to authenticate incoming requests!
                .anyRequest().authenticated()
            );
        return http.build();
    }
}
