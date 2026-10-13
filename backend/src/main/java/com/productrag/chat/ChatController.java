package com.productrag.chat;

import com.productrag.auth.AuthenticatedUser;
import com.productrag.chat.dto.ChatAnswerResponse;
import com.productrag.chat.dto.ChatMessageResponse;
import com.productrag.chat.dto.ChatRequest;
import com.productrag.chat.dto.ConversationSummary;
import com.productrag.chat.dto.RenameConversationRequest;
import com.productrag.common.api.PageResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;
    private final ChatStreamer chatStreamer;

    public ChatController(ChatService chatService, ChatStreamer chatStreamer) {
        this.chatService = chatService;
        this.chatStreamer = chatStreamer;
    }

    @PostMapping
    public ChatAnswerResponse ask(@AuthenticationPrincipal AuthenticatedUser user,
                                  @Valid @RequestBody ChatRequest request) {
        return chatService.ask(user, request);
    }

    /**
     * Same as {@link #ask} but streams the answer as server-sent events (see {@code ChatStreamEvents}).
     * Validation, rate-limit and ownership errors are returned as regular JSON errors before the stream
     * starts, so clients should send {@code Accept: text/event-stream, application/json}.
     */
    @PostMapping("/stream")
    public SseEmitter stream(@AuthenticationPrincipal AuthenticatedUser user,
                             @Valid @RequestBody ChatRequest request) {
        return chatStreamer.start(chatService.prepare(user, request));
    }

    @GetMapping("/conversations")
    public PageResponse<ConversationSummary> conversations(@AuthenticationPrincipal AuthenticatedUser user,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        return chatService.conversations(user, pageable);
    }

    @GetMapping("/conversations/{id}/messages")
    public List<ChatMessageResponse> messages(@AuthenticationPrincipal AuthenticatedUser user,
                                              @PathVariable UUID id) {
        return chatService.messages(user, id);
    }

    @PatchMapping("/conversations/{id}")
    public ConversationSummary rename(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                      @Valid @RequestBody RenameConversationRequest request) {
        return chatService.rename(user, id, request.title());
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        chatService.delete(user, id);
        return ResponseEntity.noContent().build();
    }
}
