package com.biblocat.api.mapper;

import com.biblocat.api.dto.response.AuthorResponse;
import com.biblocat.api.entity.Author;

public final class AuthorMapper {

    private AuthorMapper() {
    }

    public static AuthorResponse toResponse(Author author) {
        return new AuthorResponse(author.getId(), author.getName());
    }
}
