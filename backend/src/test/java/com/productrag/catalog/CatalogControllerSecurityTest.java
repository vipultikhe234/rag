package com.productrag.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.productrag.auth.AppUser;
import com.productrag.auth.AppUserRepository;
import com.productrag.auth.JwtService;
import com.productrag.auth.RestSecurityHandlers;
import com.productrag.auth.Role;
import com.productrag.catalog.attribute.ProductAttributeController;
import com.productrag.catalog.attribute.ProductAttributeService;
import com.productrag.catalog.brand.BrandController;
import com.productrag.catalog.brand.BrandService;
import com.productrag.catalog.brand.dto.BrandRequest;
import com.productrag.catalog.category.CategoryController;
import com.productrag.catalog.category.CategoryService;
import com.productrag.catalog.category.dto.CategoryRequest;
import com.productrag.catalog.variant.ProductVariantController;
import com.productrag.catalog.variant.ProductVariantService;
import com.productrag.common.api.ApiErrorWriter;
import com.productrag.common.exception.ConflictException;
import com.productrag.config.AppProperties;
import com.productrag.config.SecurityConfig;
import com.productrag.support.TestProperties;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({CategoryController.class, BrandController.class, ProductAttributeController.class,
        ProductVariantController.class})
@EnableConfigurationProperties(AppProperties.class)
@Import({SecurityConfig.class, JwtService.class, RestSecurityHandlers.class, ApiErrorWriter.class})
@TestPropertySource(properties = "app.security.jwt.secret=" + TestProperties.JWT_SECRET)
class CatalogControllerSecurityTest {

    private static final String BRAND_BODY = """
            {"name":"Samsung","websiteUrl":"https://www.samsung.com"}
            """;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private CategoryService categoryService;
    @MockitoBean
    private BrandService brandService;
    @MockitoBean
    private ProductAttributeService attributeService;
    @MockitoBean
    private ProductVariantService variantService;
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
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/categories", "/api/v1/brands", "/api/v1/product-attributes",
            "/api/v1/product-variants"})
    void anonymousRequestsAreUnauthorized(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/categories", "/api/v1/brands", "/api/v1/product-attributes",
            "/api/v1/product-variants"})
    void authenticatedUsersCanRead(String path) throws Exception {
        mockMvc.perform(get(path).header("Authorization", userToken))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/categories", "/api/v1/brands", "/api/v1/product-attributes",
            "/api/v1/product-variants"})
    void nonAdminCannotWrite(String path) throws Exception {
        String id = "/" + UUID.randomUUID();
        mockMvc.perform(post(path).header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BRAND_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(patch(path + id).header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BRAND_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch(path + id + "/status").header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(categoryService, brandService, attributeService, variantService);
    }

    @Test
    void nonAdminCannotDeleteCategoriesOrBrands() throws Exception {
        mockMvc.perform(delete("/api/v1/categories/" + UUID.randomUUID()).header("Authorization", userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/brands/" + UUID.randomUUID()).header("Authorization", userToken))
                .andExpect(status().isForbidden());
        verifyNoInteractions(categoryService, brandService);
    }

    @Test
    void invalidBrandInputIsRejectedWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/brands").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"slug\":\"Bad Slug\",\"websiteUrl\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(3));
        verifyNoInteractions(brandService);
    }

    @Test
    void invalidAttributeAndVariantInputIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/product-attributes").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"RAM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/product-variants").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"bad sku\",\"variantName\":\"\",\"price\":-1,\"stockQuantity\":-2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(attributeService, variantService);
    }

    @Test
    void adminCanUpdateWithPatch() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(patch("/api/v1/brands/" + id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BRAND_BODY))
                .andExpect(status().isOk());
        verify(brandService).update(eq(id), any(BrandRequest.class));
    }

    @Test
    void statusChangeRequiresAValidStatus() throws Exception {
        mockMvc.perform(patch("/api/v1/categories/" + UUID.randomUUID() + "/status")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/categories/" + UUID.randomUUID() + "/status")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DELETED\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(categoryService);
    }

    @Test
    void duplicateCategoryIsAConflict() throws Exception {
        when(categoryService.create(any(CategoryRequest.class)))
                .thenThrow(new ConflictException("CATEGORY_ALREADY_EXISTS", "A category named 'Laptops' already exists"));

        mockMvc.perform(post("/api/v1/categories").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Laptops\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_ALREADY_EXISTS"));
    }

    @Test
    void unsupportedSortAndOversizedPagesAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/brands").param("sort", "logoUrl,asc").header("Authorization", userToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/categories").param("size", "1000").header("Authorization", userToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedIdIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/product-variants/not-a-uuid").header("Authorization", userToken))
                .andExpect(status().isBadRequest());
    }
}
