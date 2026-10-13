package com.productrag.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ChatRequest(
        @NotBlank(message = "Question is required")
        @Size(max = 2000, message = "Question must be at most 2000 characters")
        String question,

        UUID conversationId) {
}
