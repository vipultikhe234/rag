package com.productrag.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.productrag.auth.AppUser;
import com.productrag.auth.AppUserRepository;
import com.productrag.auth.JwtService;
import com.productrag.auth.RestSecurityHandlers;
import com.productrag.auth.Role;
import com.productrag.common.api.ApiErrorWriter;
import com.productrag.common.api.PageResponse;
import com.productrag.common.exception.ConflictException;
import com.productrag.config.AppProperties;
import com.productrag.config.SecurityConfig;
import com.productrag.product.dto.ProductRequest;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@EnableConfigurationProperties(AppProperties.class)
@Import({SecurityConfig.class, JwtService.class, RestSecurityHandlers.class, ApiErrorWriter.class})
@TestPropertySource(properties = "app.security.jwt.secret=" + TestProperties.JWT_SECRET)
class ProductControllerSecurityTest {

    private static final String VALID_BODY = """
            {"name":"Wireless Headphones","sku":"WH-001","description":"Over-ear headphones",
             "category":"Electronics","price":2499.00,"stockQuantity":12,
             "attributes":{"battery_life":"30 hours"}}
            """;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ProductService productService;
    @MockitoBean
    private AppUserRepository userRepository;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        AppUser admin = new AppUser(UUID.randomUUID(), "admin@example.com", "hash", "Admin", Role.ADMIN);
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "hash", "User", Role.USER);
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        adminToken = "Bearer " + jwtService.issue(admin).token();
        userToken = "Bearer " + jwtService.issue(user).token();
        when(productService.list(any(), any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true, true));
    }

    @Test
    void anonymousRequestsAreUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void invalidTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/products").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUsersCanReadProducts() throws Exception {
        mockMvc.perform(get("/api/v1/products").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void nonAdminCannotCreateOrDelete() throws Exception {
        mockMvc.perform(post("/api/v1/products").header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(delete("/api/v1/products/" + UUID.randomUUID()).header("Authorization", userToken))
                .andExpect(status().isForbidden());
        verifyNoInteractions(productService);
    }

    @Test
    void adminCreateValidatesInput() throws Exception {
        mockMvc.perform(post("/api/v1/products").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"sku\":\"bad sku\",\"price\":-5,\"stockQuantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void duplicateSkuReturnsConflict() throws Exception {
        when(productService.create(any(ProductRequest.class)))
                .thenThrow(new ConflictException("SKU_ALREADY_EXISTS", "A product with SKU 'WH-001' already exists"));

        mockMvc.perform(post("/api/v1/products").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_ALREADY_EXISTS"));
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/products").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unsupportedSortIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("sort", "password,asc").header("Authorization", adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminEndpointsRequireAdminRole() throws Exception {
        mockMvc.perform(get("/api/v1/admin/vector-sync/status").header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }
}
