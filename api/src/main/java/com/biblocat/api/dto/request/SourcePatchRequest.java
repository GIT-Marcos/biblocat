package com.biblocat.api.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * All three fields MUST be sent on every request (including explicit nulls to clear).
 * The frontend always sends the complete metadata state; this is not a merge-partial DTO.
 */
public record SourcePatchRequest(
        @PositiveOrZero
        Integer year,

        @Size(max = 50)
        String edition,

        @Pattern(regexp = "https?://\\S+")
        @Size(max = 2048)
        String url
) {
}
