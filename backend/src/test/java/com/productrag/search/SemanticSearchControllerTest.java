package com.productrag.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.productrag.auth.AppUser;
import com.productrag.auth.AppUserRepository;
import com.productrag.auth.JwtService;
import com.productrag.auth.RestSecurityHandlers;
import com.productrag.auth.Role;
import com.productrag.common.api.ApiErrorWriter;
import com.productrag.config.AppProperties;
import com.productrag.config.SecurityConfig;
import com.productrag.search.dto.SemanticSearchResponse;
import com.productrag.support.TestProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SemanticSearchController.class)
@EnableConfigurationProperties(AppProperties.class)
@Import({SecurityConfig.class, JwtService.class, RestSecurityHandlers.class, ApiErrorWriter.class})
@TestPropertySource(properties = "app.security.jwt.secret=" + TestProperties.JWT_SECRET)
class SemanticSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private SemanticProductSearchService searchService;
    @MockitoBean
    private AppUserRepository userRepository;

    private String userToken;

    @BeforeEach
    void setUp() {
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "hash", "User", Role.USER);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        userToken = "Bearer " + jwtService.issue(user).token();
        when(searchService.search(any())).thenReturn(new SemanticSearchResponse("phone", "HYBRID", "test-model",
                List.of("status = ACTIVE"), List.of(), null));
    }

    @Test
    void anonymousSearchIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/products/semantic-search").param("q", "phone"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(searchService);
    }

    @Test
    void parametersArePassedToTheService() throws Exception {
        UUID categoryId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/products/semantic-search").header("Authorization", userToken)
                        .param("q", "black phone").param("categoryId", categoryId.toString())
                        .param("maxPrice", "80000").param("inStock", "true")
                        .param("option", "Color:Black").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("HYBRID"))
                .andExpect(jsonPath("$.results").isArray());

        ArgumentCaptor<SemanticSearchQuery> query = ArgumentCaptor.forClass(SemanticSearchQuery.class);
        verify(searchService).search(query.capture());
        assertThat(query.getValue()).isEqualTo(new SemanticSearchQuery("black phone", categoryId, null, null,
                new BigDecimal("80000"), true, Map.of("Color", "Black"), 5));
    }

    @Test
    void malformedOptionFiltersAndMissingQueryAreBadRequests() throws Exception {
        mockMvc.perform(get("/api/v1/products/semantic-search").header("Authorization", userToken)
                        .param("q", "phone").param("option", "Black"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/products/semantic-search").header("Authorization", userToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/products/semantic-search").header("Authorization", userToken)
                        .param("q", "phone").param("minPrice", "-1"))
                .andExpect(status().isBadRequest());
    }
}
