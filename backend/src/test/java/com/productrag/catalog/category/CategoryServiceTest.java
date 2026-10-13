package com.productrag.catalog.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.attribute.ProductAttributeRepository;
import com.productrag.catalog.category.dto.CategoryOption;
import com.productrag.catalog.category.dto.CategoryRequest;
import com.productrag.catalog.category.dto.CategoryResponse;
import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.FieldValidationException;
import com.productrag.product.Product;
import com.productrag.product.ProductRepository;
import com.productrag.product.ProductStatus;
import com.productrag.product.ProductTestData;
import com.productrag.vectorsync.VectorSyncEventType;
import com.productrag.vectorsync.VectorSyncOutbox;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductAttributeRepository attributeRepository;
    @Mock
    private VectorSyncOutbox outbox;

    private final Map<UUID, Category> store = new HashMap<>();
    private CategoryService service;

    @BeforeEach
    void setUp() {
        service = new CategoryService(categoryRepository, productRepository, attributeRepository, outbox);
        lenient().when(categoryRepository.saveAndFlush(any(Category.class))).thenAnswer(invocation -> {
            Category category = invocation.getArgument(0);
            if (category.getId() == null) {
                category.setId(UUID.randomUUID());
            }
            store.put(category.getId(), category);
            return category;
        });
        lenient().when(categoryRepository.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.<UUID>getArgument(0))));
    }

    @Test
    void createDerivesTheSlugAndDefaultsToActive() {
        CategoryResponse created = service.create(request("Men's Clothing", null, null, null));

        assertThat(created.slug()).isEqualTo("mens-clothing");
        assertThat(created.status()).isEqualTo(CatalogStatus.ACTIVE);
        assertThat(created.parentId()).isNull();
    }

    @Test
    void duplicateNameIsAConflict() {
        when(categoryRepository.existsByNameIgnoreCase("Laptops")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("Laptops", null, null, null)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("CATEGORY_ALREADY_EXISTS");
        verify(categoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateSlugIsAConflict() {
        when(categoryRepository.existsBySlug("laptops")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("Notebooks", "laptops", null, null)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("SLUG_ALREADY_EXISTS");
    }

    @Test
    void unknownParentIsAFieldError() {
        assertThatThrownBy(() -> service.create(request("Laptops", null, UUID.randomUUID(), null)))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(fields(e)).containsExactly("parentId"));
    }

    @Test
    void inactiveParentCannotBeChosenForANewCategory() {
        Category retired = save(CategoryTestData.category("Retired", "retired", null, CatalogStatus.INACTIVE));

        assertThatThrownBy(() -> service.create(request("Child", null, retired.getId(), null)))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getViolations().getFirst().message())
                        .contains("Inactive"));
    }

    @Test
    void categoryCannotBeItsOwnParent() {
        Category laptops = save(CategoryTestData.active("Laptops", "laptops"));

        assertThatThrownBy(() -> service.update(laptops.getId(), request("Laptops", null, laptops.getId(), 0L)))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(fields(e)).containsExactly("parentId"));
    }

    @Test
    void movingACategoryUnderItsOwnDescendantIsRejected() {
        Category electronics = save(CategoryTestData.active("Electronics", "electronics"));
        Category phones = save(CategoryTestData.category("Mobile Phones", "mobile-phones", electronics.getId(),
                CatalogStatus.ACTIVE));
        when(categoryRepository.findSelfAndAncestorIds(phones.getId()))
                .thenReturn(List.of(phones.getId(), electronics.getId()));

        assertThatThrownBy(() -> service.update(electronics.getId(),
                request("Electronics", null, phones.getId(), 0L)))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getViolations().getFirst().message())
                        .contains("cycle"));
    }

    @Test
    void staleVersionIsRejected() {
        Category laptops = save(CategoryTestData.active("Laptops", "laptops"));

        assertThatThrownBy(() -> service.update(laptops.getId(), request("Laptops", null, null, 7L)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    void renamingUpdatesProductTextAndQueuesReembedding() {
        Category laptops = save(CategoryTestData.active("Laptops", "laptops"));
        Product product = ProductTestData.product("Dell Inspiron", "DELL-1", "1000.00", 3, ProductStatus.ACTIVE);
        when(productRepository.findAllByCategoryId(laptops.getId())).thenReturn(List.of(product));

        service.update(laptops.getId(), request("Notebooks", null, null, 0L));

        assertThat(product.getCategory()).isEqualTo("Notebooks");
        verify(outbox).enqueue(product, VectorSyncEventType.PRODUCT_UPDATED);
    }

    @Test
    void deactivationKeepsReferencesAndDoesNotTouchProducts() {
        Category laptops = save(CategoryTestData.active("Laptops", "laptops"));

        CategoryResponse response = service.changeStatus(laptops.getId(), CatalogStatus.INACTIVE);

        assertThat(response.status()).isEqualTo(CatalogStatus.INACTIVE);
        verify(productRepository, never()).saveAllAndFlush(any());
        verify(outbox, never()).enqueue(any(), any());
    }

    @Test
    void categoryUsedByProductsCannotBeDeleted() {
        Category laptops = save(CategoryTestData.active("Laptops", "laptops"));
        when(productRepository.existsByCategoryId(laptops.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.delete(laptops.getId()))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("CATEGORY_IN_USE");
        verify(categoryRepository, never()).delete(any(Category.class));
    }

    @Test
    void categoryWithSubcategoriesCannotBeDeleted() {
        Category electronics = save(CategoryTestData.active("Electronics", "electronics"));
        when(categoryRepository.existsByParentId(electronics.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.delete(electronics.getId()))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("CATEGORY_HAS_SUBCATEGORIES");
    }

    @Test
    void unusedCategoryCanBeDeleted() {
        Category spare = save(CategoryTestData.active("Spare", "spare"));

        service.delete(spare.getId());

        verify(categoryRepository).delete(spare);
    }

    @Test
    void optionsShowTheFullPathAndHideInactiveByDefault() {
        Category electronics = CategoryTestData.active("Electronics", "electronics");
        Category phones = CategoryTestData.category("Mobile Phones", "mobile-phones", electronics.getId(),
                CatalogStatus.ACTIVE);
        Category retired = CategoryTestData.category("Retired", "retired", null, CatalogStatus.INACTIVE);
        when(categoryRepository.findAllByOrderByNameAsc()).thenReturn(List.of(electronics, phones, retired));

        List<CategoryOption> active = service.options(false);
        List<CategoryOption> all = service.options(true);

        assertThat(active).extracting(CategoryOption::path).containsExactly("Electronics", "Electronics / Mobile Phones");
        assertThat(all).hasSize(3);
    }

    private Category save(Category category) {
        store.put(category.getId(), category);
        return category;
    }

    private static CategoryRequest request(String name, String slug, UUID parentId, Long version) {
        return new CategoryRequest(name, slug, null, null, parentId, null, version);
    }

    private static List<String> fields(Throwable e) {
        return ((FieldValidationException) e).getViolations().stream().map(v -> v.field()).toList();
    }
}
