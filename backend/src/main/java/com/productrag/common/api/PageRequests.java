package com.productrag.common.api;

import com.productrag.common.exception.BadRequestException;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Validated pagination and allowlisted sorting ("property,asc|desc") shared by list endpoints. */
public final class PageRequests {

    public static final int MAX_PAGE_SIZE = 100;

    private PageRequests() {
    }

    /**
     * @param sortable API sort name -> entity attribute; anything else is rejected so clients cannot sort by
     *                 arbitrary (possibly sensitive) columns
     */
    public static PageRequest of(int page, int size, String sort, Map<String, String> sortable) {
        if (page < 0) {
            throw new BadRequestException("INVALID_PARAMETER", "page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("INVALID_PARAMETER", "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String[] parts = sort.split(",");
        String property = sortable.get(parts[0].trim());
        if (property == null) {
            throw new BadRequestException("INVALID_PARAMETER", "Unsupported sort property '" + parts[0].trim()
                    + "'. Allowed: " + String.join(", ", sortable.keySet()));
        }
        Sort.Direction direction = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc")
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(page, size, Sort.by(direction, property).and(Sort.by(Sort.Direction.ASC, "id")));
    }
}
