package com.productrag.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.productrag.auth.AuthenticatedUser;
import com.productrag.auth.Role;
import com.productrag.chat.dto.ChatAnswerResponse;
import com.productrag.chat.dto.ChatRequest;
import com.productrag.common.exception.BadRequestException;
import com.productrag.common.exception.NotFoundException;
import com.productrag.common.exception.RateLimitExceededException;
import com.productrag.common.ratelimit.RateLimiter;
import com.productrag.rag.QuestionNormalizer;
import com.productrag.rag.RagPlan;
import com.productrag.rag.RagService;
import com.productrag.rag.dto.RetrievalInfo;
import com.productrag.support.TestProperties;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

class ChatServiceTest {

    private final AuthenticatedUser alice = new AuthenticatedUser(UUID.randomUUID(), "alice@example.com", "Alice",
            Role.USER);
    private final AuthenticatedUser bob = new AuthenticatedUser(UUID.randomUUID(), "bob@example.com", "Bob",
            Role.ADMIN);

    private ChatConversationRepository conversations;
    private ChatMessageRepository messages;
    private RagService ragService;
    private RateLimiter rateLimiter;
    private EntityManager entityManager;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        conversations = mock(ChatConversationRepository.class);
        messages = mock(ChatMessageRepository.class);
        ragService = mock(RagService.class);
        rateLimiter = mock(RateLimiter.class);
        entityManager = mock(EntityManager.class);
        chatService = new ChatService(conversations, messages, ragService,
                new QuestionNormalizer(TestProperties.defaults()), rateLimiter, entityManager,
                TransactionOperations.withoutTransaction(), 20, 6,
                Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneOffset.UTC));
        RagPlan plan = RagPlan.llm("system", "user", List.of(),
                new RetrievalInfo("SEMANTIC", List.of("status = ACTIVE"), null, true));
        when(ragService.plan(anyString(), anyList())).thenReturn(plan);
        when(ragService.generate(plan)).thenReturn("answer");
    }

    @Test
    void newQuestionCreatesConversationAndStoresBothMessages() {
        ChatAnswerResponse response = chatService.ask(alice, new ChatRequest("  Which   headphones\nare best? ", null));

        assertThat(response.conversationId()).isNotNull();
        assertThat(response.answer()).isEqualTo("answer");
        verify(ragService).plan(eq("Which headphones are best?"), eq(List.of()));
        ArgumentCaptor<Object> persisted = ArgumentCaptor.forClass(Object.class);
        verify(entityManager, org.mockito.Mockito.times(3)).persist(persisted.capture());
        assertThat(persisted.getAllValues()).hasAtLeastOneElementOfType(ChatConversation.class);
        ChatConversation conversation = (ChatConversation) persisted.getAllValues().getFirst();
        assertThat(conversation.getUserId()).isEqualTo(alice.id());
    }

    @Test
    void usersCannotUseAnotherUsersConversation() {
        UUID bobsConversation = UUID.randomUUID();
        when(conversations.findByIdAndUserId(bobsConversation, alice.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.ask(alice, new ChatRequest("hello", bobsConversation)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> chatService.messages(alice, bobsConversation))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> chatService.delete(alice, bobsConversation))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> chatService.rename(alice, bobsConversation, "mine now"))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(ragService);
        verify(messages, never()).findAllByConversationIdOrderByCreatedAtAsc(any());
    }

    @Test
    void ownerCanReadOwnConversation() {
        UUID id = UUID.randomUUID();
        when(conversations.findByIdAndUserId(id, bob.id()))
                .thenReturn(Optional.of(new ChatConversation(id, bob.id(), "title", Instant.now())));
        when(messages.findAllByConversationIdOrderByCreatedAtAsc(id)).thenReturn(List.of());

        assertThat(chatService.messages(bob, id)).isEmpty();
    }

    @Test
    void ownerCanRenameConversation() {
        UUID id = UUID.randomUUID();
        when(conversations.findByIdAndUserId(id, bob.id()))
                .thenReturn(Optional.of(new ChatConversation(id, bob.id(), "old", Instant.now())));

        assertThat(chatService.rename(bob, id, "  Travel   gear ").title()).isEqualTo("Travel gear");
    }

    @Test
    void preparedNewConversationGetsIdUpFrontAndIsSavedUnderIt() {
        PreparedChat prepared = chatService.prepare(alice, new ChatRequest("hello", null));

        assertThat(prepared.newConversation()).isTrue();
        assertThat(prepared.conversationId()).isNotNull();
        ChatAnswerResponse saved = chatService.save(prepared, "partial answer");
        assertThat(saved.conversationId()).isEqualTo(prepared.conversationId());
        assertThat(saved.answer()).isEqualTo("partial answer");
        verify(ragService, never()).generate(any());
    }

    @Test
    void blankQuestionIsRejectedBeforeAnyAiCall() {
        assertThatThrownBy(() -> chatService.ask(alice, new ChatRequest("\u0000 \t ", null)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ragService);
    }

    @Test
    void rateLimitIsEnforcedPerUser() {
        org.mockito.Mockito.doThrow(new RateLimitExceededException(30)).when(rateLimiter)
                .acquire(eq("chat:" + alice.id()), eq(20), eq(Duration.ofMinutes(1)));

        assertThatThrownBy(() -> chatService.ask(alice, new ChatRequest("hello", null)))
                .isInstanceOf(RateLimitExceededException.class);
        verifyNoInteractions(ragService);
    }
}
