package com.productrag.product.image;

import static com.productrag.catalog.CatalogText.trimToNull;

import com.productrag.common.exception.FieldValidationException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Product image lists. Removing an image clears it from variants that used it (ON DELETE SET NULL). */
@Service
public class ProductImageService {

    public static final int MAX_IMAGES = 20;

    private final ProductImageRepository imageRepository;

    public ProductImageService(ProductImageRepository imageRepository) {
        this.imageRepository = imageRepository;
    }

    public Map<UUID, List<ProductImageResponse>> imagesByProduct(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return imageRepository.findAllByProductIdInOrderByPosition(productIds).stream()
                .collect(Collectors.groupingBy(ProductImage::getProductId, LinkedHashMap::new,
                        Collectors.mapping(ProductImageResponse::from, Collectors.toList())));
    }

    public List<ProductImage> images(UUID productId) {
        return imageRepository.findAllByProductIdOrderByPosition(productId);
    }

    public Map<UUID, ProductImageResponse> byIds(Collection<UUID> imageIds) {
        if (imageIds.isEmpty()) {
            return Map.of();
        }
        return imageRepository.findAllById(imageIds).stream()
                .collect(Collectors.toMap(ProductImage::getId, ProductImageResponse::from));
    }

    /** Replaces the product's images with the requested list (in display order). Null keeps the current images. */
    @Transactional
    public void sync(UUID productId, List<ProductImageRequest> requests) {
        if (requests == null) {
            return;
        }
        if (requests.size() > MAX_IMAGES) {
            throw FieldValidationException.of("images", "A product can have at most " + MAX_IMAGES + " images");
        }
        List<ProductImage> current = imageRepository.findAllByProductIdOrderByPosition(productId);
        Set<String> urls = new HashSet<>();
        Set<UUID> kept = new HashSet<>();
        List<ProductImage> matched = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            ProductImageRequest request = requests.get(i);
            String url = request.url().trim();
            if (!urls.add(url.toLowerCase(Locale.ROOT))) {
                throw FieldValidationException.of("images[" + i + "].url", "This image is listed twice");
            }
            ProductImage image;
            if (request.id() != null) {
                image = current.stream().filter(img -> img.getId().equals(request.id())).findFirst()
                        .orElseThrow(() -> FieldValidationException.of("images[" + i + "]",
                                "Image does not belong to this product"));
            } else {
                image = current.stream().filter(img -> img.getUrl().equals(url)).findFirst().orElse(null);
            }
            if (image != null) {
                kept.add(image.getId());
            }
            matched.add(image);
        }
        List<ProductImage> removed = current.stream().filter(img -> !kept.contains(img.getId())).toList();
        imageRepository.deleteAll(removed);
        imageRepository.flush();
        List<ProductImage> toSave = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            ProductImage image = matched.get(i);
            if (image == null) {
                image = new ProductImage();
                image.setProductId(productId);
            }
            image.setUrl(requests.get(i).url().trim());
            image.setAltText(trimToNull(requests.get(i).altText()));
            image.setPosition(i);
            toSave.add(image);
        }
        imageRepository.saveAllAndFlush(toSave);
    }
}
