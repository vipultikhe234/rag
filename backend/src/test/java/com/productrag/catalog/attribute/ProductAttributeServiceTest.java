package com.productrag.catalog.attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.attribute.dto.ProductAttributeRequest;
import com.productrag.catalog.attribute.dto.ProductAttributeResponse;
import com.productrag.catalog.category.Category;
import com.productrag.catalog.category.CategoryRepository;
import com.productrag.catalog.category.CategoryTestData;
import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.FieldValidationException;
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

    private final Map<UUID, ProductAttribute> store = new HashMap<>();
    private ProductAttributeService service;
    private final Category electronics = CategoryTestData.active("Electronics", "electronics");
    private Category phones;

    @BeforeEach
    void setUp() {
        service = new ProductAttributeService(attributeRepository, categoryRepository);
        phones = CategoryTestData.category("Mobile Phones", "mobile-phones", electronics.getId(), CatalogStatus.ACTIVE);
        lenient().when(categoryRepository.findById(electronics.getId())).thenReturn(Optional.of(electronics));
        lenient().when(categoryRepository.findById(phones.getId())).thenReturn(Optional.of(phones));
        lenient().when(categoryRepository.findSelfAndAncestorIds(phones.getId()))
                .thenReturn(List.of(phones.getId(), electronics.getId()));
        lenient().when(categoryRepository.findSelfAndDescendantIds(phones.getId())).thenReturn(List.of(phones.getId()));
        lenient().when(categoryRepository.findAllById(any())).thenReturn(List.of(electronics, phones));
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
        ProductAttributeResponse created = service.create(request(phones.getId(), "Operating System",
                AttributeDataType.SELECT, List.of(" Android ", "iOS"), null));

        assertThat(created.slug()).isEqualTo("operating-system");
        assertThat(created.options()).containsExactly("Android", "iOS");
        assertThat(created.status()).isEqualTo(CatalogStatus.ACTIVE);
        assertThat(created.categoryName()).isEqualTo("Mobile Phones");
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
    void slugAlreadyDefinedOnAnAncestorIsAConflict() {
        ProductAttribute inherited = AttributeTestData.attribute(electronics.getId(), "Warranty Period",
                "warranty-period", AttributeDataType.NUMBER, List.of(), false, CatalogStatus.ACTIVE);
        when(attributeRepository.findAllByCategoryIdIn(anyCollection())).thenReturn(List.of(inherited));

        assertThatThrownBy(() -> service.create(request(phones.getId(), "Warranty Period",
                AttributeDataType.NUMBER, null, "months")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Electronics");
        verify(attributeRepository, never()).saveAndFlush(any());
    }

    @Test
    void attributesCannotBeAddedToAnInactiveCategory() {
        Category retired = CategoryTestData.category("Retired", "retired", null, CatalogStatus.INACTIVE);
        when(categoryRepository.findById(retired.getId())).thenReturn(Optional.of(retired));

        assertThatThrownBy(() -> service.create(request(retired.getId(), "Colour", AttributeDataType.STRING, null,
                null)))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void applicableAttributesIncludeInheritedOnesRootFirst() {
        ProductAttribute warranty = AttributeTestData.attribute(electronics.getId(), "Warranty Period",
                "warranty-period", AttributeDataType.NUMBER, List.of(), false, CatalogStatus.ACTIVE);
        ProductAttribute ram = AttributeTestData.attribute(phones.getId(), "RAM", "ram", AttributeDataType.NUMBER,
                List.of(), true, CatalogStatus.ACTIVE);
        when(attributeRepository.findAllByCategoryIdInAndStatus(anyCollection(), any()))
                .thenReturn(List.of(ram, warranty));

        List<ProductAttribute> applicable = service.applicableDefinitions(phones.getId(), false);

        assertThat(applicable).extracting(ProductAttribute::getSlug).containsExactly("warranty-period", "ram");
    }

    @Test
    void deactivationKeepsTheDefinition() {
        ProductAttributeResponse created = service.create(request(phones.getId(), "RAM", AttributeDataType.NUMBER,
                null, "GB"));

        ProductAttributeResponse inactive = service.changeStatus(created.id(), CatalogStatus.INACTIVE);

        assertThat(inactive.status()).isEqualTo(CatalogStatus.INACTIVE);
        assertThat(inactive.slug()).isEqualTo("ram");
        verify(attributeRepository, never()).delete(any(ProductAttribute.class));
    }

    private static ProductAttributeRequest request(UUID categoryId, String name, AttributeDataType type,
                                                   List<String> options, String unit) {
        return new ProductAttributeRequest(categoryId, name, null, type, options, unit, false, true, null, null);
    }
}
