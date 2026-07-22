package com.medha.identityservice.controller;

import com.medha.identityservice.dto.DocumentRequest;
import com.medha.identityservice.dto.DocumentResponse;
import com.medha.identityservice.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Document CRUD, the "business domain" for this Keycloak/SSO demo.
 *
 * <p>Authorization policy (realm roles, enforced with method security):</p>
 * <ul>
 *   <li>USER or ADMIN: create, read</li>
 *   <li>ADMIN only: update, delete</li>
 * </ul>
 *
 * <p>Every method here assumes Spring Security has already validated the
 * bearer token's signature, expiry and issuer (see {@code SecurityConfig})
 * before the request arrives -- {@code @PreAuthorize} only ever sees
 * already-trusted claims.</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents", description = "CRUD for the protected business resource, gated by Keycloak realm roles")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Operation(summary = "Create a document (USER or ADMIN)")
    public ResponseEntity<DocumentResponse> create(@Valid @RequestBody DocumentRequest request,
                                                    JwtAuthenticationToken authentication) {
        String username = preferredUsername(authentication.getToken());
        DocumentResponse created = documentService.create(request, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Operation(summary = "List all documents (USER or ADMIN)")
    public ResponseEntity<List<DocumentResponse>> findAll() {
        return ResponseEntity.ok(documentService.findAll());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Operation(summary = "Get a document by id (USER or ADMIN)")
    public ResponseEntity<DocumentResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(documentService.findById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update a document (ADMIN only)")
    public ResponseEntity<DocumentResponse> update(@PathVariable Long id, @Valid @RequestBody DocumentRequest request) {
        return ResponseEntity.ok(documentService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a document (ADMIN only)")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private String preferredUsername(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        return username != null ? username : jwt.getSubject();
    }
}
