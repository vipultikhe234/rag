package com.productrag.product.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

public class AttributesValidator implements ConstraintValidator<ValidAttributes, Map<String, Object>> {

    static final int MAX_ENTRIES = 50;
    static final int MAX_KEY_LENGTH = 64;
    static final int MAX_STRING_LENGTH = 500;
    static final int MAX_LIST_SIZE = 20;
    private static final Pattern KEY_PATTERN = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} _.()/-]*$");

    @Override
    public boolean isValid(Map<String, Object> attributes, ConstraintValidatorContext context) {
        if (attributes == null) {
            return true;
        }
        if (attributes.size() > MAX_ENTRIES) {
            return fail(context, "At most " + MAX_ENTRIES + " attributes are allowed");
        }
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH || !KEY_PATTERN.matcher(key).matches()) {
                return fail(context, "Attribute names must be 1-" + MAX_KEY_LENGTH
                        + " characters of letters, digits, spaces or _ . ( ) / -");
            }
            Object value = entry.getValue();
            if (value instanceof Collection<?> list) {
                if (list.size() > MAX_LIST_SIZE) {
                    return fail(context, "Attribute '" + key + "' may contain at most " + MAX_LIST_SIZE + " values");
                }
                for (Object item : list) {
                    if (!isValidScalar(item)) {
                        return fail(context, "Attribute '" + key + "' contains an unsupported value");
                    }
                }
            } else if (!isValidScalar(value)) {
                return fail(context, "Attribute '" + key
                        + "' must be a string (max " + MAX_STRING_LENGTH + " characters), number, boolean or list");
            }
        }
        return true;
    }

    private static boolean isValidScalar(Object value) {
        if (value instanceof String s) {
            return !s.isBlank() && s.length() <= MAX_STRING_LENGTH;
        }
        return value instanceof Number || value instanceof Boolean;
    }

    private static boolean fail(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
