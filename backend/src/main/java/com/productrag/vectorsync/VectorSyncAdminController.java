package com.productrag.vectorsync;

import com.productrag.common.api.PageResponse;
import com.productrag.vectorsync.dto.VectorSyncEventResponse;
import com.productrag.vectorsync.dto.VectorSyncStatusResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/vector-sync")
@PreAuthorize("hasRole('ADMIN')")
public class VectorSyncAdminController {

    private final VectorSyncAdminService adminService;

    public VectorSyncAdminController(VectorSyncAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/status")
    public VectorSyncStatusResponse status() {
        return adminService.status();
    }

    @GetMapping("/events")
    public PageResponse<VectorSyncEventResponse> events(
            @RequestParam(required = false) VectorSyncStatus status,
            @RequestParam(required = false) UUID productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return adminService.events(status, productId, pageable);
    }

    @PostMapping("/retry/{eventId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public VectorSyncEventResponse retry(@PathVariable UUID eventId) {
        return adminService.retry(eventId);
    }

    @PostMapping("/retry-failed")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Integer> retryAllFailed() {
        return Map.of("requeued", adminService.retryAllFailed());
    }

    @PostMapping("/resync-missing")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public VectorSyncAdminService.MissingResult resyncMissing() {
        return adminService.resyncMissing();
    }

    /**
     * Re-syncs ACTIVE products, brands, categories, attributes and variants whose embedding is missing, from another
     * model, or built from outdated text.
     */
    @PostMapping("/refresh-stale")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public VectorSyncAdminService.RefreshResult refreshStale() {
        return adminService.refreshStale();
    }
}
