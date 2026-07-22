package com.medha.identityservice.dto;

import java.util.List;

/**
 * A small "whoami" projection of the caller's JWT — handy for demonstrating
 * that the app never sees a password, only the claims Keycloak vouched for.
 */
public record UserInfoResponse(
        String subject,
        String preferredUsername,
        String email,
        List<String> realmRoles
) {
}
