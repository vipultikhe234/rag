package com.productrag.common.exception;

import com.productrag.common.api.ApiError;
import java.util.List;
import org.springframework.http.HttpStatus;

/** Business-rule validation failure tied to request fields; returned like Bean Validation errors. */
public class FieldValidationException extends ApiException {

    private final List<ApiError.FieldViolation> violations;

    public FieldValidationException(List<ApiError.FieldViolation> violations) {
        super(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
        this.violations = List.copyOf(violations);
    }

    public static FieldValidationException of(String field, String message) {
        return new FieldValidationException(List.of(new ApiError.FieldViolation(field, message)));
    }

    public List<ApiError.FieldViolation> getViolations() {
        return violations;
    }
}
