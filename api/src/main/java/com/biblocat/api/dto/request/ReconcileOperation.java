package com.biblocat.api.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReconcileOperation(
        @NotNull
        ReconcileOperationType type,

        UUID sourceId,

        String name,

        String path,

        String pathLower,

        String contentHash,

        String fileFormat,

        String authorName
) {
}
