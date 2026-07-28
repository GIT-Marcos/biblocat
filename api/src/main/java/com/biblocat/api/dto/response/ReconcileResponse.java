package com.biblocat.api.dto.response;

import java.util.List;

public record ReconcileResponse(
        int processed,
        int created,
        int renamed,
        int updated,
        int deleted,
        int reactivated,
        List<OperationError> errors
) {
}
