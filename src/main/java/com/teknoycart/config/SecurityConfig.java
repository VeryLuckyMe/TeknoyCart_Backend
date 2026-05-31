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
                // Allow public access to authentication and error endpoints
                .requestMatchers("/auth/**", "/error").permitAll()
                // All other business logic require authorization
                .anyRequest().authenticated()
            );
        return http.build();
    }
}
