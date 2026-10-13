package com.productrag.chat;

import com.productrag.auth.AuthenticatedUser;
import com.productrag.chat.dto.ChatAnswerResponse;
import com.productrag.chat.dto.ChatMessageResponse;
import com.productrag.chat.dto.ChatRequest;
import com.productrag.chat.dto.ConversationSummary;
import com.productrag.common.api.PageResponse;
import com.productrag.common.exception.NotFoundException;
import com.productrag.common.ratelimit.RateLimiter;
import com.productrag.config.AppProperties;
import com.productrag.rag.HistoryTurn;
import com.productrag.rag.QuestionNormalizer;
import com.productrag.rag.RagPlan;
import com.productrag.rag.RagService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

/**
 * Chat orchestration. Deliberately not transactional as a whole: the AI calls happen outside any
 * database transaction, and the question/answer pair is stored afterwards in one short transaction.
 */
@Service
public class ChatService {

    private static final int TITLE_LENGTH = 80;

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final RagService ragService;
    private final QuestionNormalizer normalizer;
    private final RateLimiter rateLimiter;
    private final EntityManager entityManager;
    private final TransactionOperations transactions;
    private final int chatPerMinute;
    private final int historyMessages;
    private final Clock clock;

    @Autowired
    public ChatService(ChatConversationRepository conversationRepository, ChatMessageRepository messageRepository,
                       RagService ragService, QuestionNormalizer normalizer, RateLimiter rateLimiter,
                       EntityManager entityManager, PlatformTransactionManager transactionManager,
                       AppProperties properties) {
        this(conversationRepository, messageRepository, ragService, normalizer, rateLimiter, entityManager,
                new TransactionTemplate(transactionManager), properties.rateLimit().chatPerMinute(),
                properties.rag().historyMessages(), Clock.systemUTC());
    }

    ChatService(ChatConversationRepository conversationRepository, ChatMessageRepository messageRepository,
                RagService ragService, QuestionNormalizer normalizer, RateLimiter rateLimiter,
                EntityManager entityManager, TransactionOperations transactions, int chatPerMinute,
                int historyMessages, Clock clock) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.ragService = ragService;
        this.normalizer = normalizer;
        this.rateLimiter = rateLimiter;
        this.entityManager = entityManager;
        this.transactions = transactions;
        this.chatPerMinute = chatPerMinute;
        this.historyMessages = historyMessages;
        this.clock = clock;
    }

    public ChatAnswerResponse ask(AuthenticatedUser user, ChatRequest request) {
        PreparedChat prepared = prepare(user, request);
        return save(prepared, ragService.generate(prepared.plan()));
    }

    /**
     * Rate limit, validation, ownership check and retrieval. Everything that can reject the request
     * happens here, before any answer generation starts.
     */
    public PreparedChat prepare(AuthenticatedUser user, ChatRequest request) {
        rateLimiter.acquire("chat:" + user.id(), chatPerMinute, Duration.ofMinutes(1));
        String question = normalizer.normalize(request.question());
        Instant askedAt = clock.instant();

        List<HistoryTurn> history = List.of();
        if (request.conversationId() != null) {
            ownedConversation(user.id(), request.conversationId());
            history = recentHistory(request.conversationId());
        }

        RagPlan plan = ragService.plan(question, history);
        boolean newConversation = request.conversationId() == null;
        UUID conversationId = newConversation ? UUID.randomUUID() : request.conversationId();
        return new PreparedChat(user.id(), conversationId, newConversation, question, askedAt, plan);
    }

    public Flux<String> stream(PreparedChat prepared) {
        return ragService.stream(prepared.plan());
    }

    /** Stores the question and the (possibly partial) answer in one short transaction. */
    public ChatAnswerResponse save(PreparedChat prepared, String answer) {
        RagPlan plan = prepared.plan();
        return transactions.execute(status -> {
            Instant answeredAt = clock.instant();
            ChatConversation conversation;
            if (prepared.newConversation()) {
                conversation = new ChatConversation(prepared.conversationId(), prepared.userId(),
                        title(prepared.question()), prepared.askedAt());
                entityManager.persist(conversation);
            } else {
                conversation = ownedConversation(prepared.userId(), prepared.conversationId());
            }
            conversation.touch(answeredAt);
            entityManager.persist(new ChatMessage(conversation.getId(), ChatMessage.Role.USER, prepared.question(),
                    List.of(), null, prepared.askedAt()));
            ChatMessage reply = new ChatMessage(conversation.getId(), ChatMessage.Role.ASSISTANT, answer,
                    plan.sources(), plan.retrieval(), answeredAt.isAfter(prepared.askedAt()) ? answeredAt
                    : prepared.askedAt().plusMillis(1));
            entityManager.persist(reply);
            return new ChatAnswerResponse(conversation.getId(), reply.getId(), answer, plan.sources(),
                    plan.retrieval(), reply.getCreatedAt());
        });
    }

    public PageResponse<ConversationSummary> conversations(AuthenticatedUser user, Pageable pageable) {
        return PageResponse.from(conversationRepository.findAllByUserId(user.id(), pageable),
                ConversationSummary::from);
    }

    public List<ChatMessageResponse> messages(AuthenticatedUser user, UUID conversationId) {
        ownedConversation(user.id(), conversationId);
        return messageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(ChatMessageResponse::from)
                .toList();
    }

    public ConversationSummary rename(AuthenticatedUser user, UUID conversationId, String title) {
        String cleaned = normalizer.normalize(title);
        return transactions.execute(status -> {
            ChatConversation conversation = ownedConversation(user.id(), conversationId);
            conversation.rename(title(cleaned));
            return ConversationSummary.from(conversation);
        });
    }

    public void delete(AuthenticatedUser user, UUID conversationId) {
        transactions.executeWithoutResult(status ->
                conversationRepository.delete(ownedConversation(user.id(), conversationId)));
    }

    private ChatConversation ownedConversation(UUID userId, UUID conversationId) {
        return conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new NotFoundException("CONVERSATION_NOT_FOUND", "Conversation not found"));
    }

    private List<HistoryTurn> recentHistory(UUID conversationId) {
        if (historyMessages == 0) {
            return List.of();
        }
        List<ChatMessage> recent = new ArrayList<>(messageRepository.findByConversationIdOrderByCreatedAtDesc(
                conversationId, PageRequest.of(0, historyMessages)));
        Collections.reverse(recent);
        return recent.stream()
                .map(m -> new HistoryTurn(m.getRole() == ChatMessage.Role.USER ? "User" : "Assistant", m.getContent()))
                .toList();
    }

    private static String title(String question) {
        return question.length() <= TITLE_LENGTH ? question : question.substring(0, TITLE_LENGTH - 1) + "…";
    }
}
