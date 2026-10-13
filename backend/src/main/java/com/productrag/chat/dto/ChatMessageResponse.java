package com.productrag.chat.dto;

import com.productrag.chat.ChatMessage;
import com.productrag.rag.dto.RetrievalInfo;
import com.productrag.rag.dto.SourceReference;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChatMessageResponse(
        UUID id,
        String role,
        String content,
        List<SourceReference> sources,
        RetrievalInfo retrieval,
        Instant createdAt) {

    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(message.getId(), message.getRole().name(), message.getContent(),
                message.getSources(), message.getRetrieval(), message.getCreatedAt());
    }
}
