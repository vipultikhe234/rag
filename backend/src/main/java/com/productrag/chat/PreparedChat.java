package com.productrag.chat;

import com.productrag.rag.RagPlan;
import java.time.Instant;
import java.util.UUID;

/**
 * A validated question with its retrieval result, ready for answer generation. For a new conversation the
 * id is chosen up front (so a streaming client learns it immediately) but the row is only written on save.
 */
public record PreparedChat(UUID userId, UUID conversationId, boolean newConversation, String question,
                           Instant askedAt, RagPlan plan) {
}
