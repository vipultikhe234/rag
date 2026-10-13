package com.productrag.chat.dto;

import com.productrag.rag.dto.RetrievalInfo;
import com.productrag.rag.dto.SourceReference;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChatAnswerResponse(
        UUID conversationId,
        UUID messageId,
        String answer,
        List<SourceReference> sources,
        RetrievalInfo retrieval,
        Instant createdAt) {
}
