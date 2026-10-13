package com.productrag.common.exception;

import com.productrag.common.api.ApiError;
import com.productrag.common.api.ApiErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.getStatus());
        if (ex instanceof RateLimitExceededException rate) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(rate.getRetryAfterSeconds()));
        }
        if (ex.getStatus().is5xxServerError()) {
            log.warn("Request {} {} failed: {} ({})", request.getMethod(), request.getRequestURI(), ex.getCode(),
                    ex.getMessage());
        }
        List<ApiError.FieldViolation> violations = ex instanceof FieldValidationException fields
                ? fields.getViolations() : List.of();
        return builder.body(ApiErrorWriter.build(ex.getStatus(), ex.getCode(), ex.getMessage(),
                request.getRequestURI(), violations));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        List<ApiError.FieldViolation> globalViolations = ex.getBindingResult().getGlobalErrors().stream()
                .map(error -> new ApiError.FieldViolation(error.getObjectName(), error.getDefaultMessage()))
                .toList();
        List<ApiError.FieldViolation> all = new java.util.ArrayList<>(violations);
        all.addAll(globalViolations);
        return badRequest("VALIDATION_FAILED", "Request validation failed", request, all);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex,
                                                           HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ApiError.FieldViolation(
                                result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        return badRequest("VALIDATION_FAILED", "Request validation failed", request, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex,
                                                              HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return badRequest("VALIDATION_FAILED", "Request validation failed", request, violations);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof PayloadTooLargeException tooLarge) {
                return error(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", tooLarge.getMessage(), request);
            }
            cause = cause.getCause();
        }
        return badRequest("MALFORMED_REQUEST", "Request body is missing or is not valid JSON", request, List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest request) {
        return badRequest("INVALID_PARAMETER", "Invalid value for parameter '" + ex.getName() + "'", request,
                List.of());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex,
                                                           HttpServletRequest request) {
        return badRequest("MISSING_PARAMETER", "Missing required parameter '" + ex.getParameterName() + "'",
                request, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex,
                                                        HttpServletRequest request) {
        String detail = String.valueOf(ex.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        if (detail.contains("uq_products_sku")) {
            return error(HttpStatus.CONFLICT, "SKU_ALREADY_EXISTS", "A product with this SKU already exists",
                    request);
        }
        if (detail.contains("uq_app_users_email")) {
            return error(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "A user with this email already exists",
                    request);
        }
        if (detail.contains("uq_product_variants_sku")) {
            return error(HttpStatus.CONFLICT, "SKU_ALREADY_EXISTS", "A variant with this SKU already exists", request);
        }
        if (detail.contains("uq_categories_")) {
            return error(HttpStatus.CONFLICT, "CATEGORY_ALREADY_EXISTS",
                    "A category with this name or slug already exists", request);
        }
        if (detail.contains("uq_brands_")) {
            return error(HttpStatus.CONFLICT, "BRAND_ALREADY_EXISTS", "A brand with this name or slug already exists",
                    request);
        }
        if (detail.contains("uq_product_attributes_")) {
            return error(HttpStatus.CONFLICT, "ATTRIBUTE_ALREADY_EXISTS",
                    "An attribute with this name or slug already exists", request);
        }
        if (detail.contains("uq_products_slug")) {
            return error(HttpStatus.CONFLICT, "SLUG_ALREADY_EXISTS", "Another product already uses this slug",
                    request);
        }
        if (detail.contains("uq_product_variants_option_signature")) {
            return error(HttpStatus.CONFLICT, "VARIANT_COMBINATION_EXISTS",
                    "A variant with these options already exists for this product", request);
        }
        if (detail.contains("uq_product_variants_barcode")) {
            return error(HttpStatus.CONFLICT, "BARCODE_ALREADY_EXISTS", "Another variant already uses this barcode",
                    request);
        }
        if (detail.contains("uq_product_options_") || detail.contains("uq_product_option_values_")) {
            return error(HttpStatus.CONFLICT, "OPTION_ALREADY_EXISTS",
                    "This product already has an option or option value with this name", request);
        }
        if (detail.contains("uq_product_images_")) {
            return error(HttpStatus.CONFLICT, "IMAGE_ALREADY_EXISTS", "This product already has an image with this URL",
                    request);
        }
        if (detail.contains("foreign key")) {
            return error(HttpStatus.CONFLICT, "RECORD_IN_USE",
                    "The record is referenced by other records and cannot be changed this way", request);
        }
        log.warn("Data integrity violation on {} {}", request.getMethod(), request.getRequestURI());
        return error(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION",
                "The request conflicts with existing data or violates a database constraint", request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                         HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource was modified by another request. Reload it and try again.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action",
                request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                             HttpServletRequest request) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "HTTP method " + ex.getMethod() + " is not supported for this endpoint", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex,
                                                    HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Content type is not supported; use application/json", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested resource does not exist", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            // E.g. "Stop generating" closed a streamed chat answer; there is nobody left to respond to.
            log.debug("Client disconnected during {} {}", request.getMethod(), request.getRequestURI());
            return null;
        }
        log.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Please try again later.", request);
    }

    private ResponseEntity<ApiError> badRequest(String code, String message, HttpServletRequest request,
                                                List<ApiError.FieldViolation> violations) {
        return ResponseEntity.badRequest()
                .body(ApiErrorWriter.build(HttpStatus.BAD_REQUEST, code, message, request.getRequestURI(), violations));
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message,
                                           HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ApiErrorWriter.build(status, code, message, request.getRequestURI(), List.of()));
    }
}
