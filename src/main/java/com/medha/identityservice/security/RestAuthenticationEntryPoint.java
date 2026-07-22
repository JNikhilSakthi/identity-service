package com.medha.identityservice.security;

import com.medha.identityservice.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Runs when a request reaches a protected endpoint with no token, an
 * expired token, or a token that fails signature/issuer validation. This
 * fires from inside the Spring Security filter chain -- before the
 * DispatcherServlet -- which is why it cannot be a
 * {@code @RestControllerAdvice} handler; it has to be registered directly
 * as the resource server's {@code authenticationEntryPoint}.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                "Missing, expired, or invalid Keycloak access token: " + authException.getMessage(),
                request.getRequestURI());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
