package com.biblocat.api.mapper;

import com.biblocat.api.dto.response.SourceResponse;
import com.biblocat.api.entity.Source;

import java.util.stream.Collectors;

public final class SourceMapper {

    private SourceMapper() {
    }

    public static SourceResponse toResponse(Source source) {
        return new SourceResponse(
                source.getId(),
                source.getName(),
                source.getPath(),
                source.getFileFormat(),
                source.getAuthor() != null ? AuthorMapper.toResponse(source.getAuthor()) : null,
                source.getTags().stream()
                        .map(TagMapper::toResponse)
                        .collect(Collectors.toSet()),
                source.getYear(),
                source.getEdition(),
                source.getUrl(),
                source.getCreatedAt(),
                source.getUpdatedAt(),
                source.getDeletedAt()
        );
    }
}
