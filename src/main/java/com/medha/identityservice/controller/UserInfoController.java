package com.medha.identityservice.controller;

import com.medha.identityservice.dto.UserInfoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * A "whoami" endpoint that exists purely to make the SSO flow tangible: it
 * returns exactly what this service knows about the caller, which is
 * exactly what Keycloak put in the token's claims -- nothing more. There is
 * no local user table, no password, no session; identity is 100% delegated.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Whoami", description = "Inspect the identity claims Keycloak embedded in the caller's token")
public class UserInfoController {

    @GetMapping("/me")
    @Operation(summary = "Return the caller's identity as seen from their Keycloak JWT")
    public UserInfoResponse me(JwtAuthenticationToken authentication) {
        Jwt jwt = authentication.getToken();
        List<String> realmRoles = extractRealmRoles(jwt);
        return new UserInfoResponse(
                jwt.getSubject(),
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email"),
                realmRoles);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRealmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null || !realmAccess.containsKey("roles")) {
            return List.of();
        }
        return (List<String>) realmAccess.get("roles");
    }
}
