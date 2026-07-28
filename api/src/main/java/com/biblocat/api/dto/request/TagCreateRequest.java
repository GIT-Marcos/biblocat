package com.biblocat.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TagCreateRequest(
        @NotBlank
        @Size(max = 255)
        String name
) {
}
