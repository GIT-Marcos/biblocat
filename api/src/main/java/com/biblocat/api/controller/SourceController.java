package com.biblocat.api.controller;

import com.biblocat.api.dto.request.ReconcileRequest;
import com.biblocat.api.dto.request.SourcePatchRequest;
import com.biblocat.api.dto.request.SourceTagsRequest;
import com.biblocat.api.dto.response.PageResponse;
import com.biblocat.api.dto.response.PathsEntryResponse;
import com.biblocat.api.dto.response.ReconcileResponse;
import com.biblocat.api.dto.response.SourceResponse;
import com.biblocat.api.entity.FileFormat;
import com.biblocat.api.exception.InvalidPaginationParameterException;
import com.biblocat.api.exception.InvalidSortFieldException;
import com.biblocat.api.service.SourceService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/sources")
public class SourceController {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "name", "path", "fileFormat", "year", "createdAt", "updatedAt", "author.name"
    );

    private final SourceService sourceService;

    public SourceController(SourceService sourceService) {
        this.sourceService = sourceService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<SourceResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID authorId,
            @RequestParam(required = false) UUID tagId,
            @RequestParam(required = false) FileFormat format,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable,
            WebRequest webRequest) {
        validatePagination(webRequest.getParameter("page"), webRequest.getParameter("size"), webRequest.getParameterValues("sort"));
        Page<SourceResponse> page = sourceService.findAll(q, authorId, tagId, format, includeDeleted, pageable);
        return ResponseEntity.ok(PageResponse.from(page));
    }

    private static void validatePagination(String page, String size, String[] sorts) {
        if (page != null) {
            int pageValue;
            try {
                pageValue = Integer.parseInt(page);
            } catch (NumberFormatException e) {
                throw new InvalidPaginationParameterException("page must be a number");
            }
            if (pageValue < 0) {
                throw new InvalidPaginationParameterException("page must not be negative");
            }
        }
        if (size != null) {
            int sizeValue;
            try {
                sizeValue = Integer.parseInt(size);
            } catch (NumberFormatException e) {
                throw new InvalidPaginationParameterException("size must be a number");
            }
            if (sizeValue < 1) {
                throw new InvalidPaginationParameterException("size must be at least 1");
            }
        }
        if (sorts != null) {
            for (String value : sorts) {
                List<String> tokens = Arrays.stream(value.split(","))
                        .filter(token -> !token.isBlank())
                        .toList();
                if (tokens.isEmpty()) {
                    continue;
                }
                String last = tokens.getLast();
                if (last.equalsIgnoreCase("asc") || last.equalsIgnoreCase("desc")) {
                    tokens = tokens.subList(0, tokens.size() - 1);
                }
                for (int i = 0; i < tokens.size(); i++) {
                    String token = tokens.get(i);
                    if (token.contains(":")) {
                        throw new InvalidPaginationParameterException("invalid sort format, use 'field' or 'field,direction'");
                    }
                    if ((token.equalsIgnoreCase("asc") || token.equalsIgnoreCase("desc")) && i != tokens.size() - 1) {
                        throw new InvalidPaginationParameterException("invalid sort direction: " + token);
                    }
                    if (!ALLOWED_SORT_FIELDS.contains(token)) {
                        throw new InvalidSortFieldException(token);
                    }
                }
            }
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<SourceResponse> getById(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean includeDeleted) {
        return ResponseEntity.ok(sourceService.findById(id, includeDeleted));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<SourceResponse> patch(
            @PathVariable UUID id,
            @Valid @RequestBody SourcePatchRequest request) {
        return ResponseEntity.ok(sourceService.patch(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> purge(@PathVariable UUID id) {
        sourceService.purge(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/paths")
    public ResponseEntity<List<PathsEntryResponse>> paths() {
        return ResponseEntity.ok(sourceService.findPathsForReconciliation());
    }

    @PostMapping("/reconcile")
    public ResponseEntity<ReconcileResponse> reconcile(@Valid @RequestBody ReconcileRequest request) {
        return ResponseEntity.ok(sourceService.reconcile(request));
    }

    @PutMapping("/{id}/tags")
    public ResponseEntity<SourceResponse> replaceTags(
            @PathVariable UUID id,
            @Valid @RequestBody SourceTagsRequest request) {
        return ResponseEntity.ok(sourceService.replaceTags(id, request));
    }
}
