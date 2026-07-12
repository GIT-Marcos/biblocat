package com.biblocat.dto;

public record SourceState(
        String id,
        String path,
        String contentHash,
        String pathLower,
        String deletedAt
) {}
