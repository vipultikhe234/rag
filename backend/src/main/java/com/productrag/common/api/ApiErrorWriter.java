package com.productrag.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Writes {@link ApiError} bodies from servlet filters and security handlers. */
@Component
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static ApiError build(HttpStatus status, String code, String message, String path,
                                 List<ApiError.FieldViolation> fieldErrors) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), code, message, path, fieldErrors);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                      String code, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                build(status, code, message, request.getRequestURI(), List.of()));
    }
}
