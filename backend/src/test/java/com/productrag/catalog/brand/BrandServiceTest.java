package com.productrag.catalog.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.brand.dto.BrandOption;
import com.productrag.catalog.brand.dto.BrandRequest;
import com.productrag.catalog.brand.dto.BrandResponse;
import com.productrag.common.exception.ConflictException;
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
class BrandServiceTest {

    @Mock
    private BrandRepository brandRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private VectorSyncOutbox outbox;

    private final Map<UUID, Brand> store = new HashMap<>();
    private BrandService service;

    @BeforeEach
    void setUp() {
        service = new BrandService(brandRepository, productRepository, outbox);
        lenient().when(brandRepository.saveAndFlush(any(Brand.class))).thenAnswer(invocation -> {
            Brand brand = invocation.getArgument(0);
            if (brand.getId() == null) {
                brand.setId(UUID.randomUUID());
            }
            store.put(brand.getId(), brand);
            return brand;
        });
        lenient().when(brandRepository.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.<UUID>getArgument(0))));
    }

    @Test
    void createTrimsValuesDerivesSlugAndDefaultsToActive() {
        BrandResponse created = service.create(new BrandRequest("  H&M ", null, "  ", null,
                "https://www2.hm.com", null, null));

        assertThat(created.name()).isEqualTo("H&M");
        assertThat(created.slug()).isEqualTo("h-and-m");
        assertThat(created.description()).isNull();
        assertThat(created.websiteUrl()).isEqualTo("https://www2.hm.com");
        assertThat(created.status()).isEqualTo(CatalogStatus.ACTIVE);
    }

    @Test
    void duplicateNameIsAConflict() {
        when(brandRepository.existsByNameIgnoreCase("Apple")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("Apple", null)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("BRAND_ALREADY_EXISTS");
        verify(brandRepository, never()).saveAndFlush(any());
    }

    @Test
    void staleVersionIsRejected() {
        Brand apple = save(BrandTestData.brand("Apple", "apple", CatalogStatus.ACTIVE));

        assertThatThrownBy(() -> service.update(apple.getId(), request("Apple", 3L)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    void renamingUpdatesProductTextAndQueuesReembedding() {
        Brand apple = save(BrandTestData.brand("Apple", "apple", CatalogStatus.ACTIVE));
        Product iphone = ProductTestData.product("iPhone 15", "IP15", "79900.00", 4, ProductStatus.ACTIVE);
        when(productRepository.findAllByBrandId(apple.getId())).thenReturn(List.of(iphone));

        service.update(apple.getId(), new BrandRequest("Apple Inc", "apple", null, null, null, null, 0L));

        assertThat(iphone.getBrand()).isEqualTo("Apple Inc");
        verify(outbox).enqueue(iphone, VectorSyncEventType.PRODUCT_UPDATED);
    }

    @Test
    void deactivatedBrandDisappearsFromActiveOptionsOnly() {
        Brand apple = save(BrandTestData.brand("Apple", "apple", CatalogStatus.ACTIVE));

        assertThat(service.changeStatus(apple.getId(), CatalogStatus.INACTIVE).status())
                .isEqualTo(CatalogStatus.INACTIVE);
        verify(productRepository, never()).saveAllAndFlush(any());

        when(brandRepository.findAllByStatusOrderByNameAsc(CatalogStatus.ACTIVE)).thenReturn(List.of());
        when(brandRepository.findAllByOrderByNameAsc()).thenReturn(List.of(apple));
        assertThat(service.options(false)).isEmpty();
        assertThat(service.options(true)).extracting(BrandOption::status).containsExactly(CatalogStatus.INACTIVE);
    }

    @Test
    void brandUsedByProductsCannotBeDeleted() {
        Brand apple = save(BrandTestData.brand("Apple", "apple", CatalogStatus.ACTIVE));
        when(productRepository.existsByBrandId(apple.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.delete(apple.getId()))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("BRAND_IN_USE");
        verify(brandRepository, never()).delete(any(Brand.class));
    }

    private Brand save(Brand brand) {
        store.put(brand.getId(), brand);
        return brand;
    }

    private static BrandRequest request(String name, Long version) {
        return new BrandRequest(name, null, null, null, null, null, version);
    }
}
