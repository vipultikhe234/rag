package com.productrag.embedding;

import com.productrag.product.Product;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds the text that is embedded for a product.
 *
 * <p>Fields that affect the embedding (semantic fields): name, SKU, category, brand, description and
 * attributes. Fields deliberately excluded: price, currency, stock quantity, status, timestamps and
 * ids. Price and stock change often and are always read live from PostgreSQL when answering, and
 * status is enforced by the retrieval query, so changing them never triggers a new embedding call.
 */
@Component
public class EmbeddingContentBuilder {

    static final int MAX_DESCRIPTION_CHARS = 6000;
    static final int MAX_ATTRIBUTE_VALUE_CHARS = 300;

    public String build(Product product) {
        StringBuilder text = new StringBuilder(512);
        line(text, "Product", product.getName());
        line(text, "SKU", product.getSku());
        line(text, "Category", product.getCategory());
        line(text, "Brand", product.getBrand());
        line(text, "Description", truncate(normalize(product.getDescription()), MAX_DESCRIPTION_CHARS));
        String attributes = formatAttributes(product.getAttributes());
        line(text, "Attributes", attributes);
        return text.toString().trim();
    }

    public String hash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    static String formatAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return "";
        }
        // Sorted by key so the text (and therefore the hash) is deterministic.
        return new TreeMap<>(attributes).entrySet().stream()
                .map(entry -> humanize(entry.getKey()) + ": " + truncate(formatValue(entry.getValue()),
                        MAX_ATTRIBUTE_VALUE_CHARS))
                .filter(entry -> !entry.endsWith(": "))
                .collect(Collectors.joining("; "));
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).map(EmbeddingContentBuilder::normalize)
                    .collect(Collectors.joining(", "));
        }
        if (value instanceof Boolean flag) {
            return flag ? "yes" : "no";
        }
        return normalize(String.valueOf(value));
    }

    private static String humanize(String key) {
        return normalize(key.replace('_', ' '));
    }

    private static void line(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append(label).append(": ").append(normalize(value)).append('\n');
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
