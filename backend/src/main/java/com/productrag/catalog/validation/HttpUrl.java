package com.productrag.catalog.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Absolute http(s) URL with a host. Null or blank values are accepted (the field is optional). */
@Documented
@Constraint(validatedBy = HttpUrlValidator.class)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpUrl {

    String message() default "Must be a valid http:// or https:// URL";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
