package com.medha.identityservice.dto;

import com.medha.identityservice.entity.Document;

import java.time.Instant;

/** Outbound representation of a {@link Document}. */
public record DocumentResponse(
        Long id,
        String title,
        String content,
        String ownerUsername,
        Instant createdAt,
        Instant updatedAt
) {
    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getTitle(),
                document.getContent(),
                document.getOwnerUsername(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
