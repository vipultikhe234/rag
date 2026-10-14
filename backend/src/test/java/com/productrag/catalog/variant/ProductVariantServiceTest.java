package com.productrag.catalog.variant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.attribute.AttributeValueValidator;
import com.productrag.catalog.category.CategoryRepository;
import com.productrag.catalog.option.ProductOptionService;
import com.productrag.catalog.option.ProductOptionService.Combination;
import com.productrag.catalog.option.dto.SelectedOption;
import com.productrag.catalog.variant.dto.ProductVariantRequest;
import com.productrag.catalog.variant.dto.ProductVariantResponse;
import com.productrag.catalog.variant.dto.VariantDraft;
import com.productrag.common.exception.BadRequestException;
import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.FieldValidationException;
import com.productrag.product.Product;
import com.productrag.product.ProductRepository;
import com.productrag.product.ProductStatus;
import com.productrag.product.ProductTestData;
import com.productrag.product.image.ProductImageService;
import com.productrag.support.TestProperties;
import com.productrag.vectorsync.VectorSyncEventType;
import com.productrag.vectorsync.VectorSyncOutbox;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
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
    private CategoryRepository categoryRepository;
    @Mock
    private AttributeValueValidator attributeValueValidator;
    @Mock
    private ProductOptionService optionService;
    @Mock
    private ProductImageService imageService;
    @Mock
    private VectorSyncOutbox outbox;

    private final Map<UUID, ProductVariant> store = new HashMap<>();
    private ProductVariantService service;
    private Product phone;

    @BeforeEach
    void setUp() {
        service = new ProductVariantService(variantRepository, productRepository, categoryRepository,
                attributeValueValidator, optionService, imageService, outbox, TestProperties.defaults());
        phone = ProductTestData.product("Samsung Galaxy S24", "GS24", "74999.00", 10, ProductStatus.ACTIVE);
        phone.setCategoryId(UUID.randomUUID());
        phone.setHasVariants(true);
        lenient().when(productRepository.findByIdForUpdate(phone.getId())).thenReturn(Optional.of(phone));
        lenient().when(attributeValueValidator.validate(any(), anyMap(), anyMap(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(optionService.resolve(eq(phone.getId()), any(), anyString()))
                .thenAnswer(invocation -> combination(invocation.getArgument(1)));
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
    void createNormalizesSkuNamesTheVariantFromItsOptionsAndRefreshesTheSummary() {
        ProductVariantResponse created = service.create(request(" gs24-256-blk ", Map.of("Color", "Black",
                "Storage", "256"), "74999", "79999", 3));

        assertThat(created.sku()).isEqualTo("GS24-256-BLK");
        assertThat(created.variantName()).isEqualTo("Black / 256");
        assertThat(created.compareAtPrice()).isEqualByComparingTo("79999.00");
        assertThat(created.onSale()).isTrue();
        assertThat(created.status()).isEqualTo(CatalogStatus.ACTIVE);
        assertThat(created.stockLevel()).isEqualTo(StockLevel.LOW_STOCK);
        assertThat(created.productName()).isEqualTo("Samsung Galaxy S24");
        verify(optionService).assign(eq(created.id()), eq(phone.getId()), any(Combination.class));
        verify(productRepository).refreshVariantSummary(phone.getId());
    }

    @Test
    void compareAtPriceMustBeHigherThanThePrice() {
        assertThatThrownBy(() -> service.create(request("GS24-1", Map.of("Color", "Black"), "100.00", "100.00", 1)))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getViolations().getFirst().field()).isEqualTo("compareAtPrice"));
        verify(variantRepository, never()).saveAndFlush(any());
    }

    @Test
    void skuUsedByAProductOrAnotherVariantIsAConflict() {
        when(productRepository.existsBySku("GS24")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("gs24", Map.of("Color", "Black"), "100.00", null, 1)))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.getCode()).isEqualTo("SKU_ALREADY_EXISTS"));
    }

    @Test
    void anExistingOptionCombinationIsAConflict() {
        when(variantRepository.existsByProductIdAndOptionSignature(eq(phone.getId()), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("GS24-2", Map.of("Color", "Black"), "100.00", null, 1)))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("VARIANT_COMBINATION_EXISTS");
                    assertThat(e.getMessage()).contains("Black");
                });
        verify(variantRepository, never()).saveAndFlush(any());
    }

    @Test
    void barcodeUsedByAnotherVariantIsAConflict() {
        when(variantRepository.existsByBarcode("8801643")).thenReturn(true);
        ProductVariantRequest request = new ProductVariantRequest(phone.getId(), "GS24-3", Map.of("Color", "Black"),
                null, new BigDecimal("100"), null, 1, "8801643", null, null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.getCode()).isEqualTo("BARCODE_ALREADY_EXISTS"));
    }

    @Test
    void simpleProductsDoNotAcceptVariants() {
        phone.setHasVariants(false);

        assertThatThrownBy(() -> service.create(request("GS24-4", Map.of("Color", "Black"), "100.00", null, 1)))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getViolations().getFirst().field()).isEqualTo("productId"));
    }

    @Test
    void variantsCanBePreparedWhileTheProductIsInactive() {
        phone.setStatus(ProductStatus.INACTIVE);

        ProductVariantResponse created = service.create(request("GS24-5", Map.of("Color", "Black"), "100.00", null,
                1));

        assertThat(created.productStatus()).isEqualTo(ProductStatus.INACTIVE);
    }

    @Test
    void theImageMustBelongToTheProduct() {
        ProductVariantRequest request = new ProductVariantRequest(phone.getId(), "GS24-6", Map.of("Color", "Black"),
                null, new BigDecimal("100"), null, 1, null, UUID.randomUUID(), null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getViolations().getFirst().field()).isEqualTo("imageId"));
    }

    @Test
    void batchCreateReportsDuplicateSkusAndCombinationsPerRow() {
        List<VariantDraft> sameSku = List.of(draft("GS24-A", "Black"), draft("gs24-a", "White"));
        assertThatThrownBy(() -> service.createForProduct(phone.getId(), sameSku))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getViolations().getFirst().field()).isEqualTo("variants[1].sku"));

        List<VariantDraft> sameOptions = List.of(draft("GS24-B", "Black"), draft("GS24-C", "black"));
        assertThatThrownBy(() -> service.createForProduct(phone.getId(), sameOptions))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getViolations().getFirst().field()).isEqualTo("variants[1].options"));
    }

    @Test
    void aProductHasAtMostOneHundredVariants() {
        when(variantRepository.countByProductId(phone.getId())).thenReturn(100L);

        assertThatThrownBy(() -> service.createForProduct(phone.getId(), List.of(draft("GS24-D", "Black"))))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getViolations().getFirst().message()).contains("at most 100"));
    }

    @Test
    void variantOptionsAreTypeCheckedWithoutRequiringEveryAttribute() {
        service.create(new ProductVariantRequest(phone.getId(), "GS24-7", Map.of("Color", "Black"),
                Map.of("warranty-period", "12"), new BigDecimal("80000"), null, 0, null, null, null, null));

        verify(attributeValueValidator).validate(phone.getCategoryId(), Map.of("warranty-period", "12"), Map.of(),
                false);
    }

    @Test
    void zeroStockIsOutOfStockAndDeactivationKeepsTheVariant() {
        ProductVariantResponse created = service.create(request("GS24-8", Map.of("Color", "Black"), "100.00", null,
                0));
        assertThat(created.stockLevel()).isEqualTo(StockLevel.OUT_OF_STOCK);

        ProductVariantResponse inactive = service.changeStatus(created.id(), CatalogStatus.INACTIVE);

        assertThat(inactive.status()).isEqualTo(CatalogStatus.INACTIVE);
        assertThat(store).containsKey(created.id());
        verify(variantRepository, never()).delete(any(ProductVariant.class));
        verify(productRepository, org.mockito.Mockito.times(2)).refreshVariantSummary(phone.getId());
    }

    @Test
    void aVariantCannotMoveToAnotherProduct() {
        ProductVariantResponse created = service.create(request("GS24-9", Map.of("Color", "Black"), "100.00", null,
                1));

        assertThatThrownBy(() -> service.update(created.id(), new ProductVariantRequest(UUID.randomUUID(), "GS24-9",
                Map.of("Color", "Black"), null, new BigDecimal("100.00"), null, 1, null, null, null, null)))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getViolations().getFirst().field()).isEqualTo("productId"));
    }

    @Test
    void onlyChangesVisibleInSearchReEmbedTheProduct() {
        ProductVariantResponse created = service.create(request("GS24-11", Map.of("Color", "Black"), "100.00", null,
                1));
        verify(outbox).enqueue(phone, VectorSyncEventType.PRODUCT_UPDATED);

        service.update(created.id(), request("GS24-11", Map.of("Color", "Black"), "90.00", null, 0));
        verify(outbox, org.mockito.Mockito.times(1)).enqueue(phone, VectorSyncEventType.PRODUCT_UPDATED);

        service.update(created.id(), request("GS24-11", Map.of("Color", "White"), "90.00", null, 0));
        verify(outbox, org.mockito.Mockito.times(2)).enqueue(phone, VectorSyncEventType.PRODUCT_UPDATED);

        service.changeStatus(created.id(), CatalogStatus.INACTIVE);
        verify(outbox, org.mockito.Mockito.times(3)).enqueue(phone, VectorSyncEventType.PRODUCT_UPDATED);
    }

    @Test
    void staleVersionIsRejected() {
        ProductVariantResponse created = service.create(request("GS24-10", Map.of("Color", "Black"), "100.00", null,
                1));

        assertThatThrownBy(() -> service.update(created.id(), new ProductVariantRequest(phone.getId(), "GS24-10",
                Map.of("Color", "Black"), null, new BigDecimal("100.00"), null, 1, null, null, null, 5L)))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.getCode()).isEqualTo("CONCURRENT_MODIFICATION"));
    }

    @Test
    void invalidListFiltersAreRejected() {
        assertThatThrownBy(() -> service.list(new VariantListQuery(null, null, null, null, new BigDecimal("10"),
                new BigDecimal("5"), null, null, null, null), null))
                .isInstanceOf(BadRequestException.class);
        Map<String, String> tooMany = Map.of("a", "1", "b", "2", "c", "3", "d", "4", "e", "5", "f", "6");
        assertThatThrownBy(() -> service.list(new VariantListQuery(null, null, null, null, null, null, null, null,
                null, tooMany), null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void optionFilterParametersMustBeNameValuePairs() {
        assertThat(ProductVariantController.optionFilters(List.of("Color:Black", "Storage Capacity: 256")))
                .containsEntry("Color", "Black").containsEntry("Storage Capacity", "256");
        assertThatThrownBy(() -> ProductVariantController.optionFilters(List.of("Black")))
                .isInstanceOf(BadRequestException.class);
    }

    private ProductVariantRequest request(String sku, Map<String, String> options, String price, String compareAt,
                                          int stock) {
        return new ProductVariantRequest(phone.getId(), sku, options, Map.of(), new BigDecimal(price),
                compareAt == null ? null : new BigDecimal(compareAt), stock, null, null, null, null);
    }

    private static VariantDraft draft(String sku, String color) {
        return new VariantDraft(sku, Map.of("Color", color), null, new BigDecimal("100"), null, 1, null, null, null,
                null);
    }

    /** Deterministic stand-in for option resolution: value ids derive from the (case-insensitive) value text. */
    private static Combination combination(Map<String, String> options) {
        List<SelectedOption> selections = new ArrayList<>();
        new TreeMap<>(options).forEach((name, value) -> {
            UUID id = UUID.nameUUIDFromBytes((name + "=" + value.toLowerCase()).getBytes(StandardCharsets.UTF_8));
            selections.add(new SelectedOption(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name, id,
                    value, value));
        });
        return new Combination(selections,
                ProductOptionService.signature(selections.stream().map(SelectedOption::valueId).toList()),
                ProductOptionService.title(selections));
    }
}
