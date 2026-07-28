package com.biblocat.api.dto.response;

import java.util.UUID;

public record TagResponse(
        UUID id,
        String name
) {
}
