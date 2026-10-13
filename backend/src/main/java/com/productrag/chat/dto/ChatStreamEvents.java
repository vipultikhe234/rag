package com.productrag.chat.dto;

import com.productrag.rag.dto.RetrievalInfo;
import com.productrag.rag.dto.SourceReference;
import java.util.List;
import java.util.UUID;

/**
 * Payloads of the server-sent events emitted by {@code POST /api/v1/chat/stream}, in order:
 * one {@code meta}, any number of {@code delta}, then exactly one {@code done} (a {@link ChatAnswerResponse})
 * or {@code error}.
 */
public final class ChatStreamEvents {

    public static final String META = "meta";
    public static final String DELTA = "delta";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    private ChatStreamEvents() {
    }

    public record Meta(UUID conversationId, List<SourceReference> sources, RetrievalInfo retrieval) {
    }

    public record Delta(String text) {
    }

    public record Error(String code, String message) {
    }
}
