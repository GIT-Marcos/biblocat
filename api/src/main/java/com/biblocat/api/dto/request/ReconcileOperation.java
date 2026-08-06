package com.biblocat.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ReconcileOperation(
        @NotNull
        ReconcileOperationType type,

        UUID sourceId,

        @Size(max = 255) String name,

        @Size(max = 1024) String path,

        @Size(max = 1024) String pathLower,

        @Size(max = 64) String contentHash,

        @Size(max = 255) String fileFormat,

        @Size(max = 255) String authorName
) {
}
