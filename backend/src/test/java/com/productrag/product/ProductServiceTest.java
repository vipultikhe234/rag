package com.productrag.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.NotFoundException;
import com.productrag.product.dto.ProductRequest;
import com.productrag.product.dto.ProductResponse;
import com.productrag.support.TestProperties;
import com.productrag.vectorsync.ProductSyncStatusService;
import com.productrag.vectorsync.VectorSyncEventType;
import com.productrag.vectorsync.VectorSyncOutbox;
import java.math.BigDecimal;
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

    private ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(repository, outbox, syncStatusService, TestProperties.defaults());
        when(repository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            if (product.getId() == null) {
                ProductTestData.setId(product, UUID.randomUUID());
            }
            return product;
        });
        when(syncStatusService.statusFor(anyCollection())).thenReturn(Map.of());
    }

    @Test
    void createSavesProductAndEnqueuesCreatedEvent() {
        when(repository.existsBySku("WH-001")).thenReturn(false);

        ProductResponse response = service.create(request("wh-001", null));

        assertThat(response.sku()).isEqualTo("WH-001");
        assertThat(response.currency()).isEqualTo("INR");
        assertThat(response.status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(response.vectorSync().state()).isEqualTo("PENDING");
        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(repository).saveAndFlush(saved.capture());
        verify(outbox).enqueue(saved.getValue(), VectorSyncEventType.PRODUCT_CREATED);
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
                new BigDecimal("10.00"), "INR", 1, Map.of(), null, 99L);

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
        when(syncStatusService.statusFor(existing.getId())).thenReturn(null);

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
                new BigDecimal("15.5"), "usd", 4, null, null, null);

        ProductResponse response = service.create(usd);

        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.price()).isEqualByComparingTo("15.50");
        assertThat(response.attributes()).isEmpty();
        verify(outbox).enqueue(any(Product.class), eq(VectorSyncEventType.PRODUCT_CREATED));
    }

    private static ProductRequest request(String sku, ProductStatus status) {
        return new ProductRequest("Bluetooth Speaker", sku, "Portable speaker with deep bass.", "Electronics",
                "ExampleBrand", new BigDecimal("999.00"), null, 25, Map.of("battery_life", "12 hours"), status, null);
    }
}
