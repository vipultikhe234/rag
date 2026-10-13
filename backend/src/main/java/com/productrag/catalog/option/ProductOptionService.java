package com.productrag.catalog.option;

import com.productrag.catalog.attribute.AttributeDataType;
import com.productrag.catalog.attribute.AttributeValueValidator;
import com.productrag.catalog.attribute.ProductAttribute;
import com.productrag.catalog.attribute.ProductAttributeRepository;
import com.productrag.catalog.attribute.ProductAttributeService;
import com.productrag.catalog.option.dto.ProductOptionRequest;
import com.productrag.catalog.option.dto.ProductOptionResponse;
import com.productrag.catalog.option.dto.SelectedOption;
import com.productrag.catalog.variant.ProductVariant;
import com.productrag.catalog.variant.ProductVariantRepository;
import com.productrag.common.api.ApiError;
import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.FieldValidationException;
import com.productrag.product.Product;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product options (e.g. Color, Storage) and the option values each variant uses. A variant's combination is
 * identified by its signature (sorted option value ids), which the database keeps unique per product.
 */
@Service
public class ProductOptionService {

    public static final int MAX_OPTIONS = 3;
    private static final Pattern NUMERIC = Pattern.compile("^-?[0-9]+(\\.[0-9]+)?$");

    private final ProductOptionRepository optionRepository;
    private final ProductOptionValueRepository valueRepository;
    private final VariantOptionValueRepository variantValueRepository;
    private final ProductAttributeRepository attributeRepository;
    private final ProductAttributeService attributeService;
    private final ProductVariantRepository variantRepository;

    public ProductOptionService(ProductOptionRepository optionRepository, ProductOptionValueRepository valueRepository,
                                VariantOptionValueRepository variantValueRepository,
                                ProductAttributeRepository attributeRepository,
                                ProductAttributeService attributeService, ProductVariantRepository variantRepository) {
        this.optionRepository = optionRepository;
        this.valueRepository = valueRepository;
        this.variantValueRepository = variantValueRepository;
        this.attributeRepository = attributeRepository;
        this.attributeService = attributeService;
        this.variantRepository = variantRepository;
    }

    /** A resolved option combination for one variant. */
    public record Combination(List<SelectedOption> selections, String signature, String title) {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------------------------------------

    public Map<UUID, List<ProductOptionResponse>> optionsByProduct(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        List<ProductOption> options = optionRepository.findAllByProductIdInOrderByPosition(productIds);
        Map<UUID, List<ProductOptionValue>> values = valuesByOption(options);
        Map<UUID, String> units = units(options);
        Map<UUID, List<ProductOptionResponse>> result = new LinkedHashMap<>();
        for (ProductOption option : options) {
            List<ProductOptionResponse.Value> optionValues = values.getOrDefault(option.getId(), List.of()).stream()
                    .map(v -> new ProductOptionResponse.Value(v.getId(), v.getValue(), v.getPosition()))
                    .toList();
            result.computeIfAbsent(option.getProductId(), id -> new ArrayList<>())
                    .add(new ProductOptionResponse(option.getId(), option.getName(), option.getAttributeId(),
                            units.get(option.getId()), option.getPosition(), optionValues));
        }
        return result;
    }

    public List<ProductOptionResponse> options(UUID productId) {
        return optionsByProduct(List.of(productId)).getOrDefault(productId, List.of());
    }

    /** The selected option values of each variant, in option order. */
    public Map<UUID, List<SelectedOption>> selectionsByVariant(Collection<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        List<VariantOptionValue> rows = variantValueRepository.findAllByVariantIdIn(variantIds);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ProductOption> options = optionRepository.findAllById(
                        rows.stream().map(VariantOptionValue::getOptionId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ProductOption::getId, Function.identity()));
        Map<UUID, ProductOptionValue> values = valueRepository.findAllById(
                        rows.stream().map(VariantOptionValue::getOptionValueId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ProductOptionValue::getId, Function.identity()));
        Map<UUID, String> units = units(options.values());
        Map<UUID, List<SelectedOption>> result = new HashMap<>();
        rows.stream()
                .sorted(Comparator.comparingInt(row -> options.get(row.getOptionId()).getPosition()))
                .forEach(row -> {
                    ProductOption option = options.get(row.getOptionId());
                    ProductOptionValue value = values.get(row.getOptionValueId());
                    result.computeIfAbsent(row.getVariantId(), id -> new ArrayList<>())
                            .add(selected(option, value, units.get(option.getId())));
                });
        return result;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Option definitions
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Stores the product's options exactly as requested (at most three). Values used by variants cannot be removed;
     * a new option gives every existing variant its first value; variant titles and signatures are recalculated.
     */
    @Transactional
    public void sync(Product product, List<ProductOptionRequest> requests) {
        List<ProductOptionRequest> wanted = requests == null ? List.of() : requests;
        if (wanted.size() > MAX_OPTIONS) {
            throw FieldValidationException.of("options", "A product can have at most " + MAX_OPTIONS + " options");
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < wanted.size(); i++) {
            String name = wanted.get(i).name().trim();
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw FieldValidationException.of("options[" + i + "].name", "Option '" + name + "' is listed twice");
            }
        }
        List<ProductOption> current = optionRepository.findAllByProductIdOrderByPosition(product.getId());
        Map<UUID, List<ProductOptionValue>> currentValues = valuesByOption(current);
        List<ProductOption> matched = new ArrayList<>();
        Set<UUID> kept = new HashSet<>();
        for (int i = 0; i < wanted.size(); i++) {
            ProductOption option = match(current, wanted.get(i), "options[" + i + "]");
            if (option != null && !kept.add(option.getId())) {
                throw FieldValidationException.of("options[" + i + "]", "Option '" + option.getName()
                        + "' is listed twice");
            }
            matched.add(option);
        }
        for (ProductOption existing : current) {
            if (!kept.contains(existing.getId())) {
                if (variantValueRepository.existsByOptionId(existing.getId())) {
                    throw new ConflictException("OPTION_IN_USE",
                            "Option '" + existing.getName() + "' is used by variants and cannot be removed");
                }
                valueRepository.deleteAll(currentValues.getOrDefault(existing.getId(), List.of()));
                optionRepository.delete(existing);
            }
        }
        optionRepository.flush();

        List<ProductOption> added = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            ProductOptionRequest request = wanted.get(i);
            String field = "options[" + i + "]";
            ProductOption option = matched.get(i);
            boolean isNew = option == null;
            if (isNew) {
                option = new ProductOption();
                option.setProductId(product.getId());
            }
            UUID previousAttributeId = isNew ? null : option.getAttributeId();
            ProductAttribute attribute = resolveAttribute(product, request.attributeId(), previousAttributeId,
                    field + ".attributeId");
            option.setName(request.name().trim());
            option.setAttributeId(attribute == null ? null : attribute.getId());
            option.setPosition(i);
            option = optionRepository.saveAndFlush(option);
            boolean revalidateAll = !Objects.equals(previousAttributeId, option.getAttributeId());
            syncValues(option, attribute, request.values(),
                    isNew ? List.of() : currentValues.getOrDefault(option.getId(), List.of()), field, revalidateAll);
            if (isNew) {
                added.add(option);
            }
        }

        List<ProductVariant> variants = variantRepository.findAllByProductId(product.getId());
        if (variants.isEmpty()) {
            return;
        }
        for (ProductOption option : added) {
            UUID firstValue = valueRepository.findAllByOptionIdInOrderByPosition(List.of(option.getId()))
                    .getFirst().getId();
            for (ProductVariant variant : variants) {
                variantValueRepository.save(
                        new VariantOptionValue(variant.getId(), product.getId(), option.getId(), firstValue));
            }
        }
        variantValueRepository.flush();
        refreshVariantTitles(variants);
    }

    private ProductOption match(List<ProductOption> current, ProductOptionRequest request, String field) {
        if (request.id() != null) {
            return current.stream().filter(o -> o.getId().equals(request.id())).findFirst()
                    .orElseThrow(() -> FieldValidationException.of(field, "Option does not belong to this product"));
        }
        String name = request.name().trim().toLowerCase(Locale.ROOT);
        return current.stream().filter(o -> o.getName().toLowerCase(Locale.ROOT).equals(name)).findFirst()
                .orElse(null);
    }

    /** A newly linked attribute must be active and apply to the product's category; an existing link may stay. */
    private ProductAttribute resolveAttribute(Product product, UUID attributeId, UUID previousAttributeId,
                                              String field) {
        if (attributeId == null) {
            return null;
        }
        ProductAttribute attribute = attributeRepository.findById(attributeId)
                .orElseThrow(() -> FieldValidationException.of(field, "Attribute does not exist"));
        if (!attributeId.equals(previousAttributeId)) {
            if (!attribute.isActive()) {
                throw FieldValidationException.of(field, "Attribute '" + attribute.getName() + "' is inactive");
            }
            boolean applicable = product.getCategoryId() != null && attributeService
                    .applicableDefinitions(product.getCategoryId(), false).stream()
                    .anyMatch(a -> a.getId().equals(attributeId));
            if (!applicable) {
                throw FieldValidationException.of(field, "Attribute '" + attribute.getName()
                        + "' does not apply to category '" + product.getCategory() + "'");
            }
        }
        return attribute;
    }

    private void syncValues(ProductOption option, ProductAttribute attribute, List<ProductOptionRequest.Value> wanted,
                            List<ProductOptionValue> current, String field, boolean revalidateAll) {
        Set<String> seen = new HashSet<>();
        List<ProductOptionValue> matched = new ArrayList<>();
        Set<UUID> kept = new HashSet<>();
        for (int j = 0; j < wanted.size(); j++) {
            ProductOptionRequest.Value request = wanted.get(j);
            String text = request.value().trim();
            String valueField = field + ".values[" + j + "]";
            if (!seen.add(text.toLowerCase(Locale.ROOT))) {
                throw FieldValidationException.of(valueField, "Value '" + text + "' is listed twice");
            }
            ProductOptionValue value;
            if (request.id() != null) {
                value = current.stream().filter(v -> v.getId().equals(request.id())).findFirst()
                        .orElseThrow(() -> FieldValidationException.of(valueField,
                                "Value does not belong to option '" + option.getName() + "'"));
            } else {
                value = current.stream().filter(v -> v.getValue().equalsIgnoreCase(text)).findFirst().orElse(null);
            }
            if (value != null && !kept.add(value.getId())) {
                throw FieldValidationException.of(valueField, "Value '" + text + "' is listed twice");
            }
            matched.add(value);
        }
        for (ProductOptionValue existing : current) {
            if (!kept.contains(existing.getId())) {
                if (variantValueRepository.existsByOptionValueId(existing.getId())) {
                    throw new ConflictException("OPTION_VALUE_IN_USE", "Value '" + existing.getValue()
                            + "' of option '" + option.getName() + "' is used by variants and cannot be removed");
                }
                valueRepository.delete(existing);
            }
        }
        valueRepository.flush();
        List<ProductOptionValue> toSave = new ArrayList<>();
        for (int j = 0; j < wanted.size(); j++) {
            String text = wanted.get(j).value().trim();
            ProductOptionValue value = matched.get(j);
            boolean changed = value == null || !value.getValue().equals(text);
            if (value == null) {
                value = new ProductOptionValue();
                value.setOptionId(option.getId());
            }
            String stored = attribute != null && (changed || revalidateAll)
                    ? canonical(attribute, text, field + ".values[" + j + "]") : text;
            value.setValue(stored);
            value.setPosition(j);
            toSave.add(value);
        }
        valueRepository.saveAllAndFlush(toSave);
    }

    private static String canonical(ProductAttribute attribute, String text, String field) {
        try {
            Object value = AttributeValueValidator.coerce(attribute, text);
            return value instanceof BigDecimal decimal ? decimal.stripTrailingZeros().toPlainString()
                    : String.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw FieldValidationException.of(field, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Variant combinations
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Resolves option name -> value pairs (case-insensitive) to the product's option values. Every option needs
     * exactly one value, and only values defined on the product are accepted.
     */
    public Combination resolve(UUID productId, Map<String, String> selected, String field) {
        List<ProductOption> options = optionRepository.findAllByProductIdOrderByPosition(productId);
        if (options.isEmpty()) {
            throw FieldValidationException.of(field, "The product has no options; add options to the product first");
        }
        Map<String, String> byName = new HashMap<>();
        if (selected != null) {
            selected.forEach((name, value) -> {
                if (name != null && value != null && !value.isBlank()) {
                    byName.put(name.trim().toLowerCase(Locale.ROOT), value.trim());
                }
            });
        }
        Set<String> known = options.stream().map(o -> o.getName().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        String optionNames = options.stream().map(ProductOption::getName).collect(Collectors.joining(", "));
        List<ApiError.FieldViolation> violations = new ArrayList<>();
        byName.keySet().stream().filter(name -> !known.contains(name)).forEach(name -> violations.add(
                new ApiError.FieldViolation(field, "Unknown option '" + name + "'. The product's options are: "
                        + optionNames)));
        Map<UUID, List<ProductOptionValue>> values = valuesByOption(options);
        Map<UUID, String> units = units(options);
        List<SelectedOption> selections = new ArrayList<>();
        for (ProductOption option : options) {
            String requested = byName.get(option.getName().toLowerCase(Locale.ROOT));
            if (requested == null) {
                violations.add(new ApiError.FieldViolation(field, "Choose a value for option '" + option.getName()
                        + "'"));
                continue;
            }
            values.getOrDefault(option.getId(), List.of()).stream()
                    .filter(v -> v.getValue().equalsIgnoreCase(requested))
                    .findFirst()
                    .ifPresentOrElse(v -> selections.add(selected(option, v, units.get(option.getId()))),
                            () -> violations.add(new ApiError.FieldViolation(field, "'" + requested
                                    + "' is not a value of option '" + option.getName()
                                    + "'. Add it to the product's options first.")));
        }
        if (!violations.isEmpty()) {
            throw new FieldValidationException(violations);
        }
        return new Combination(List.copyOf(selections),
                signature(selections.stream().map(SelectedOption::valueId).toList()), title(selections));
    }

    /** Stores the variant's option values (updates rows in place; the key is variant + option). */
    public void assign(UUID variantId, UUID productId, Combination combination) {
        Map<UUID, VariantOptionValue> existing = variantValueRepository.findAllByVariantIdIn(List.of(variantId))
                .stream().collect(Collectors.toMap(VariantOptionValue::getOptionId, Function.identity()));
        List<VariantOptionValue> rows = new ArrayList<>();
        for (SelectedOption selection : combination.selections()) {
            VariantOptionValue row = existing.remove(selection.optionId());
            if (row == null) {
                row = new VariantOptionValue(variantId, productId, selection.optionId(), selection.valueId());
            } else {
                row.setOptionValueId(selection.valueId());
            }
            rows.add(row);
        }
        variantValueRepository.deleteAll(existing.values());
        variantValueRepository.saveAllAndFlush(rows);
    }

    private void refreshVariantTitles(List<ProductVariant> variants) {
        Map<UUID, List<SelectedOption>> selections = selectionsByVariant(
                variants.stream().map(ProductVariant::getId).toList());
        for (ProductVariant variant : variants) {
            List<SelectedOption> selected = selections.getOrDefault(variant.getId(), List.of());
            variant.setVariantName(title(selected));
            variant.setOptionSignature(signature(selected.stream().map(SelectedOption::valueId).toList()));
        }
        variantRepository.saveAllAndFlush(variants);
    }

    public static String signature(Collection<UUID> valueIds) {
        return valueIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
    }

    public static String title(List<SelectedOption> selections) {
        String title = selections.stream().map(SelectedOption::label).collect(Collectors.joining(" / "));
        return title.length() > 200 ? title.substring(0, 200) : title;
    }

    public static String label(String value, String unit) {
        return unit != null && NUMERIC.matcher(value).matches() ? value + " " + unit : value;
    }

    private static SelectedOption selected(ProductOption option, ProductOptionValue value, String unit) {
        return new SelectedOption(option.getId(), option.getName(), value.getId(), value.getValue(),
                label(value.getValue(), unit));
    }

    private Map<UUID, List<ProductOptionValue>> valuesByOption(Collection<ProductOption> options) {
        if (options.isEmpty()) {
            return Map.of();
        }
        return valueRepository.findAllByOptionIdInOrderByPosition(options.stream().map(ProductOption::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(ProductOptionValue::getOptionId, LinkedHashMap::new,
                        Collectors.toList()));
    }

    /** Option id -> unit of its linked NUMBER attribute. */
    private Map<UUID, String> units(Collection<ProductOption> options) {
        Set<UUID> attributeIds = options.stream().map(ProductOption::getAttributeId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (attributeIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> attributeUnits = new HashMap<>();
        for (ProductAttribute attribute : attributeRepository.findAllById(attributeIds)) {
            if (attribute.getDataType() == AttributeDataType.NUMBER && attribute.getUnit() != null) {
                attributeUnits.put(attribute.getId(), attribute.getUnit());
            }
        }
        Map<UUID, String> units = new HashMap<>();
        for (ProductOption option : options) {
            if (option.getAttributeId() != null && attributeUnits.containsKey(option.getAttributeId())) {
                units.put(option.getId(), attributeUnits.get(option.getAttributeId()));
            }
        }
        return units;
    }
}
