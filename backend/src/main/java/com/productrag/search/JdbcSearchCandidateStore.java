package com.productrag.search;

import com.productrag.product.ProductSpecifications;
import com.productrag.search.dto.SemanticSearchResponse.MatchingVariant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL for semantic search. Only fixed SQL fragments are concatenated; every user-supplied value is a bound
 * parameter.
 */
@Repository
public class JdbcSearchCandidateStore implements SearchCandidateStore {

    static final int MAX_VARIANTS_PER_PRODUCT = 10;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSearchCandidateStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> exactSku(String sku) {
        return jdbc.queryForList("""
                        SELECT p.id FROM products p
                        WHERE p.status = 'ACTIVE'
                          AND (upper(p.sku) = :sku
                               OR EXISTS (SELECT 1 FROM product_variants v
                                          WHERE v.product_id = p.id AND v.status = 'ACTIVE' AND upper(v.sku) = :sku))
                        ORDER BY p.name
                        LIMIT 10
                        """,
                new MapSqlParameterSource("sku", sku.trim().toUpperCase(Locale.ROOT)), UUID.class);
    }

    @Override
    public List<UUID> keyword(String text, int limit) {
        String pattern = "%" + ProductSpecifications.escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%";
        return jdbc.queryForList("""
                        SELECT p.id FROM products p
                        WHERE p.status = 'ACTIVE'
                          AND (lower(p.name) LIKE :pattern ESCAPE '\\' OR lower(p.sku) LIKE :pattern ESCAPE '\\'
                               OR lower(coalesce(p.brand, '')) LIKE :pattern ESCAPE '\\'
                               OR lower(p.category) LIKE :pattern ESCAPE '\\')
                        ORDER BY p.name
                        LIMIT :limit
                        """,
                new MapSqlParameterSource().addValue("pattern", pattern).addValue("limit", limit), UUID.class);
    }

    @Override
    public Verified verify(Collection<UUID> productIds, SemanticSearchQuery query) {
        if (productIds.isEmpty()) {
            return new Verified(Set.of(), Map.of());
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", productIds);
        String variantConditions = variantConditions(query, params);
        StringBuilder sql = new StringBuilder();
        if (query.categoryId() != null) {
            sql.append("""
                    WITH RECURSIVE subtree AS (
                        SELECT id FROM categories WHERE id = :categoryId
                        UNION ALL
                        SELECT c.id FROM categories c JOIN subtree s ON c.parent_id = s.id
                    )
                    """);
            params.addValue("categoryId", query.categoryId());
        }
        sql.append("SELECT p.id FROM products p WHERE p.id IN (:ids) AND p.status = 'ACTIVE'");
        if (query.categoryId() != null) {
            sql.append(" AND p.category_id IN (SELECT id FROM subtree)");
        }
        if (query.brandId() != null) {
            sql.append(" AND p.brand_id = :brandId");
            params.addValue("brandId", query.brandId());
        }
        if (query.hasVariantConditions()) {
            sql.append(" AND ((NOT p.has_variants AND ").append(simpleConditions(query, params))
                    .append(") OR (p.has_variants AND EXISTS (SELECT 1 FROM product_variants v")
                    .append(" WHERE v.product_id = p.id AND v.status = 'ACTIVE'").append(variantConditions)
                    .append(")))");
        }
        Set<UUID> verified = new HashSet<>(jdbc.queryForList(sql.toString(), params, UUID.class));
        return new Verified(verified, matchingVariants(verified, variantConditions, params));
    }

    private Map<UUID, List<MatchingVariant>> matchingVariants(Set<UUID> productIds, String variantConditions,
                                                              MapSqlParameterSource params) {
        Map<UUID, List<MatchingVariant>> variants = new LinkedHashMap<>();
        if (productIds.isEmpty()) {
            return variants;
        }
        params.addValue("verified", productIds);
        jdbc.query("""
                        SELECT v.product_id, v.id, v.sku, v.variant_name, v.price, v.compare_at_price, v.stock_quantity
                        FROM product_variants v
                        WHERE v.product_id IN (:verified) AND v.status = 'ACTIVE'""" + variantConditions + """

                        ORDER BY v.product_id, v.price, v.sku
                        """, params, rs -> {
                    List<MatchingVariant> list = variants.computeIfAbsent(rs.getObject("product_id", UUID.class),
                            id -> new ArrayList<>());
                    if (list.size() < MAX_VARIANTS_PER_PRODUCT) {
                        list.add(new MatchingVariant(rs.getObject("id", UUID.class), rs.getString("sku"),
                                rs.getString("variant_name"), rs.getBigDecimal("price"),
                                rs.getBigDecimal("compare_at_price"), rs.getInt("stock_quantity")));
                    }
                });
        return variants;
    }

    /** Conditions on a simple product's own price and stock; simple products have no options. */
    private static String simpleConditions(SemanticSearchQuery query, MapSqlParameterSource params) {
        if (!query.options().isEmpty()) {
            return "FALSE";
        }
        List<String> conditions = new ArrayList<>(List.of("TRUE"));
        if (query.minPrice() != null) {
            conditions.add("p.price >= :minPrice");
            params.addValue("minPrice", query.minPrice());
        }
        if (query.maxPrice() != null) {
            conditions.add("p.price <= :maxPrice");
            params.addValue("maxPrice", query.maxPrice());
        }
        if (query.inStock()) {
            conditions.add("p.stock_quantity > 0");
        }
        return String.join(" AND ", conditions);
    }

    /** " AND ..." conditions on one variant {@code v}: price, stock and every requested option value. */
    private static String variantConditions(SemanticSearchQuery query, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder();
        if (query.minPrice() != null) {
            sql.append(" AND v.price >= :minPrice");
            params.addValue("minPrice", query.minPrice());
        }
        if (query.maxPrice() != null) {
            sql.append(" AND v.price <= :maxPrice");
            params.addValue("maxPrice", query.maxPrice());
        }
        if (query.inStock()) {
            sql.append(" AND v.stock_quantity > 0");
        }
        int i = 0;
        for (Map.Entry<String, String> option : query.options().entrySet()) {
            String name = "optionName" + i;
            String value = "optionValue" + i;
            sql.append(" AND EXISTS (SELECT 1 FROM variant_option_values vov")
                    .append(" JOIN product_options o ON o.id = vov.option_id")
                    .append(" JOIN product_option_values ov ON ov.id = vov.option_value_id")
                    .append(" WHERE vov.variant_id = v.id AND lower(o.name) = :").append(name)
                    .append(" AND lower(ov.value) = :").append(value).append(")");
            params.addValue(name, option.getKey().trim().toLowerCase(Locale.ROOT));
            params.addValue(value, option.getValue().trim().toLowerCase(Locale.ROOT));
            i++;
        }
        return sql.toString();
    }
}
