package com.medha.identityservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound payload for create/update. Kept separate from the entity so the
 * persistence model can evolve independently of the wire contract, and so
 * bean validation rules live on the boundary, not on the domain model.
 */
public record DocumentRequest(

        @NotBlank(message = "title must not be blank")
        @Size(max = 200, message = "title must be at most 200 characters")
        String title,

        @NotBlank(message = "content must not be blank")
        @Size(max = 4000, message = "content must be at most 4000 characters")
        String content
) {
}
