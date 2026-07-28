package com.biblocat.api.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

public record SourceTagsRequest(
        @NotNull
        Set<UUID> tagIds
) {
}
