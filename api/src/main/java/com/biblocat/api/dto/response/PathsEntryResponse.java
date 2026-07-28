package com.biblocat.api.dto.response;

import java.time.Instant;
import java.util.UUID;

public record PathsEntryResponse(
    UUID id,
    String path,
    String pathLower,
    String contentHash,
    Instant deletedAt
) {
}
