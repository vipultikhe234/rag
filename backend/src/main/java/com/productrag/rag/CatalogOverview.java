package com.productrag.rag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Answers questions about the catalog itself ("how many brands do we have?", "list all categories") with exact
 * totals and names read from PostgreSQL. Such questions cannot be answered from a handful of semantically
 * retrieved products, so the LLM is not involved. A question is only treated this way when, apart from the
 * subject word, it contains nothing but question and filler words; "which brands make gaming laptops" still goes
 * through product retrieval.
 */
@Component
public class CatalogOverview {

    enum Subject { BRANDS, CATEGORIES, ATTRIBUTES, VARIANTS }

    static final int MAX_LISTED = 100;

    private static final Map<String, Subject> SUBJECT_WORDS = Map.ofEntries(
            Map.entry("brand", Subject.BRANDS), Map.entry("brands", Subject.BRANDS),
            Map.entry("category", Subject.CATEGORIES), Map.entry("categories", Subject.CATEGORIES),
            Map.entry("subcategory", Subject.CATEGORIES), Map.entry("subcategories", Subject.CATEGORIES),
            Map.entry("attribute", Subject.ATTRIBUTES), Map.entry("attributes", Subject.ATTRIBUTES),
            Map.entry("specification", Subject.ATTRIBUTES), Map.entry("specifications", Subject.ATTRIBUTES),
            Map.entry("variant", Subject.VARIANTS), Map.entry("variants", Subject.VARIANTS));

    private static final Set<String> FILLER = Set.of((
            "a an the of in on at to for and or is are was were be do does did have has had there here we you i me "
                    + "us our your my they it this these those how many much number count total totals list lists "
                    + "show tell give name names which what whats all every each available active inactive currently "
                    + "current catalog catalogue store shop system database db please can could would kindly exist "
                    + "exists existing registered different kinds kind types type sorts overall altogether now today "
                    + "u ur r wt pls plz any some offer offered carry sell sold stock stocked get see know").split(" "));

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogOverview(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RetrievalResult> answer(String question) {
        Set<Subject> subjects = detect(question);
        if (subjects.isEmpty()) {
            return Optional.empty();
        }
        List<String> sections = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        long total = 0;
        for (Subject subject : subjects) {
            Section section = switch (subject) {
                case BRANDS -> brands();
                case CATEGORIES -> categories();
                case ATTRIBUTES -> attributes();
                case VARIANTS -> variants();
            };
            sections.add(section.text());
            sources.add(section.source());
            total += section.total();
        }
        return Optional.of(new RetrievalResult(RetrievalStrategy.CATALOG, List.of(), List.copyOf(sources), total,
                String.join("\n\n", sections)));
    }

    /** Subjects asked about, or empty when the question is about anything else. */
    static Set<Subject> detect(String question) {
        if (question == null || question.isBlank()) {
            return Set.of();
        }
        List<String> tokens = Arrays.stream(question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> !t.isEmpty())
                .toList();
        Set<Subject> subjects = EnumSet.noneOf(Subject.class);
        for (String token : tokens) {
            Subject subject = SUBJECT_WORDS.get(token);
            if (subject != null) {
                subjects.add(subject);
            } else if (!FILLER.contains(token)) {
                return Set.of();
            }
        }
        return subjects;
    }

    private Section brands() {
        List<NamedCount> rows = jdbc.query("""
                        SELECT b.name, b.status, count(p.id) FILTER (WHERE p.status = 'ACTIVE') AS products
                        FROM brands b LEFT JOIN products p ON p.brand_id = b.id
                        GROUP BY b.id, b.name, b.status
                        ORDER BY lower(b.name)
                        """,
                (rs, i) -> new NamedCount(rs.getString("name"), "ACTIVE".equals(rs.getString("status")),
                        rs.getLong("products")));
        if (rows.isEmpty()) {
            return new Section("There are no brands in the catalog yet.", "brands table", 0);
        }
        List<String> active = rows.stream().filter(NamedCount::active)
                .map(r -> markdown(r.name()) + " (" + r.count() + ")").toList();
        List<String> inactive = rows.stream().filter(r -> !r.active()).map(r -> markdown(r.name())).toList();
        StringBuilder text = new StringBuilder()
                .append("There are **").append(rows.size()).append(rows.size() == 1 ? " brand" : " brands")
                .append("** in the catalog: ").append(active.size()).append(" active and ").append(inactive.size())
                .append(" inactive.");
        if (!active.isEmpty()) {
            text.append("\n\n**Active brands** (number of active products in brackets): ").append(joined(active));
        }
        if (!inactive.isEmpty()) {
            text.append("\n\n**Inactive brands:** ").append(joined(inactive));
        }
        return new Section(text.toString(), "brands table (all statuses)", rows.size());
    }

    private Section categories() {
        List<CategoryRow> rows = jdbc.query("SELECT id, name, parent_id, status FROM categories ORDER BY lower(name)",
                (rs, i) -> new CategoryRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("parent_id", UUID.class), "ACTIVE".equals(rs.getString("status"))));
        if (rows.isEmpty()) {
            return new Section("There are no categories in the catalog yet.", "categories table", 0);
        }
        Map<UUID, List<CategoryRow>> children = new HashMap<>();
        rows.stream().filter(r -> r.parentId() != null)
                .forEach(r -> children.computeIfAbsent(r.parentId(), k -> new ArrayList<>()).add(r));
        List<CategoryRow> roots = rows.stream().filter(r -> r.parentId() == null).toList();
        long active = rows.stream().filter(CategoryRow::active).count();
        StringBuilder text = new StringBuilder()
                .append("There are **").append(rows.size()).append(rows.size() == 1 ? " category" : " categories")
                .append("**: ").append(roots.size()).append(" top-level and ").append(rows.size() - roots.size())
                .append(" subcategories (").append(active).append(" active, ").append(rows.size() - active)
                .append(" inactive).\n");
        int listed = 0;
        for (CategoryRow root : roots) {
            if (listed++ >= MAX_LISTED) {
                text.append("\n- … and ").append(roots.size() - MAX_LISTED).append(" more top-level categories");
                break;
            }
            text.append("\n- **").append(markdown(root.name())).append("**").append(root.active() ? "" : " (inactive)");
            List<String> subs = descendants(root.id(), children);
            if (!subs.isEmpty()) {
                text.append(": ").append(joined(subs));
            }
        }
        return new Section(text.toString(), "categories table (all statuses)", rows.size());
    }

    /** Subcategory names below a category (any depth), "(inactive)" marked; cycles cannot occur in the data. */
    private static List<String> descendants(UUID id, Map<UUID, List<CategoryRow>> children) {
        List<String> names = new ArrayList<>();
        for (CategoryRow child : children.getOrDefault(id, List.of())) {
            names.add(markdown(child.name()) + (child.active() ? "" : " (inactive)"));
            descendants(child.id(), children).forEach(name -> names.add(markdown(child.name()) + " › " + name));
        }
        return names;
    }

    private Section attributes() {
        List<String[]> rows = jdbc.query("""
                        SELECT name, data_type, unit, status FROM product_attributes ORDER BY lower(name)
                        """,
                (rs, i) -> new String[] {rs.getString("name"), rs.getString("data_type"), rs.getString("unit"),
                        rs.getString("status")});
        if (rows.isEmpty()) {
            return new Section("There are no product attributes defined yet.", "product_attributes table", 0);
        }
        long active = rows.stream().filter(r -> "ACTIVE".equals(r[3])).count();
        List<String> names = rows.stream().map(r -> markdown(r[0]) + " (" + r[1].toLowerCase(Locale.ROOT)
                + (r[2] == null ? "" : ", " + markdown(r[2])) + ("ACTIVE".equals(r[3]) ? "" : ", inactive") + ")")
                .toList();
        String text = "There are **" + rows.size() + (rows.size() == 1 ? " product attribute" : " product attributes")
                + "** (" + active + " active, " + (rows.size() - active) + " inactive): " + joined(names);
        return new Section(text, "product_attributes table (all statuses)", rows.size());
    }

    private Section variants() {
        Map<String, Long> counts = jdbc.queryForObject("""
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE status = 'ACTIVE') AS active,
                               count(*) FILTER (WHERE status = 'ACTIVE' AND stock_quantity > 0) AS in_stock,
                               count(DISTINCT product_id) AS products
                        FROM product_variants
                        """,
                Map.of(), (rs, i) -> {
                    Map<String, Long> m = new LinkedHashMap<>();
                    for (String column : List.of("total", "active", "in_stock", "products")) {
                        m.put(column, rs.getLong(column));
                    }
                    return m;
                });
        long total = counts.get("total");
        String text = total == 0 ? "There are no product variants yet."
                : "There are **" + total + (total == 1 ? " variant" : " variants") + "** across "
                + counts.get("products") + " products: " + counts.get("active") + " active ("
                + counts.get("in_stock") + " of them in stock) and " + (total - counts.get("active"))
                + " inactive. The Variants page lists them with SKU, options, price and stock.";
        return new Section(text, "product_variants table (all statuses)", total);
    }

    private static String joined(List<String> items) {
        if (items.size() <= MAX_LISTED) {
            return String.join(", ", items);
        }
        return String.join(", ", items.subList(0, MAX_LISTED)) + " … and " + (items.size() - MAX_LISTED) + " more";
    }

    /** Names are catalog data shown as Markdown; neutralize characters that would change the formatting. */
    static String markdown(String text) {
        return text == null ? "" : text.replaceAll("([\\\\`*_\\[\\]<>#|])", "\\\\$1").replaceAll("\\s+", " ").trim();
    }

    private record Section(String text, String source, long total) {
    }

    private record NamedCount(String name, boolean active, long count) {
    }

    private record CategoryRow(UUID id, String name, UUID parentId, boolean active) {
    }
}
