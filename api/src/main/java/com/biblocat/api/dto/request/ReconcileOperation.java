package com.biblocat.api.dto.request;

import com.biblocat.api.entity.FileFormat;
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

        FileFormat fileFormat,

        String authorName
) {
}
