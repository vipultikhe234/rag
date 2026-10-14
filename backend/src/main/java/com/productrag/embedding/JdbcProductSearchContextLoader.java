package com.productrag.embedding;

import com.productrag.embedding.ProductSearchContext.AttributeLabel;
import com.productrag.embedding.ProductSearchContext.OptionSummary;
import com.productrag.product.Product;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Three queries per batch: the category tree (a small table, read whole), the attribute definitions for the
 * products' attribute keys, and the option values used by ACTIVE variants of the variant products.
 */
@Repository
public class JdbcProductSearchContextLoader implements ProductSearchContextLoader {

    private static final int MAX_CATEGORY_DEPTH = 20;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcProductSearchContextLoader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, ProductSearchContext> load(Collection<Product> products) {
        Map<UUID, ProductSearchContext> result = new HashMap<>();
        if (products.isEmpty()) {
            return result;
        }
        Map<UUID, CategoryNode> categories = products.stream().anyMatch(p -> p.getCategoryId() != null)
                ? categories() : Map.of();
        Map<String, AttributeLabel> labels = attributeLabels(products);
        Map<UUID, List<OptionSummary>> options = options(products.stream()
                .filter(Product::hasVariants).map(Product::getId).toList());
        for (Product product : products) {
            Map<String, AttributeLabel> own = new HashMap<>();
            if (product.getAttributes() != null) {
                product.getAttributes().keySet().forEach(key -> {
                    AttributeLabel label = labels.get(key);
                    if (label != null) {
                        own.put(key, label);
                    }
                });
            }
            result.put(product.getId(), new ProductSearchContext(path(product.getCategoryId(), categories), own,
                    options.getOrDefault(product.getId(), List.of())));
        }
        return result;
    }

    private Map<UUID, CategoryNode> categories() {
        Map<UUID, CategoryNode> nodes = new HashMap<>();
        jdbc.getJdbcTemplate().query("SELECT id, name, parent_id FROM categories", rs -> {
            UUID id = rs.getObject("id", UUID.class);
            nodes.put(id, new CategoryNode(rs.getString("name"), rs.getObject("parent_id", UUID.class)));
        });
        return nodes;
    }

    private static List<String> path(UUID categoryId, Map<UUID, CategoryNode> categories) {
        List<String> names = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        UUID current = categoryId;
        while (current != null && seen.add(current) && names.size() < MAX_CATEGORY_DEPTH) {
            CategoryNode node = categories.get(current);
            if (node == null) {
                break;
            }
            names.addFirst(node.name());
            current = node.parentId();
        }
        return names;
    }

    private Map<String, AttributeLabel> attributeLabels(Collection<Product> products) {
        Set<String> slugs = new HashSet<>();
        products.stream().map(Product::getAttributes).filter(Objects::nonNull).forEach(a -> slugs.addAll(a.keySet()));
        Map<String, AttributeLabel> labels = new HashMap<>();
        if (slugs.isEmpty()) {
            return labels;
        }
        jdbc.query("SELECT slug, name, data_type, unit FROM product_attributes WHERE slug IN (:slugs)",
                new MapSqlParameterSource("slugs", slugs), rs -> {
                    labels.put(rs.getString("slug"), new AttributeLabel(rs.getString("name"),
                            rs.getString("data_type"), rs.getString("unit")));
                });
        return labels;
    }

    private Map<UUID, List<OptionSummary>> options(List<UUID> productIds) {
        Map<UUID, List<OptionSummary>> result = new HashMap<>();
        if (productIds.isEmpty()) {
            return result;
        }
        Map<UUID, Map<String, List<String>>> byProduct = new LinkedHashMap<>();
        jdbc.query("""
                        SELECT o.product_id, o.name, a.data_type, a.unit, v.value
                        FROM product_options o
                        JOIN product_option_values v ON v.option_id = o.id
                        LEFT JOIN product_attributes a ON a.id = o.attribute_id
                        WHERE o.product_id IN (:ids)
                          AND EXISTS (SELECT 1 FROM variant_option_values vov
                                      JOIN product_variants pv ON pv.id = vov.variant_id
                                      WHERE vov.option_value_id = v.id AND pv.status = 'ACTIVE')
                        ORDER BY o.product_id, o.position, o.name, v.position, v.value
                        """,
                new MapSqlParameterSource("ids", productIds), rs -> {
                    String value = rs.getString("value");
                    String unit = rs.getString("unit");
                    if ("NUMBER".equals(rs.getString("data_type")) && unit != null && value.matches("\\d+(\\.\\d+)?")) {
                        value = value + " " + unit;
                    }
                    byProduct.computeIfAbsent(rs.getObject("product_id", UUID.class), k -> new LinkedHashMap<>())
                            .computeIfAbsent(rs.getString("name"), k -> new ArrayList<>())
                            .add(value);
                });
        byProduct.forEach((productId, options) -> result.put(productId, options.entrySet().stream()
                .map(e -> new OptionSummary(e.getKey(), e.getValue())).toList()));
        return result;
    }

    private record CategoryNode(String name, UUID parentId) {
    }
}
