package com.medha.identityservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documents the Bearer-token requirement so "Try it out" in Swagger UI can send a Keycloak access token. */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "keycloak-bearer";

    @Bean
    public OpenAPI identityServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("identity-service API")
                        .description("Enterprise SSO demo: Document CRUD protected by Keycloak-issued JWTs (Project 4/17 - springboot-keycloak-sso-demo)")
                        .version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
