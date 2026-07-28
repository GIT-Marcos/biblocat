package com.biblocat.api.dto.response;

import java.util.UUID;

public record AuthorResponse(
        UUID id,
        String name
) {
}
