package com.productrag.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.brand.Brand;
import com.productrag.catalog.brand.BrandRepository;
import com.productrag.catalog.brand.BrandTestData;
import com.productrag.catalog.category.Category;
import com.productrag.catalog.category.CategoryAssignability;
import com.productrag.catalog.category.CategoryRepository;
import com.productrag.catalog.category.CategoryTestData;
import com.productrag.common.exception.FieldValidationException;
import com.productrag.product.dto.ProductRequest;
import java.math.BigDecimal;
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
class ProductCatalogLinksTest {

    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private BrandRepository brandRepository;

    private ProductCatalogLinks links;

    @BeforeEach
    void setUp() {
        links = new ProductCatalogLinks(categoryRepository, brandRepository,
                new CategoryAssignability(categoryRepository));
    }

    @Test
    void activeCategoryUnderAnInactiveParentCannotBeNewlyAssigned() {
        Category electronics = CategoryTestData.category("Electronics", "electronics", null, CatalogStatus.INACTIVE);
        Category phones = CategoryTestData.category("Mobile Phones", "mobile-phones", electronics.getId(),
                CatalogStatus.ACTIVE);
        when(categoryRepository.findById(phones.getId())).thenReturn(Optional.of(phones));
        when(categoryRepository.findSelfAndAncestorIds(phones.getId()))
                .thenReturn(List.of(phones.getId(), electronics.getId()));
        when(categoryRepository.findAllById(List.of(phones.getId(), electronics.getId())))
                .thenReturn(List.of(phones, electronics));

        assertThatThrownBy(() -> links.category(request(null, phones.getId(), null, null), null))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getViolations().getFirst().message())
                                .contains("under inactive category 'Electronics'"));
        assertThat(links.category(request(null, phones.getId(), null, null), phones.getId())).isSameAs(phones);
    }

    @Test
    void categoryIsResolvedByIdOrByLegacyName() {
        Category laptops = CategoryTestData.active("Laptops", "laptops");
        when(categoryRepository.findById(laptops.getId())).thenReturn(Optional.of(laptops));
        when(categoryRepository.findByNameIgnoreCase("laptops")).thenReturn(Optional.of(laptops));

        assertThat(links.category(request(null, laptops.getId(), null, null), null)).isSameAs(laptops);
        assertThat(links.category(request(" laptops ", null, null, null), null)).isSameAs(laptops);
    }

    @Test
    void missingOrUnknownCategoryIsAFieldError() {
        when(categoryRepository.findByNameIgnoreCase("Gadgets")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> links.category(request(null, null, null, null), null))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> links.category(request("Gadgets", null, null, null), null))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getViolations().getFirst().field())
                        .isEqualTo("category"));
    }

    @Test
    void inactiveCategoryCannotBeNewlyAssignedButCanBeKept() {
        Category retired = CategoryTestData.category("Pet Supplies", "pet-supplies", null, CatalogStatus.INACTIVE);
        when(categoryRepository.findById(retired.getId())).thenReturn(Optional.of(retired));
        ProductRequest request = request(null, retired.getId(), null, null);

        assertThatThrownBy(() -> links.category(request, UUID.randomUUID()))
                .isInstanceOf(FieldValidationException.class);
        assertThat(links.category(request, retired.getId())).isSameAs(retired);
    }

    @Test
    void brandIsOptionalAndInactiveBrandsCannotBeNewlyAssigned() {
        Brand retired = BrandTestData.brand("Reebok", "reebok", CatalogStatus.INACTIVE);
        when(brandRepository.findById(retired.getId())).thenReturn(Optional.of(retired));

        assertThat(links.brand(request("Laptops", null, null, null), null)).isNull();
        assertThatThrownBy(() -> links.brand(request("Laptops", null, null, retired.getId()), null))
                .isInstanceOf(FieldValidationException.class);
        assertThat(links.brand(request("Laptops", null, null, retired.getId()), retired.getId())).isSameAs(retired);
    }

    private static ProductRequest request(String category, UUID categoryId, String brand, UUID brandId) {
        return new ProductRequest("Laptop", "LAP-1", "A laptop", category, brand, new BigDecimal("50000"), "INR", 1,
                Map.of(), null, null, categoryId, brandId);
    }
}
