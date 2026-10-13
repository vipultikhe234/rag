package com.productrag.chat.dto;

import com.productrag.chat.ChatConversation;
import java.time.Instant;
import java.util.UUID;

public record ConversationSummary(UUID id, String title, Instant createdAt, Instant updatedAt) {

    public static ConversationSummary from(ChatConversation conversation) {
        return new ConversationSummary(conversation.getId(), conversation.getTitle(), conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }
}
