package com.teknoycart.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired(required = false)
    private com.teknoycart.repositories.UserRepository userRepository;

    private static class CachedUserStatus {
        final boolean verified;
        final boolean locked;
        final long cachedAt;

        CachedUserStatus(boolean verified, boolean locked) {
            this.verified = verified;
            this.locked = locked;
            this.cachedAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > 60_000;
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<String, CachedUserStatus> statusCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt) && tokenProvider.validateToken(jwt)) {
                UUID userId = tokenProvider.getUserIdFromToken(jwt);
                String email = tokenProvider.getEmailFromToken(jwt);

                boolean allowed = true;
                if (userRepository != null && (userId != null || (email != null && !email.isBlank()))) {
                    String cacheKey = userId != null ? userId.toString() : email.toLowerCase().trim();
                    CachedUserStatus status = statusCache.get(cacheKey);

                    if (status == null || status.isExpired()) {
                        java.util.Optional<com.teknoycart.models.User> userOpt = userId != null
                                ? userRepository.findById(userId)
                                : userRepository.findByEmail(email.toLowerCase().trim());

                        if (userOpt.isEmpty()) {
                            allowed = false;
                        } else {
                            com.teknoycart.models.User u = userOpt.get();
                            status = new CachedUserStatus(u.isVerified(), u.isLocked());
                            statusCache.put(cacheKey, status);
                            allowed = status.verified && !status.locked;
                        }
                    } else {
                        allowed = status.verified && !status.locked;
                    }
                }

                if (allowed) {
                    UserPrincipal principal = new UserPrincipal(userId, email);

                    List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                            new SimpleGrantedAuthority("ROLE_USER")
                    );

                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            principal, null, authorities
                    );
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    SecurityContextHolder.clearContext();
                }
            }
        } catch (Exception ex) {
            logger.error("Could not set user authentication in security context", ex);
        }

        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
