package com.productrag.chat;

import com.productrag.ai.AiProviderException;
import com.productrag.chat.dto.ChatAnswerResponse;
import com.productrag.chat.dto.ChatStreamEvents;
import com.productrag.common.exception.ApiException;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * Streams an answer to the browser as server-sent events while the model generates it. The exchange is
 * stored once generation ends; if the client disconnects first (e.g. "Stop generating"), generation is
 * cancelled and the partial answer is stored.
 */
@Component
public class ChatStreamer {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamer.class);
    private static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(10);

    private final ChatService chatService;

    public ChatStreamer(ChatService chatService) {
        this.chatService = chatService;
    }

    public SseEmitter start(PreparedChat prepared) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
        new Session(prepared, emitter).start();
        return emitter;
    }

    private final class Session {

        private final PreparedChat prepared;
        private final SseEmitter emitter;
        private final StringBuilder answer = new StringBuilder();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile Disposable subscription;

        Session(PreparedChat prepared, SseEmitter emitter) {
            this.prepared = prepared;
            this.emitter = emitter;
        }

        void start() {
            emitter.onTimeout(this::abandon);
            emitter.onError(ex -> abandon());
            emitter.onCompletion(this::abandon);
            if (!send(ChatStreamEvents.META, new ChatStreamEvents.Meta(prepared.conversationId(),
                    prepared.plan().sources(), prepared.plan().retrieval()))) {
                abandon();
                return;
            }
            Disposable disposable = chatService.stream(prepared).subscribe(this::onChunk, this::onError,
                    this::onComplete);
            subscription = disposable;
            if (finished.get()) {
                disposable.dispose();
            }
        }

        private void onChunk(String chunk) {
            if (finished.get()) {
                return;
            }
            synchronized (answer) {
                answer.append(chunk);
            }
            if (!send(ChatStreamEvents.DELTA, new ChatStreamEvents.Delta(chunk))) {
                abandon();
            }
        }

        private void onComplete() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            String text = currentAnswer();
            if (text.isEmpty()) {
                failWith(AiProviderException.emptyResponse());
                return;
            }
            try {
                ChatAnswerResponse saved = chatService.save(prepared, text);
                send(ChatStreamEvents.DONE, saved);
                emitter.complete();
            } catch (RuntimeException ex) {
                failWith(ex);
            }
        }

        private void onError(Throwable ex) {
            if (finished.compareAndSet(false, true)) {
                failWith(ex);
            }
        }

        private void failWith(Throwable ex) {
            ChatStreamEvents.Error error;
            if (ex instanceof ApiException api) {
                if (api.getStatus().is5xxServerError()) {
                    log.warn("Chat stream failed: {} ({}){}", api.getCode(), api.getMessage(), rootCause(api));
                }
                error = new ChatStreamEvents.Error(api.getCode(), api.getMessage());
            } else {
                log.error("Unexpected chat stream failure", ex);
                error = new ChatStreamEvents.Error("INTERNAL_ERROR",
                        "An unexpected error occurred. Please try again later.");
            }
            send(ChatStreamEvents.ERROR, error);
            emitter.complete();
        }

        /** Client gone or emitter timed out: stop generating and keep whatever was already produced. */
        private void abandon() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            Disposable current = subscription;
            if (current != null) {
                current.dispose();
            }
            String partial = currentAnswer();
            if (!partial.isEmpty()) {
                try {
                    chatService.save(prepared, partial);
                } catch (RuntimeException ex) {
                    log.warn("Could not store partial chat answer: {}", ex.getMessage());
                }
            }
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // Already completed by the container.
            }
        }

        private static String rootCause(Throwable ex) {
            Throwable root = ex.getCause();
            if (root == null) {
                return "";
            }
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String message = String.valueOf(root.getMessage());
            return " caused by " + root.getClass().getSimpleName() + ": "
                    + (message.length() > 300 ? message.substring(0, 300) + "…" : message);
        }

        private String currentAnswer() {
            synchronized (answer) {
                return answer.toString().strip();
            }
        }

        private boolean send(String event, Object data) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
                return true;
            } catch (IOException | IllegalStateException ex) {
                return false;
            }
        }
    }
}
