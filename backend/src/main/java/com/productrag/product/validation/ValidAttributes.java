package com.productrag.product.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Product attributes must be a flat JSON object: at most {@value AttributesValidator#MAX_ENTRIES} keys,
 * keys of 1-64 characters, and values that are strings, numbers, booleans or short lists of those.
 */
@Documented
@Constraint(validatedBy = AttributesValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidAttributes {

    String message() default "Invalid product attributes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
