package com.medha.identityservice.config;

import com.medha.identityservice.security.KeycloakRealmRoleConverter;
import com.medha.identityservice.security.RestAccessDeniedHandler;
import com.medha.identityservice.security.RestAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Wires this service as an OAuth2 Resource Server: stateless, JWT-only,
 * every endpoint locked down by default except the small allow-list below.
 *
 * <h2>The issuer-vs-jwk-set-uri split (the #1 real-world Keycloak gotcha)</h2>
 * <p>When this app and Keycloak both run inside the same Docker Compose
 * network, but a human (or curl) reaches Keycloak from the host machine to
 * fetch a token, the token's {@code iss} claim contains whatever hostname
 * the *client* used -- typically {@code http://localhost:8080/realms/...}.
 * If the resource server were configured with a single
 * {@code issuer-uri=http://localhost:8080/...}, Spring would try to reach
 * {@code localhost:8080} *from inside the app's own container* to fetch the
 * signing keys, which resolves to the app container itself, not Keycloak --
 * connection refused.</p>
 *
 * <p>The fix used here: fetch keys from the Docker-internal address
 * ({@code jwk-set-uri=http://keycloak:8080/...}, reachable container-to-
 * container) while still validating the {@code iss} claim against the
 * externally-visible address ({@code issuer-uri=http://localhost:8080/...},
 * what's actually inside the token). A single {@code issuer-uri} property
 * cannot express this split, so we build the {@link JwtDecoder} bean by
 * hand instead of relying on Spring Boot's oauth2 resourceserver
 * auto-configuration.</p>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final KeycloakRealmRoleConverter keycloakRealmRoleConverter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Value("${security.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${security.jwt.jwk-set-uri}")
    private String jwkSetUri;

    public SecurityConfig(KeycloakRealmRoleConverter keycloakRealmRoleConverter,
                           RestAuthenticationEntryPoint authenticationEntryPoint,
                           RestAccessDeniedHandler accessDeniedHandler) {
        this.keycloakRealmRoleConverter = keycloakRealmRoleConverter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
        return decoder;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health/**",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder()).jwtAuthenticationConverter(keycloakRealmRoleConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }
}
