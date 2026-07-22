package com.medha.identityservice.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Bridges Keycloak's token shape to Spring Security's authority model.
 *
 * <p>Keycloak does NOT put roles in the standard {@code scope}/{@code scp}
 * claim that {@link JwtGrantedAuthoritiesConverter} looks at by default.
 * Realm roles arrive nested as:
 * <pre>
 * {
 *   "realm_access": { "roles": ["ADMIN", "USER"] }
 * }
 * </pre>
 * This converter reads that claim and maps each realm role to a
 * {@code ROLE_&lt;name&gt;} {@link GrantedAuthority}, which is what
 * {@code hasRole("ADMIN")} / {@code @PreAuthorize("hasRole('ADMIN')")}
 * expect. Client roles (under {@code resource_access.<client-id>.roles})
 * are intentionally out of scope for this demo — this project models
 * enterprise-wide realm roles (ADMIN/USER), not per-application roles.</p>
 */
@Component
public class KeycloakRealmRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_CLAIM = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtGrantedAuthoritiesConverter defaultScopeConverter = new JwtGrantedAuthoritiesConverter();
    private final JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();

    public KeycloakRealmRoleConverter() {
        delegate.setJwtGrantedAuthoritiesConverter(this::extractAllAuthorities);
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return delegate.convert(jwt);
    }

    private Collection<GrantedAuthority> extractAllAuthorities(Jwt jwt) {
        return Stream.concat(
                        defaultScopeConverter.convert(jwt).stream(),
                        extractRealmRoles(jwt).stream())
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private Set<GrantedAuthority> extractRealmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (realmAccess == null || !realmAccess.containsKey(ROLES_CLAIM)) {
            return Set.of();
        }
        List<String> roles = (List<String>) realmAccess.get(ROLES_CLAIM);
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .collect(Collectors.toSet());
    }
}
