package com.biblocat.api.mapper;

import com.biblocat.api.dto.response.SourceResponse;
import com.biblocat.api.entity.Source;

import java.util.stream.Collectors;

public final class SourceMapper {

    private SourceMapper() {
    }

    /**
     * Convierte una entidad Source a SourceResponse.
     * <p>
     * <b>REQUIRES:</b> La sesión JPA debe estar activa para poder acceder a
     * {@code source.getTags()} (colección {@code @ManyToMany LAZY}).
     * Esto solo puede invocarse dentro de un contexto transaccional
     * ({@code @Transactional}) o con un fetch eagerly/hibernate.initialize previo.
     * Fuera de una transacción se lanza {@code LazyInitializationException}.
     */
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
