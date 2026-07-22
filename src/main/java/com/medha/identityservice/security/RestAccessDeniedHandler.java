package com.medha.identityservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medha.identityservice.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Runs when a request carries a valid, correctly-signed Keycloak token, but
 * the caller's realm roles don't satisfy the endpoint's
 * {@code @PreAuthorize}/{@code hasRole(...)} rule -- e.g. a USER calling
 * DELETE. Registered as the resource server's {@code accessDeniedHandler}
 * so the filter-chain-level 403 gets the same JSON envelope as the
 * controller-level 403 in {@link com.medha.identityservice.exception.GlobalExceptionHandler}.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.FORBIDDEN.value(),
                "Forbidden",
                "Your token is valid but lacks the realm role required for this operation",
                request.getRequestURI());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
