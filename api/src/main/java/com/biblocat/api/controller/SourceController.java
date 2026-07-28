package com.biblocat.api.controller;

import com.biblocat.api.dto.request.ReconcileRequest;
import com.biblocat.api.dto.request.SourcePatchRequest;
import com.biblocat.api.dto.request.SourceTagsRequest;
import com.biblocat.api.dto.response.PathsEntryResponse;
import com.biblocat.api.dto.response.ReconcileResponse;
import com.biblocat.api.dto.response.SourceResponse;
import com.biblocat.api.entity.FileFormat;
import com.biblocat.api.service.SourceService;
import jakarta.validation.Valid;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sources")
public class SourceController {

    private final SourceService sourceService;

    public SourceController(SourceService sourceService) {
        this.sourceService = sourceService;
    }

    @GetMapping
    public ResponseEntity<Page<SourceResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID authorId,
            @RequestParam(required = false) UUID tagId,
            @RequestParam(required = false) FileFormat format,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        Page<SourceResponse> page = sourceService.findAll(q, authorId, tagId, format, includeDeleted, pageable);
        return ResponseEntity.ok(page);
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
