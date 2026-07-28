package com.biblocat.api.dto.response;

import com.biblocat.api.entity.FileFormat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record SourceResponse(
        UUID id,
        String name,
        String path,
        FileFormat fileFormat,
        AuthorResponse author,
        Set<TagResponse> tags,
        Integer year,
        String edition,
        String url,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
