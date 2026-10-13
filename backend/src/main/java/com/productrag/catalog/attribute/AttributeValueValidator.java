package com.productrag.catalog.attribute;

import com.productrag.common.api.ApiError;
import com.productrag.common.exception.FieldValidationException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Validates product attribute values against the category's attribute definitions (keyed by attribute slug).
 * <ul>
 *   <li>Active definitions: the value must match the data type (numeric/boolean strings are converted, select
 *   values must be one of the options); required ones must be present.</li>
 *   <li>Inactive definitions: an existing value is kept as-is (historical specification) but cannot be changed or
 *   newly added.</li>
 *   <li>Keys without a definition stay allowed as free-form attributes, as before the catalog modules existed.</li>
 * </ul>
 */
@Component
public class AttributeValueValidator {

    private final ProductAttributeService attributeService;

    public AttributeValueValidator(ProductAttributeService attributeService) {
        this.attributeService = attributeService;
    }

    /**
     * @param previous the product's current values (empty for a new product)
     * @return the values with typed/canonical representations for defined attributes
     * @throws FieldValidationException with one violation per invalid value, field "attributes.&lt;slug&gt;"
     */
    public Map<String, Object> validate(UUID categoryId, Map<String, Object> values, Map<String, Object> previous) {
        return validate(categoryId, values, previous, true);
    }

    /** @param enforceRequired false for partial value sets such as variant options (type checks only) */
    public Map<String, Object> validate(UUID categoryId, Map<String, Object> values, Map<String, Object> previous,
                                        boolean enforceRequired) {
        Map<String, Object> result = new LinkedHashMap<>(values);
        List<ApiError.FieldViolation> violations = new ArrayList<>();
        for (ProductAttribute definition : attributeService.applicableDefinitions(categoryId, true)) {
            String key = definition.getSlug();
            String field = "attributes." + key;
            Object value = values.get(key);
            boolean missing = value == null || (value instanceof String s && s.isBlank());
            if (!definition.isActive()) {
                if (!missing && !Objects.equals(value, previous.get(key))) {
                    violations.add(new ApiError.FieldViolation(field,
                            definition.getName() + " is inactive; its value can no longer be changed"));
                }
                continue;
            }
            if (missing) {
                result.remove(key);
                if (enforceRequired && definition.isRequired()) {
                    violations.add(new ApiError.FieldViolation(field, definition.getName() + " is required"));
                }
                continue;
            }
            try {
                result.put(key, coerce(definition, value));
            } catch (IllegalArgumentException e) {
                violations.add(new ApiError.FieldViolation(field, e.getMessage()));
            }
        }
        if (!violations.isEmpty()) {
            throw new FieldValidationException(violations);
        }
        return result;
    }

    public static Object coerce(ProductAttribute definition, Object value) {
        String name = definition.getName();
        return switch (definition.getDataType()) {
            case STRING -> {
                if (!(value instanceof String s)) {
                    throw new IllegalArgumentException(name + " must be text");
                }
                yield s.trim();
            }
            case NUMBER -> {
                if (value instanceof Number number) {
                    yield number;
                }
                if (value instanceof String s) {
                    try {
                        BigDecimal parsed = new BigDecimal(s.trim());
                        yield parsed.stripTrailingZeros().scale() <= 0 ? (Object) parsed.longValueExact() : parsed;
                    } catch (NumberFormatException | ArithmeticException e) {
                        throw new IllegalArgumentException(name + " must be a number");
                    }
                }
                throw new IllegalArgumentException(name + " must be a number");
            }
            case BOOLEAN -> {
                if (value instanceof Boolean bool) {
                    yield bool;
                }
                if (value instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
                    yield Boolean.parseBoolean(s);
                }
                throw new IllegalArgumentException(name + " must be true or false");
            }
            case SELECT -> {
                String text = value instanceof String s ? s.trim() : null;
                if (text != null) {
                    for (String option : definition.getOptions()) {
                        if (option.toLowerCase(Locale.ROOT).equals(text.toLowerCase(Locale.ROOT))) {
                            yield option;
                        }
                    }
                }
                throw new IllegalArgumentException(name + " must be one of: " + String.join(", ", definition.getOptions()));
            }
        };
    }
}
