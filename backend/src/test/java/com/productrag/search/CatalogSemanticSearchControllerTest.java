package com.productrag.search;

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
import com.productrag.embedding.CatalogEntityType;
import com.productrag.search.dto.CatalogSemanticSearchResponse;
import com.productrag.support.TestProperties;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CatalogSemanticSearchController.class)
@EnableConfigurationProperties(AppProperties.class)
@Import({SecurityConfig.class, JwtService.class, RestSecurityHandlers.class, ApiErrorWriter.class})
@TestPropertySource(properties = "app.security.jwt.secret=" + TestProperties.JWT_SECRET)
class CatalogSemanticSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private CatalogSemanticSearchService searchService;
    @MockitoBean
    private AppUserRepository userRepository;

    private String userToken;

    @BeforeEach
    void setUp() {
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "hash", "User", Role.USER);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        userToken = "Bearer " + jwtService.issue(user).token();
    }

    @Test
    void anonymousSearchIsUnauthorized() throws Exception {
        for (String module : List.of("brands", "categories", "product-attributes", "product-variants")) {
            mockMvc.perform(get("/api/v1/" + module + "/semantic-search").param("q", "laptop"))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(searchService);
    }

    @Test
    void eachModuleRoutesToItsSearch() throws Exception {
        when(searchService.brands("gaming", 5)).thenReturn(empty(CatalogEntityType.BRAND));
        when(searchService.categories("running gear", 10)).thenReturn(empty(CatalogEntityType.CATEGORY));
        when(searchService.attributes("screen", 10)).thenReturn(empty(CatalogEntityType.ATTRIBUTE));
        when(searchService.variants("black 256", 10)).thenReturn(empty(CatalogEntityType.VARIANT));

        mockMvc.perform(get("/api/v1/brands/semantic-search").header("Authorization", userToken)
                        .param("q", "gaming").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityType").value("BRAND"))
                .andExpect(jsonPath("$.results").isArray());
        mockMvc.perform(get("/api/v1/categories/semantic-search").header("Authorization", userToken)
                        .param("q", "running gear"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entityType").value("CATEGORY"));
        mockMvc.perform(get("/api/v1/product-attributes/semantic-search").header("Authorization", userToken)
                        .param("q", "screen"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entityType").value("ATTRIBUTE"));
        mockMvc.perform(get("/api/v1/product-variants/semantic-search").header("Authorization", userToken)
                        .param("q", "black 256"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entityType").value("VARIANT"));

        verify(searchService).brands("gaming", 5);
        verify(searchService).variants("black 256", 10);
    }

    @Test
    void aMissingQueryIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/brands/semantic-search").header("Authorization", userToken))
                .andExpect(status().isBadRequest());
    }

    private static <T> CatalogSemanticSearchResponse<T> empty(CatalogEntityType type) {
        return new CatalogSemanticSearchResponse<>("q", type, "HYBRID", "test-model", List.of(), null);
    }
}
