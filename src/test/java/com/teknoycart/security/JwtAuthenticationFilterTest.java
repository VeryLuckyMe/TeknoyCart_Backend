package com.teknoycart.security;

import com.teknoycart.models.User;
import com.teknoycart.repositories.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class JwtAuthenticationFilterTest {

    @InjectMocks
    private JwtAuthenticationFilter filter;

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private UserRepository userRepository;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private UUID userId;
    private String email;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        userId = UUID.randomUUID();
        email = "test.user@cit.edu";
        ReflectionTestUtils.setField(filter, "userRepository", userRepository);
    }

    @Test
    @DisplayName("Filter sets authentication for verified and unlocked user")
    void testFilterAllowsVerifiedUnlockedUser() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid_token");
        when(tokenProvider.validateToken("valid_token")).thenReturn(true);
        when(tokenProvider.getUserIdFromToken("valid_token")).thenReturn(userId);
        when(tokenProvider.getEmailFromToken("valid_token")).thenReturn(email);

        User user = new User();
        user.setUserId(userId);
        user.setEmail(email);
        user.setVerified(true);
        user.setLocked(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        filter.doFilter(request, response, filterChain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(email, ((UserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getEmail());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Filter rejects unverified user even if JWT signature is valid (Finding H4)")
    void testFilterRejectsUnverifiedUser() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid_token");
        when(tokenProvider.validateToken("valid_token")).thenReturn(true);
        when(tokenProvider.getUserIdFromToken("valid_token")).thenReturn(userId);
        when(tokenProvider.getEmailFromToken("valid_token")).thenReturn(email);

        User user = new User();
        user.setUserId(userId);
        user.setEmail(email);
        user.setVerified(false); // Unverified!
        user.setLocked(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Filter rejects locked user even if JWT signature is valid")
    void testFilterRejectsLockedUser() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid_token");
        when(tokenProvider.validateToken("valid_token")).thenReturn(true);
        when(tokenProvider.getUserIdFromToken("valid_token")).thenReturn(userId);
        when(tokenProvider.getEmailFromToken("valid_token")).thenReturn(email);

        User user = new User();
        user.setUserId(userId);
        user.setEmail(email);
        user.setVerified(true);
        user.setLocked(true); // Locked!
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Filter rejects non-existent user (Finding H4)")
    void testFilterRejectsNonExistentUser() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid_token");
        when(tokenProvider.validateToken("valid_token")).thenReturn(true);
        when(tokenProvider.getUserIdFromToken("valid_token")).thenReturn(userId);
        when(tokenProvider.getEmailFromToken("valid_token")).thenReturn(email);

        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }
}
