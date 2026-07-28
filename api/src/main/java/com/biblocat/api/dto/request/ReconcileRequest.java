package com.biblocat.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ReconcileRequest(
        @NotNull
        @Size(min = 1)
        List<ReconcileOperation> operations
) {
}
