package com.medha.identityservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the identity-service.
 *
 * <p>This service does NOT implement authentication itself. It is a pure
 * OAuth2 Resource Server: it trusts a Keycloak realm as the single source of
 * truth for identity, and its only security job is to validate the JWT
 * access tokens Keycloak issues and enforce role-based access control based
 * on the realm roles embedded in those tokens.</p>
 */
@SpringBootApplication
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
