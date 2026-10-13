package com.productrag.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.productrag.catalog.attribute.AttributeValueValidator;
import com.productrag.catalog.category.Category;
import com.productrag.catalog.category.CategoryRepository;
import com.productrag.catalog.category.CategoryTestData;
import com.productrag.catalog.option.ProductOptionService;
import com.productrag.catalog.option.dto.ProductOptionRequest;
import com.productrag.catalog.variant.ProductVariantRepository;
import com.productrag.catalog.variant.ProductVariantService;
import com.productrag.catalog.variant.dto.VariantDraft;
import com.productrag.common.exception.BadRequestException;
import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.FieldValidationException;
import com.productrag.common.exception.NotFoundException;
import com.productrag.product.dto.ProductRequest;
import com.productrag.product.dto.ProductResponse;
import com.productrag.product.image.ProductImageRequest;
import com.productrag.product.image.ProductImageService;
import com.productrag.support.TestProperties;
import com.productrag.vectorsync.ProductSyncStatusService;
import com.productrag.vectorsync.VectorSyncEventType;
import com.productrag.vectorsync.VectorSyncOutbox;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductServiceTest {

    @Mock
    private ProductRepository repository;
    @Mock
    private VectorSyncOutbox outbox;
    @Mock
    private ProductSyncStatusService syncStatusService;
    @Mock
    private ProductCatalogLinks catalogLinks;
    @Mock
    private AttributeValueValidator attributeValueValidator;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ProductVariantRepository variantRepository;
    @Mock
    private ProductVariantService variantService;
    @Mock
    private ProductOptionService optionService;
    @Mock
    private ProductImageService imageService;
    @Mock
    private EntityManager entityManager;

    private ProductService service;
    private final Category electronics = CategoryTestData.active("Electronics", "electronics");

    @BeforeEach
    void setUp() {
        service = new ProductService(repository, outbox, syncStatusService, catalogLinks, attributeValueValidator,
                categoryRepository, variantRepository, variantService, optionService, imageService, entityManager,
                TestProperties.defaults());
        when(catalogLinks.category(any(), any())).thenReturn(electronics);
        when(attributeValueValidator.validate(any(), anyMap(), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(repository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            if (product.getId() == null) {
                ProductTestData.setId(product, UUID.randomUUID());
            }
            return product;
        });
        when(repository.findByIdForUpdate(any()))
                .thenAnswer(invocation -> repository.findById(invocation.getArgument(0)));
        when(syncStatusService.statusFor(anyCollection())).thenReturn(Map.of());
    }

    @Test
    void createSavesProductAndEnqueuesCreatedEvent() {
        when(repository.existsBySku("WH-001")).thenReturn(false);

        ProductResponse response = service.create(request("wh-001", null));

        assertThat(response.sku()).isEqualTo("WH-001");
        assertThat(response.slug()).isEqualTo("bluetooth-speaker");
        assertThat(response.hasVariants()).isFalse();
        assertThat(response.currency()).isEqualTo("INR");
        assertThat(response.status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(response.vectorSync().state()).isEqualTo("PENDING");
        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(repository).saveAndFlush(saved.capture());
        verify(outbox).enqueue(saved.getValue(), VectorSyncEventType.PRODUCT_CREATED);
        verify(variantService, never()).createAll(any(), any(), anyString());
    }

    @Test
    void derivedSlugsGetANumberWhenTaken() {
        when(repository.existsBySlug("bluetooth-speaker")).thenReturn(true);
        when(repository.existsBySlug("bluetooth-speaker-2")).thenReturn(true);

        ProductResponse response = service.create(request("SPK-2", null));

        assertThat(response.slug()).isEqualTo("bluetooth-speaker-3");
    }

    @Test
    void anExplicitSlugThatIsTakenIsAConflict() {
        when(repository.existsBySlug("speaker")).thenReturn(true);

        assertThatThrownBy(() -> service.create(withSlug(request("SPK-3", null), "speaker")))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.getCode()).isEqualTo("SLUG_ALREADY_EXISTS"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void renamingKeepsTheSlug() {
        Product existing = ProductTestData.headphones();
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        ProductResponse response = service.update(existing.getId(), request("WH-001", null));

        assertThat(response.name()).isEqualTo("Bluetooth Speaker");
        assertThat(response.slug()).isEqualTo("wireless-headphones");
    }

    @Test
    void simpleProductsNeedPriceAndStock() {
        ProductRequest noPrice = new ProductRequest("Mouse", "MS-2", "A mouse", "Electronics", null, null, null, 4,
                null, null, null, null, null);

        assertThatThrownBy(() -> service.create(noPrice))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getViolations().getFirst().field()).isEqualTo("price"));
    }

    @Test
    void variantProductsNeedOptionsAndAtLeastOneVariant() {
        ProductRequest noOptions = variantRequest(null, List.of(draft("TS-S")));
        assertThatThrownBy(() -> service.create(noOptions))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getViolations().getFirst().field()).isEqualTo("options"));

        ProductRequest noVariants = variantRequest(List.of(sizeOption()), List.of());
        assertThatThrownBy(() -> service.create(noVariants))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getViolations().getFirst().field()).isEqualTo("variants"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void variantProductIsCreatedWithImagesOptionsAndVariantsInOneTransaction() {
        List<ProductOptionRequest> options = List.of(sizeOption());
        List<VariantDraft> drafts = List.of(draft("TS-S"), draft("TS-M"));
        ProductRequest request = variantRequest(options, drafts);

        ProductResponse response = service.create(request);

        assertThat(response.hasVariants()).isTrue();
        InOrder order = inOrder(repository, imageService, optionService, variantService, entityManager, outbox);
        order.verify(repository).saveAndFlush(any(Product.class));
        order.verify(imageService).sync(response.id(), request.images());
        order.verify(optionService).sync(any(Product.class), eq(options));
        order.verify(variantService).createAll(any(Product.class), eq(drafts), eq("variants"));
        order.verify(repository).refreshVariantSummary(response.id());
        order.verify(entityManager).refresh(any(Product.class));
        order.verify(outbox).enqueue(any(Product.class), eq(VectorSyncEventType.PRODUCT_CREATED));
    }

    @Test
    void submittedPriceIsIgnoredForVariantProducts() {
        ProductRequest base = variantRequest(List.of(sizeOption()), List.of(draft("TS-S")));
        ProductRequest withPrice = new ProductRequest(base.name(), base.sku(), base.description(), null, null,
                new BigDecimal("5.00"), null, 999, null, null, null, base.categoryId(), null, null, true, null,
                base.options(), base.variants());

        service.create(withPrice);

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStockQuantity()).isZero();
        assertThat(saved.getValue().getPrice()).isEqualByComparingTo("0.00");
    }

    @Test
    void aProductWithVariantsCannotBecomeSimple() {
        Product existing = ProductTestData.headphones();
        existing.setHasVariants(true);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(variantRepository.countByProductId(existing.getId())).thenReturn(2L);
        ProductRequest simple = new ProductRequest("Wireless Headphones", "WH-001", "Desc", null, null,
                new BigDecimal("10.00"), null, 1, null, null, null, null, null, null, false, null, null, null);

        assertThatThrownBy(() -> service.update(existing.getId(), simple))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.getCode()).isEqualTo("PRODUCT_HAS_VARIANTS"));
        verify(outbox, never()).enqueue(any(), any());
    }

    @Test
    void invertedPriceRangeIsRejected() {
        ProductListQuery query = new ProductListQuery(null, null, null, null, null, Map.of(), new BigDecimal("10"),
                new BigDecimal("5"), null, null);

        assertThatThrownBy(() -> service.list(query, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void duplicateSkuIsRejectedWithoutSavingOrEnqueueing() {
        when(repository.existsBySku("WH-001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("WH-001", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("WH-001");
        verify(repository, never()).saveAndFlush(any());
        verify(outbox, never()).enqueue(any(), any());
    }

    @Test
    void updateAppliesChangesAndEnqueuesUpdatedEvent() {
        Product existing = ProductTestData.headphones();
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        ProductResponse response = service.update(existing.getId(), request("WH-001", null));

        assertThat(response.name()).isEqualTo("Bluetooth Speaker");
        assertThat(response.price()).isEqualByComparingTo("999.00");
        verify(outbox).enqueue(existing, VectorSyncEventType.PRODUCT_UPDATED);
        verify(outbox, never()).enqueue(existing, VectorSyncEventType.PRODUCT_DEACTIVATED);
    }

    @Test
    void updateWithStatusChangeEnqueuesStatusEventToo() {
        Product existing = ProductTestData.headphones();
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.update(existing.getId(), request("WH-001", ProductStatus.INACTIVE));

        InOrder order = inOrder(outbox);
        order.verify(outbox).enqueue(existing, VectorSyncEventType.PRODUCT_UPDATED);
        order.verify(outbox).enqueue(existing, VectorSyncEventType.PRODUCT_DEACTIVATED);
    }

    @Test
    void updateRejectsSkuOwnedByAnotherProduct() {
        Product existing = ProductTestData.headphones();
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.existsBySkuAndIdNot("SPK-1", existing.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.update(existing.getId(), request("SPK-1", null)))
                .isInstanceOf(ConflictException.class);
        verify(outbox, never()).enqueue(any(), any());
    }

    @Test
    void updateRejectsStaleVersion() {
        Product existing = ProductTestData.headphones();
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        ProductRequest stale = new ProductRequest("Name", "WH-001", "Desc", "Electronics", null,
                new BigDecimal("10.00"), "INR", 1, Map.of(), null, 99L, null, null);

        assertThatThrownBy(() -> service.update(existing.getId(), stale))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    void deactivationEnqueuesDeactivatedEvent() {
        Product existing = ProductTestData.headphones();
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        ProductResponse response = service.changeStatus(existing.getId(), ProductStatus.INACTIVE);

        assertThat(response.status()).isEqualTo(ProductStatus.INACTIVE);
        verify(outbox).enqueue(existing, VectorSyncEventType.PRODUCT_DEACTIVATED);
    }

    @Test
    void reactivationEnqueuesReactivatedEvent() {
        Product existing = ProductTestData.product("Desk Lamp", "DL-1", "799.00", 3, ProductStatus.INACTIVE);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.changeStatus(existing.getId(), ProductStatus.ACTIVE);

        verify(outbox).enqueue(existing, VectorSyncEventType.PRODUCT_REACTIVATED);
    }

    @Test
    void unchangedStatusDoesNotEnqueue() {
        Product existing = ProductTestData.headphones();
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.changeStatus(existing.getId(), ProductStatus.ACTIVE);

        verify(outbox, never()).enqueue(any(), any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void deleteEnqueuesDeletedEventBeforeRemovingTheRow() {
        Product existing = ProductTestData.headphones();
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.delete(existing.getId());

        InOrder order = inOrder(outbox, repository);
        order.verify(outbox).enqueue(existing, VectorSyncEventType.PRODUCT_DELETED);
        order.verify(repository).delete(existing);
        order.verify(repository).flush();
    }

    @Test
    void missingProductReturnsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
        verify(outbox, never()).enqueue(any(), any());
    }

    @Test
    void currencyDefaultsAndIsUpperCased() {
        when(repository.existsBySku(anyString())).thenReturn(false);
        ProductRequest usd = new ProductRequest("Mouse", "MS-1", "A mouse", "Electronics", null,
                new BigDecimal("15.5"), "usd", 4, null, null, null, null, null);

        ProductResponse response = service.create(usd);

        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.price()).isEqualByComparingTo("15.50");
        assertThat(response.attributes()).isEmpty();
        verify(outbox).enqueue(any(Product.class), eq(VectorSyncEventType.PRODUCT_CREATED));
    }

    @Test
    void categoryNameAndIdComeFromTheResolvedCategory() {
        ProductResponse response = service.create(request("SPK-9", null));

        assertThat(response.categoryId()).isEqualTo(electronics.getId());
        assertThat(response.category()).isEqualTo("Electronics");
        assertThat(response.brandId()).isNull();
    }

    @Test
    void skuUsedByAVariantIsRejected() {
        when(variantRepository.existsBySku("GS24-256-BLK")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("gs24-256-blk", null)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("SKU_ALREADY_EXISTS");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void productWithVariantsCannotBeDeleted() {
        Product existing = ProductTestData.headphones();
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(variantRepository.existsByProductId(existing.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.delete(existing.getId()))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("PRODUCT_HAS_VARIANTS");
        verify(repository, never()).delete(any(Product.class));
        verify(outbox, never()).enqueue(any(), any());
    }

    private static ProductRequest request(String sku, ProductStatus status) {
        return new ProductRequest("Bluetooth Speaker", sku, "Portable speaker with deep bass.", "Electronics",
                "ExampleBrand", new BigDecimal("999.00"), null, 25, Map.of("battery_life", "12 hours"), status, null,
                null, null);
    }

    private static ProductRequest withSlug(ProductRequest r, String slug) {
        return new ProductRequest(r.name(), r.sku(), r.description(), r.category(), r.brand(), r.price(),
                r.currency(), r.stockQuantity(), r.attributes(), r.status(), r.version(), r.categoryId(),
                r.brandId(), slug, r.hasVariants(), r.images(), r.options(), r.variants());
    }

    private ProductRequest variantRequest(List<ProductOptionRequest> options, List<VariantDraft> variants) {
        return new ProductRequest("Cotton T-Shirt", "TS", "A soft cotton t-shirt.", null, null, null, null, null,
                null, null, null, electronics.getId(), null, null, true,
                List.of(new ProductImageRequest(null, "https://example.com/ts.jpg", "Front")), options, variants);
    }

    private static ProductOptionRequest sizeOption() {
        return new ProductOptionRequest(null, "Size", null,
                List.of(new ProductOptionRequest.Value(null, "S"), new ProductOptionRequest.Value(null, "M")));
    }

    private static VariantDraft draft(String sku) {
        return new VariantDraft(sku, Map.of("Size", sku.substring(sku.length() - 1)), null, new BigDecimal("499"),
                null, 5, null, null, null, null);
    }
}
