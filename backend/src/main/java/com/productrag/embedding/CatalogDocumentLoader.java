package com.productrag.embedding;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Builds the search documents embedded for brands, categories, attributes and variants, a fixed number of queries per
 * batch. Every list is sorted and capped, so the same data always gives the same text (and hash).
 *
 * <ul>
 *   <li>Brand: name, description, categories and names of its ACTIVE products.</li>
 *   <li>Category: name, path, description, subcategories, brands and names of ACTIVE products in its subtree.</li>
 *   <li>Attribute: name, data type, unit, allowed options, assigned categories.</li>
 *   <li>Variant: title, SKU, product name, brand, category and option values.</li>
 * </ul>
 * Prices, stock, statuses, ids and timestamps are never part of a document; they are read live when searching.
 */
@Repository
public class CatalogDocumentLoader {

    static final int MAX_LISTED = 20;
    static final int MAX_DESCRIPTION_CHARS = 2000;
    private static final int MAX_CATEGORY_DEPTH = 20;

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogDocumentLoader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Documents of the entities that exist; ids of deleted entities are absent from the result. */
    public Map<UUID, String> load(CatalogEntityType type, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return switch (type) {
            case BRAND -> brands(ids);
            case CATEGORY -> categories(ids);
            case ATTRIBUTE -> attributes(ids);
            case VARIANT -> variants(ids);
        };
    }

    private Map<UUID, String> brands(Collection<UUID> ids) {
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        Map<UUID, String[]> rows = new LinkedHashMap<>();
        jdbc.query("SELECT id, name, description FROM brands WHERE id IN (:ids)", params, rs -> {
            rows.put(rs.getObject("id", UUID.class), new String[] {rs.getString("name"), rs.getString("description")});
        });
        Map<UUID, Set<String>> categories = new HashMap<>();
        jdbc.query("""
                        SELECT DISTINCT p.brand_id, coalesce(c.name, p.category) AS category
                        FROM products p LEFT JOIN categories c ON c.id = p.category_id
                        WHERE p.brand_id IN (:ids) AND p.status = 'ACTIVE'
                        """, params,
                rs -> {
                    categories.computeIfAbsent(rs.getObject("brand_id", UUID.class), k -> sortedSet())
                            .add(rs.getString("category"));
                });
        Map<UUID, List<String>> products = new HashMap<>();
        jdbc.query("""
                        SELECT brand_id, name FROM (
                            SELECT p.brand_id, p.name,
                                   row_number() OVER (PARTITION BY p.brand_id ORDER BY lower(p.name), p.id) AS rn
                            FROM products p
                            WHERE p.brand_id IN (:ids) AND p.status = 'ACTIVE') ranked
                        WHERE rn <= :limit
                        ORDER BY brand_id, rn
                        """, params.addValue("limit", MAX_LISTED),
                rs -> {
                    products.computeIfAbsent(rs.getObject("brand_id", UUID.class), k -> new ArrayList<>())
                            .add(rs.getString("name"));
                });
        Map<UUID, String> result = new HashMap<>();
        rows.forEach((id, row) -> {
            StringBuilder text = new StringBuilder();
            line(text, "Brand", row[0]);
            line(text, "Description", truncate(normalize(row[1]), MAX_DESCRIPTION_CHARS));
            line(text, "Categories", joined(categories.get(id)));
            line(text, "Products", joined(products.get(id)));
            result.put(id, text.toString().trim());
        });
        return result;
    }

    private Map<UUID, String> categories(Collection<UUID> ids) {
        Map<UUID, CategoryNode> all = new HashMap<>();
        jdbc.getJdbcTemplate().query("SELECT id, name, description, parent_id FROM categories", rs -> {
            all.put(rs.getObject("id", UUID.class), new CategoryNode(rs.getString("name"),
                    rs.getString("description"), rs.getObject("parent_id", UUID.class)));
        });
        Map<UUID, Set<String>> children = new HashMap<>();
        all.forEach((id, node) -> {
            if (node.parentId() != null) {
                children.computeIfAbsent(node.parentId(), k -> sortedSet()).add(node.name());
            }
        });
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        String subtree = """
                WITH RECURSIVE tree(root_id, id, depth) AS (
                    SELECT c.id, c.id, 0 FROM categories c WHERE c.id IN (:ids)
                    UNION ALL
                    SELECT t.root_id, c.id, t.depth + 1 FROM categories c JOIN tree t ON c.parent_id = t.id
                    WHERE t.depth < 20)
                """;
        Map<UUID, Set<String>> brands = new HashMap<>();
        jdbc.query(subtree + """
                        SELECT DISTINCT t.root_id, p.brand FROM tree t
                        JOIN products p ON p.category_id = t.id
                        WHERE p.status = 'ACTIVE' AND p.brand IS NOT NULL
                        """, params,
                rs -> {
                    brands.computeIfAbsent(rs.getObject("root_id", UUID.class), k -> sortedSet())
                            .add(rs.getString("brand"));
                });
        Map<UUID, List<String>> products = new HashMap<>();
        jdbc.query(subtree + """
                        SELECT root_id, name FROM (
                            SELECT t.root_id, p.name,
                                   row_number() OVER (PARTITION BY t.root_id ORDER BY lower(p.name), p.id) AS rn
                            FROM tree t JOIN products p ON p.category_id = t.id
                            WHERE p.status = 'ACTIVE') ranked
                        WHERE rn <= :limit
                        ORDER BY root_id, rn
                        """, params.addValue("limit", MAX_LISTED),
                rs -> {
                    products.computeIfAbsent(rs.getObject("root_id", UUID.class), k -> new ArrayList<>())
                            .add(rs.getString("name"));
                });
        Map<UUID, String> result = new HashMap<>();
        for (UUID id : ids) {
            CategoryNode node = all.get(id);
            if (node == null) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            line(text, "Category", node.name());
            List<String> path = path(id, all);
            if (path.size() > 1) {
                line(text, "Path", String.join(" > ", path));
            }
            line(text, "Description", truncate(normalize(node.description()), MAX_DESCRIPTION_CHARS));
            line(text, "Subcategories", joined(children.get(id)));
            line(text, "Brands", joined(brands.get(id)));
            line(text, "Products", joined(products.get(id)));
            result.put(id, text.toString().trim());
        }
        return result;
    }

    private Map<UUID, String> attributes(Collection<UUID> ids) {
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        Map<UUID, String[]> rows = new LinkedHashMap<>();
        jdbc.query("""
                        SELECT a.id, a.name, a.data_type, a.unit,
                               (SELECT string_agg(o.value, ', ' ORDER BY o.ord)
                                FROM jsonb_array_elements_text(a.options) WITH ORDINALITY AS o(value, ord)) AS options
                        FROM product_attributes a WHERE a.id IN (:ids)
                        """, params,
                rs -> {
                    rows.put(rs.getObject("id", UUID.class), new String[] {rs.getString("name"),
                            rs.getString("data_type"), rs.getString("unit"), rs.getString("options")});
                });
        Map<UUID, Set<String>> categories = new HashMap<>();
        jdbc.query("""
                        SELECT ac.attribute_id, c.name FROM product_attribute_categories ac
                        JOIN categories c ON c.id = ac.category_id
                        WHERE ac.attribute_id IN (:ids)
                        """, params,
                rs -> {
                    categories.computeIfAbsent(rs.getObject("attribute_id", UUID.class), k -> sortedSet())
                            .add(rs.getString("name"));
                });
        Map<UUID, String> result = new HashMap<>();
        rows.forEach((id, row) -> {
            StringBuilder text = new StringBuilder();
            line(text, "Attribute", row[0]);
            line(text, "Type", row[1] == null ? null : row[1].toLowerCase(Locale.ROOT));
            line(text, "Unit", row[2]);
            line(text, "Options", row[3]);
            line(text, "Categories", joined(categories.get(id)));
            result.put(id, text.toString().trim());
        });
        return result;
    }

    private Map<UUID, String> variants(Collection<UUID> ids) {
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        Map<UUID, String[]> rows = new LinkedHashMap<>();
        jdbc.query("""
                        SELECT v.id, v.variant_name, v.sku, p.name AS product, p.brand,
                               coalesce(c.name, p.category) AS category
                        FROM product_variants v
                        JOIN products p ON p.id = v.product_id
                        LEFT JOIN categories c ON c.id = p.category_id
                        WHERE v.id IN (:ids)
                        """, params,
                rs -> {
                    rows.put(rs.getObject("id", UUID.class), new String[] {rs.getString("variant_name"),
                            rs.getString("sku"), rs.getString("product"), rs.getString("brand"),
                            rs.getString("category")});
                });
        Map<UUID, List<String>> options = new HashMap<>();
        jdbc.query("""
                        SELECT vov.variant_id, o.name, ov.value, a.data_type, a.unit
                        FROM variant_option_values vov
                        JOIN product_options o ON o.id = vov.option_id
                        JOIN product_option_values ov ON ov.id = vov.option_value_id
                        LEFT JOIN product_attributes a ON a.id = o.attribute_id
                        WHERE vov.variant_id IN (:ids)
                        ORDER BY vov.variant_id, o.position, o.name
                        """, params,
                rs -> {
                    String value = rs.getString("value");
                    String unit = rs.getString("unit");
                    if ("NUMBER".equals(rs.getString("data_type")) && unit != null && value.matches("\\d+(\\.\\d+)?")) {
                        value = value + " " + unit;
                    }
                    options.computeIfAbsent(rs.getObject("variant_id", UUID.class), k -> new ArrayList<>())
                            .add(normalize(rs.getString("name")) + ": " + normalize(value));
                });
        Map<UUID, String> result = new HashMap<>();
        rows.forEach((id, row) -> {
            StringBuilder text = new StringBuilder();
            line(text, "Variant", row[0]);
            line(text, "SKU", row[1]);
            line(text, "Product", row[2]);
            line(text, "Brand", row[3]);
            line(text, "Category", row[4]);
            List<String> own = options.get(id);
            line(text, "Options", own == null ? null : String.join("; ", own));
            result.put(id, text.toString().trim());
        });
        return result;
    }

    private static List<String> path(UUID id, Map<UUID, CategoryNode> all) {
        List<String> names = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        UUID current = id;
        while (current != null && seen.add(current) && names.size() < MAX_CATEGORY_DEPTH) {
            CategoryNode node = all.get(current);
            if (node == null) {
                break;
            }
            names.addFirst(node.name());
            current = node.parentId();
        }
        return names;
    }

    private static Set<String> sortedSet() {
        return new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    }

    private static String joined(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> list = values.stream().map(CatalogDocumentLoader::normalize).filter(v -> !v.isEmpty()).toList();
        String shown = String.join(", ", list.subList(0, Math.min(list.size(), MAX_LISTED)));
        return list.size() > MAX_LISTED ? shown + " and more" : shown;
    }

    private static void line(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append(label).append(": ").append(normalize(value)).append('\n');
        }
    }

    static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record CategoryNode(String name, String description, UUID parentId) {
    }
}
