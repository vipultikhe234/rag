package com.productrag.catalog.attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.attribute.dto.ProductAttributeRequest;
import com.productrag.catalog.attribute.dto.ProductAttributeResponse;
import com.productrag.catalog.category.Category;
import com.productrag.catalog.category.CategoryAssignability;
import com.productrag.catalog.category.CategoryRepository;
import com.productrag.catalog.category.CategoryTestData;
import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.FieldValidationException;
import com.productrag.vectorsync.VectorSyncOutbox;
import java.util.Collection;
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
class ProductAttributeServiceTest {

    @Mock
    private ProductAttributeRepository attributeRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private VectorSyncOutbox outbox;

    private final Map<UUID, ProductAttribute> store = new HashMap<>();
    private final Map<UUID, Category> categories = new HashMap<>();
    private ProductAttributeService service;
    private final Category electronics = CategoryTestData.active("Electronics", "electronics");
    private Category phones;
    private Category laptops;

    @BeforeEach
    void setUp() {
        service = new ProductAttributeService(attributeRepository, categoryRepository,
                new CategoryAssignability(categoryRepository), outbox);
        phones = CategoryTestData.category("Mobile Phones", "mobile-phones", electronics.getId(), CatalogStatus.ACTIVE);
        laptops = CategoryTestData.category("Laptops", "laptops", electronics.getId(), CatalogStatus.ACTIVE);
        List.of(electronics, phones, laptops).forEach(c -> categories.put(c.getId(), c));
        lenient().when(categoryRepository.findSelfAndAncestorIds(any())).thenAnswer(invocation -> {
            List<UUID> chain = new java.util.ArrayList<>();
            Category current = categories.get(invocation.<UUID>getArgument(0));
            while (current != null) {
                chain.add(current.getId());
                current = current.getParentId() == null ? null : categories.get(current.getParentId());
            }
            return chain;
        });
        lenient().when(categoryRepository.findAllById(anyIterable())).thenAnswer(invocation -> {
            Iterable<UUID> ids = invocation.getArgument(0);
            List<Category> found = new java.util.ArrayList<>();
            ids.forEach(id -> Optional.ofNullable(categories.get(id)).ifPresent(found::add));
            return found;
        });
        lenient().when(attributeRepository.saveAndFlush(any(ProductAttribute.class))).thenAnswer(invocation -> {
            ProductAttribute attribute = invocation.getArgument(0);
            if (attribute.getId() == null) {
                attribute.setId(UUID.randomUUID());
            }
            store.put(attribute.getId(), attribute);
            return attribute;
        });
        lenient().when(attributeRepository.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.<UUID>getArgument(0))));
    }

    @Test
    void selectAttributeIsCreatedWithTrimmedOptions() {
        ProductAttributeResponse created = service.create(request(List.of(phones.getId()), "Operating System",
                AttributeDataType.SELECT, List.of(" Android ", "iOS"), null));

        assertThat(created.slug()).isEqualTo("operating-system");
        assertThat(created.options()).containsExactly("Android", "iOS");
        assertThat(created.status()).isEqualTo(CatalogStatus.ACTIVE);
        assertThat(created.categories()).extracting(ProductAttributeResponse.CategoryRef::name)
                .containsExactly("Mobile Phones");
    }

    @Test
    void oneAttributeCanBeAssignedToSeveralCategories() {
        ProductAttributeResponse created = service.create(request(List.of(phones.getId(), laptops.getId()), "RAM",
                AttributeDataType.NUMBER, null, "GB"));

        assertThat(created.categories()).extracting(ProductAttributeResponse.CategoryRef::name)
                .containsExactly("Laptops", "Mobile Phones");
        assertThat(store.get(created.id()).getCategoryIds()).containsExactlyInAnyOrder(phones.getId(),
                laptops.getId());
    }

    @Test
    void atLeastOneCategoryIsRequired() {
        assertThatThrownBy(() -> service.create(request(List.of(), "RAM", AttributeDataType.NUMBER, null, "GB")))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getViolations().getFirst().field()).isEqualTo("categoryIds"));
    }

    @Test
    void selectWithoutOptionsIsRejected() {
        assertThatThrownBy(() -> ProductAttributeService.normalizeOptions(AttributeDataType.SELECT, List.of(" ")))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void duplicateOptionsAreRejectedIgnoringCase() {
        assertThatThrownBy(() -> ProductAttributeService.normalizeOptions(AttributeDataType.SELECT,
                List.of("Black", "black")))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void nonSelectTypesCannotHaveOptions() {
        assertThatThrownBy(() -> ProductAttributeService.normalizeOptions(AttributeDataType.NUMBER, List.of("8")))
                .isInstanceOf(FieldValidationException.class);
        assertThat(ProductAttributeService.normalizeOptions(AttributeDataType.BOOLEAN, null)).isEmpty();
    }

    @Test
    void attributeNamesAreUniqueAcrossAllCategories() {
        when(attributeRepository.existsByNameIgnoreCase("Warranty Period")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request(List.of(laptops.getId()), "Warranty Period",
                AttributeDataType.NUMBER, null, "months")))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.getCode()).isEqualTo("ATTRIBUTE_ALREADY_EXISTS"));
        verify(attributeRepository, never()).saveAndFlush(any());
    }

    @Test
    void attributeSlugsAreUniqueAcrossAllCategories() {
        when(attributeRepository.existsBySlug("warranty-period")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request(List.of(phones.getId()), "Warranty Period",
                AttributeDataType.NUMBER, null, "months")))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.getCode()).isEqualTo("SLUG_ALREADY_EXISTS"))
                .hasMessageContaining("warranty-period");
    }

    @Test
    void attributesCannotBeAddedToAnInactiveCategory() {
        Category retired = CategoryTestData.category("Retired", "retired", null, CatalogStatus.INACTIVE);
        categories.put(retired.getId(), retired);

        assertThatThrownBy(() -> service.create(request(List.of(retired.getId()), "Colour", AttributeDataType.STRING,
                null, null)))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getViolations().getFirst().message()).contains("'Retired' is inactive"));
    }

    @Test
    void attributesCannotBeAddedUnderAnInactiveParent() {
        Category archive = CategoryTestData.category("Archive", "archive", null, CatalogStatus.INACTIVE);
        Category oldPhones = CategoryTestData.category("Old Phones", "old-phones", archive.getId(),
                CatalogStatus.ACTIVE);
        categories.put(archive.getId(), archive);
        categories.put(oldPhones.getId(), oldPhones);

        assertThatThrownBy(() -> service.create(request(List.of(oldPhones.getId()), "Colour",
                AttributeDataType.STRING, null, null)))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getViolations().getFirst().message())
                                .contains("under inactive category 'Archive'"));
    }

    @Test
    void anExistingAssignmentMayStayAfterTheCategoryIsDeactivated() {
        ProductAttributeResponse created = service.create(request(List.of(phones.getId()), "RAM",
                AttributeDataType.NUMBER, null, "GB"));
        phones.setStatus(CatalogStatus.INACTIVE);

        ProductAttributeResponse updated = service.update(created.id(), new ProductAttributeRequest(
                List.of(phones.getId()), null, "RAM", null, AttributeDataType.NUMBER, null, "GB", true, true, null,
                created.version()));

        assertThat(updated.required()).isTrue();
        assertThat(updated.categories()).extracting(ProductAttributeResponse.CategoryRef::active).containsExactly(false);
    }

    @Test
    void onlyNameSlugTypeOrUnitChangesReembedProductsThatUseTheAttribute() {
        ProductAttributeResponse created = service.create(request(List.of(phones.getId()), "RAM",
                AttributeDataType.NUMBER, null, "GB"));

        service.update(created.id(), new ProductAttributeRequest(List.of(phones.getId()), null, "RAM", null,
                AttributeDataType.NUMBER, null, "GB", true, false, null, null));
        verify(outbox, never()).enqueueForAttribute(any(), any());

        service.update(created.id(), new ProductAttributeRequest(List.of(phones.getId()), null, "Memory", null,
                AttributeDataType.NUMBER, null, "GB", true, false, null, null));
        verify(outbox).enqueueForAttribute(created.id(), "ram");
    }

    @Test
    void applicableAttributesIncludeInheritedOnesRootFirst() {
        ProductAttribute warranty = AttributeTestData.attribute(electronics.getId(), "Warranty Period",
                "warranty-period", AttributeDataType.NUMBER, List.of(), false, CatalogStatus.ACTIVE);
        ProductAttribute ram = AttributeTestData.attribute(phones.getId(), "RAM", "ram", AttributeDataType.NUMBER,
                List.of(), true, CatalogStatus.ACTIVE);
        when(attributeRepository.findAllAssignedToAnyWithStatus(anyCollection(), any()))
                .thenReturn(List.of(ram, warranty));

        List<ProductAttribute> applicable = service.applicableDefinitions(phones.getId(), false);

        assertThat(applicable).extracting(ProductAttribute::getSlug).containsExactly("warranty-period", "ram");
    }

    @Test
    void deactivationKeepsTheDefinition() {
        ProductAttributeResponse created = service.create(request(List.of(phones.getId()), "RAM",
                AttributeDataType.NUMBER, null, "GB"));

        ProductAttributeResponse inactive = service.changeStatus(created.id(), CatalogStatus.INACTIVE);

        assertThat(inactive.status()).isEqualTo(CatalogStatus.INACTIVE);
        assertThat(inactive.slug()).isEqualTo("ram");
        verify(attributeRepository, never()).delete(any(ProductAttribute.class));
    }

    private static ProductAttributeRequest request(Collection<UUID> categoryIds, String name, AttributeDataType type,
                                                   List<String> options, String unit) {
        return new ProductAttributeRequest(List.copyOf(categoryIds), null, name, null, type, options, unit, false,
                true, null, null);
    }
}
