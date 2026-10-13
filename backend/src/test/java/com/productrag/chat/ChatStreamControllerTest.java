package com.productrag.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.productrag.ai.AiProviderException;
import com.productrag.auth.AppUser;
import com.productrag.auth.AppUserRepository;
import com.productrag.auth.JwtService;
import com.productrag.auth.RestSecurityHandlers;
import com.productrag.auth.Role;
import com.productrag.chat.dto.ChatAnswerResponse;
import com.productrag.common.api.ApiErrorWriter;
import com.productrag.common.exception.NotFoundException;
import com.productrag.config.AppProperties;
import com.productrag.config.SecurityConfig;
import com.productrag.rag.RagPlan;
import com.productrag.rag.dto.RetrievalInfo;
import com.productrag.support.TestProperties;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

@WebMvcTest(ChatController.class)
@EnableConfigurationProperties(AppProperties.class)
@Import({SecurityConfig.class, JwtService.class, RestSecurityHandlers.class, ApiErrorWriter.class, ChatStreamer.class})
@TestPropertySource(properties = "app.security.jwt.secret=" + TestProperties.JWT_SECRET)
class ChatStreamControllerTest {

    private static final String ACCEPT = "text/event-stream, application/json";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ChatService chatService;
    @MockitoBean
    private AppUserRepository userRepository;

    private String token;
    private PreparedChat prepared;

    @BeforeEach
    void setUp() {
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "hash", "User", Role.USER);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        token = "Bearer " + jwtService.issue(user).token();
        RagPlan plan = RagPlan.llm("system", "user", List.of(),
                new RetrievalInfo("SEMANTIC", List.of("status = ACTIVE"), null, true));
        prepared = new PreparedChat(user.getId(), UUID.randomUUID(), true, "Which headphones?", Instant.now(), plan);
        when(chatService.prepare(any(), any())).thenReturn(prepared);
    }

    @Test
    void streamsMetaDeltasAndDoneThenStoresTheFullAnswer() throws Exception {
        when(chatService.stream(prepared)).thenReturn(Flux.just("Wireless ", "Headphones", " (SKU WH-001)"));
        when(chatService.save(eq(prepared), anyString())).thenAnswer(invocation -> new ChatAnswerResponse(
                prepared.conversationId(), UUID.randomUUID(), invocation.getArgument(1), List.of(),
                prepared.plan().retrieval(), Instant.now()));

        String body = streamBody("{\"question\":\"Which headphones?\"}");

        assertThat(body).containsSubsequence("event:meta", prepared.conversationId().toString(),
                "event:delta", "Wireless ", "event:delta", "Headphones", "event:done",
                "Wireless Headphones (SKU WH-001)");
        assertThat(body).doesNotContain("event:error");
        verify(chatService).save(prepared, "Wireless Headphones (SKU WH-001)");
    }

    @Test
    void providerFailureMidStreamSendsErrorEventAndStoresNothing() throws Exception {
        when(chatService.stream(prepared)).thenReturn(Flux.concat(Flux.just("Partial"),
                Flux.error(AiProviderException.unavailable(new RuntimeException("timeout")))));

        String body = streamBody("{\"question\":\"Which headphones?\"}");

        assertThat(body).containsSubsequence("event:meta", "event:delta", "event:error", "AI_PROVIDER_UNAVAILABLE");
        assertThat(body).doesNotContain("event:done");
        verify(chatService, never()).save(any(), anyString());
    }

    @Test
    void emptyModelOutputIsReportedAsError() throws Exception {
        when(chatService.stream(prepared)).thenReturn(Flux.just("  ", "\n"));

        assertThat(streamBody("{\"question\":\"Which headphones?\"}"))
                .contains("event:error").contains("AI_PROVIDER_EMPTY_RESPONSE");
        verify(chatService, never()).save(any(), anyString());
    }

    @Test
    void rejectionsBeforeStreamingAreRegularJsonErrors() throws Exception {
        when(chatService.prepare(any(), any()))
                .thenThrow(new NotFoundException("CONVERSATION_NOT_FOUND", "Conversation not found"));

        mockMvc.perform(post("/api/v1/chat/stream").header(HttpHeaders.AUTHORIZATION, token)
                        .header(HttpHeaders.ACCEPT, ACCEPT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hi\",\"conversationId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/chat/stream").header(HttpHeaders.AUTHORIZATION, token)
                        .header(HttpHeaders.ACCEPT, ACCEPT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anonymousStreamingIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/chat/stream").header(HttpHeaders.ACCEPT, ACCEPT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(chatService);
    }

    private String streamBody(String json) throws Exception {
        MvcResult started = mockMvc.perform(post("/api/v1/chat/stream").header(HttpHeaders.AUTHORIZATION, token)
                        .header(HttpHeaders.ACCEPT, ACCEPT).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
