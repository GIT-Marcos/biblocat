package com.biblocat.api.dto.response;

import com.biblocat.api.dto.request.ReconcileOperationType;

import java.util.UUID;

public record OperationError(
        ReconcileOperationType type,
        UUID sourceId,
        String path,
        String error
) {
}
