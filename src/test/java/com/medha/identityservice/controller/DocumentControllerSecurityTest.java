package com.medha.identityservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medha.identityservice.config.SecurityConfig;
import com.medha.identityservice.dto.DocumentRequest;
import com.medha.identityservice.dto.DocumentResponse;
import com.medha.identityservice.security.KeycloakRealmRoleConverter;
import com.medha.identityservice.security.RestAccessDeniedHandler;
import com.medha.identityservice.security.RestAuthenticationEntryPoint;
import com.medha.identityservice.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security-focused integration test this project is really about:
 * proves the 401/403/200 matrix implied by
 * "USER or ADMIN can read/create, ADMIN-only can update/delete" -- without
 * needing a real, running Keycloak. {@code SecurityMockMvcRequestPostProcessors.jwt()}
 * fabricates an already-validated {@code Jwt} + authorities, which is the
 * standard, documented way to test method security rules driven by JWT
 * claims in isolation from the network calls a real token validation would
 * make.
 */
@WebMvcTest(DocumentController.class)
@Import({SecurityConfig.class, KeycloakRealmRoleConverter.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
@TestPropertySource(properties = {
        "security.jwt.issuer-uri=https://test-issuer.example.com/realms/test",
        "security.jwt.jwk-set-uri=https://test-issuer.example.com/realms/test/protocol/openid-connect/certs"
})
class DocumentControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DocumentService documentService;

    @Test
    void anonymousRequest_isRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/v1/documents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userRole_canListDocuments() throws Exception {
        when(documentService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/documents")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk());
    }

    @Test
    void userRole_canCreateDocument() throws Exception {
        DocumentRequest request = new DocumentRequest("Title", "Content");
        DocumentResponse response = new DocumentResponse(1L, "Title", "Content", "alice", Instant.now(), Instant.now());
        when(documentService.create(any(DocumentRequest.class), any(String.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/documents")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void userRole_isForbiddenFromDeleting() throws Exception {
        mockMvc.perform(delete("/api/v1/documents/1")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        verify(documentService, never()).delete(anyLong());
    }

    @Test
    void userRole_isForbiddenFromUpdating() throws Exception {
        DocumentRequest request = new DocumentRequest("Title", "Content");

        mockMvc.perform(put("/api/v1/documents/1")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRole_canDeleteDocument() throws Exception {
        doNothing().when(documentService).delete(1L);

        mockMvc.perform(delete("/api/v1/documents/1")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void adminRole_canUpdateDocument() throws Exception {
        DocumentRequest request = new DocumentRequest("New Title", "New Content");
        DocumentResponse response = new DocumentResponse(1L, "New Title", "New Content", "alice", Instant.now(), Instant.now());
        when(documentService.update(any(Long.class), any(DocumentRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/v1/documents/1")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void invalidPayload_isRejectedWith400() throws Exception {
        String blankTitlePayload = "{\"title\":\"\",\"content\":\"\"}";

        mockMvc.perform(post("/api/v1/documents")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType("application/json")
                        .content(blankTitlePayload))
                .andExpect(status().isBadRequest());
    }
}
