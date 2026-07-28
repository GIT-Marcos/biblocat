package com.biblocat.api.dto.response;

public record ReconciliationStatusResponse(
        boolean pending,
        String message
) {
}
