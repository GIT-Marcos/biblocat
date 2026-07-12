package com.biblocat.dto;

import java.util.List;

public record ReconcileResponse(
        int processed,
        int created,
        int renamed,
        int updated,
        int deleted,
        int reactivated,
        List<ReconcileError> errors
) {}
