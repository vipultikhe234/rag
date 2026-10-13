package com.productrag.catalog.variant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.attribute.AttributeValueValidator;
import com.productrag.catalog.variant.dto.ProductVariantRequest;
import com.productrag.catalog.variant.dto.ProductVariantResponse;
import com.productrag.common.exception.BadRequestException;
import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.FieldValidationException;
import com.productrag.product.Product;
import com.productrag.product.ProductRepository;
import com.productrag.product.ProductStatus;
import com.productrag.product.ProductTestData;
import com.productrag.support.TestProperties;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductVariantServiceTest {

    @Mock
    private ProductVariantRepository variantRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private AttributeValueValidator attributeValueValidator;

    private final Map<UUID, ProductVariant> store = new HashMap<>();
    private ProductVariantService service;
    private Product phone;

    @BeforeEach
    void setUp() {
        service = new ProductVariantService(variantRepository, productRepository, attributeValueValidator,
                TestProperties.defaults());
        phone = ProductTestData.product("Samsung Galaxy S24", "GS24", "74999.00", 10, ProductStatus.ACTIVE);
        phone.setCategoryId(UUID.randomUUID());
        lenient().when(productRepository.findById(phone.getId())).thenReturn(Optional.of(phone));
        lenient().when(attributeValueValidator.validate(any(), anyMap(), anyMap(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(variantRepository.saveAndFlush(any(ProductVariant.class))).thenAnswer(invocation -> {
            ProductVariant variant = invocation.getArgument(0);
            if (variant.getId() == null) {
                variant.setId(UUID.randomUUID());
            }
            store.put(variant.getId(), variant);
            return variant;
        });
        lenient().when(variantRepository.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.<UUID>getArgument(0))));
    }

    @Test
    void createNormalizesSkuAndComputesEffectivePrice() {
        ProductVariantResponse created = service.create(request(" gs24-256-blk ", "74999", "69999", 3));

        assertThat(created.sku()).isEqualTo("GS24-256-BLK");
        assertThat(created.effectivePrice()).isEqualByComparingTo("69999.00");
        assertThat(created.status()).isEqualTo(CatalogStatus.ACTIVE);
        assertThat(created.stockLevel()).isEqualTo(StockLevel.LOW_STOCK);
        assertThat(created.productName()).isEqualTo("Samsung Galaxy S24");
    }

    @Test
    void salePriceMustBeLowerThanThePrice() {
        assertThatThrownBy(() -> service.create(request("GS24-1", "100.00", "100.00", 1)))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getViolations().getFirst().field())
                        .isEqualTo("salePrice"));
        verify(variantRepository, never()).saveAndFlush(any());
    }

    @Test
    void skuUsedByAProductOrAnotherVariantIsAConflict() {
        when(productRepository.existsBySku("GS24")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("gs24", "100.00", null, 1)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("SKU_ALREADY_EXISTS");
    }

    @Test
    void variantsCannotBeAddedToAnInactiveProduct() {
        phone.setStatus(ProductStatus.INACTIVE);

        assertThatThrownBy(() -> service.create(request("GS24-1", "100.00", null, 1)))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getViolations().getFirst().field())
                        .isEqualTo("productId"));
    }

    @Test
    void variantOptionsAreTypeCheckedWithoutRequiringEveryAttribute() {
        service.create(new ProductVariantRequest(phone.getId(), "GS24-2", "256 GB", Map.of("storage-capacity", "256"),
                new BigDecimal("80000"), null, 0, null, null));

        verify(attributeValueValidator).validate(phone.getCategoryId(), Map.of("storage-capacity", "256"), Map.of(),
                false);
    }

    @Test
    void zeroStockIsOutOfStockAndDeactivationKeepsTheVariant() {
        ProductVariantResponse created = service.create(request("GS24-3", "100.00", null, 0));
        assertThat(created.stockLevel()).isEqualTo(StockLevel.OUT_OF_STOCK);

        ProductVariantResponse inactive = service.changeStatus(created.id(), CatalogStatus.INACTIVE);

        assertThat(inactive.status()).isEqualTo(CatalogStatus.INACTIVE);
        assertThat(store).containsKey(created.id());
    }

    @Test
    void staleVersionIsRejected() {
        ProductVariantResponse created = service.create(request("GS24-4", "100.00", null, 1));

        assertThatThrownBy(() -> service.update(created.id(), new ProductVariantRequest(phone.getId(), "GS24-4",
                "Renamed", Map.of(), new BigDecimal("100.00"), null, 1, null, 5L)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    void invertedPriceRangeIsRejected() {
        assertThatThrownBy(() -> service.list(null, null, null, null, new BigDecimal("10"), new BigDecimal("5"),
                null, null))
                .isInstanceOf(BadRequestException.class);
    }

    private ProductVariantRequest request(String sku, String price, String salePrice, int stock) {
        return new ProductVariantRequest(phone.getId(), sku, "Variant " + sku, Map.of(), new BigDecimal(price),
                salePrice == null ? null : new BigDecimal(salePrice), stock, null, null);
    }
}
