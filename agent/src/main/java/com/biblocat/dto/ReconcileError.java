package com.biblocat.dto;

public record ReconcileError(
        String type,
        String path,
        String sourceId,
        String error
) {}
