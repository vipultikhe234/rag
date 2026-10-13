package com.productrag.chat;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {

    /** Ownership-scoped lookup: another user's conversation is indistinguishable from a missing one. */
    Optional<ChatConversation> findByIdAndUserId(UUID id, UUID userId);

    Page<ChatConversation> findAllByUserId(UUID userId, Pageable pageable);
}
