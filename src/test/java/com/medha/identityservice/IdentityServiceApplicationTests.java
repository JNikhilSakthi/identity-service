package com.medha.identityservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the full application context (H2 datasource, JPA, the
 * hand-built {@code JwtDecoder} bean, method security, etc.) must wire up
 * cleanly with no Keycloak instance running. This is possible because
 * {@code NimbusJwtDecoder} builds a lazy JWK client -- it only reaches out
 * to the jwk-set-uri the first time a token actually needs decoding, never
 * at startup.
 */
@SpringBootTest
class IdentityServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
