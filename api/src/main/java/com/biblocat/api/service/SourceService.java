package com.biblocat.api.service;

import com.biblocat.api.dto.request.*;
import com.biblocat.api.dto.response.OperationError;
import com.biblocat.api.dto.response.PathsEntryResponse;
import com.biblocat.api.dto.response.ReconcileResponse;
import com.biblocat.api.dto.response.SourceResponse;
import com.biblocat.api.entity.Author;
import com.biblocat.api.entity.FileFormat;
import com.biblocat.api.entity.Source;
import com.biblocat.api.entity.Tag;
import com.biblocat.api.exception.ActiveSourceException;
import com.biblocat.api.exception.DuplicatePathException;
import com.biblocat.api.exception.SourceNotFoundException;
import com.biblocat.api.exception.TagNotFoundException;
import com.biblocat.api.mapper.SourceMapper;
import com.biblocat.api.repository.SourcePaginationRepository;
import com.biblocat.api.repository.SourceRepository;
import com.biblocat.api.repository.TagRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class SourceService {

    private final SourceRepository sourceRepository;
    private final SourcePaginationRepository sourcePaginationRepository;
    private final AuthorService authorService;
    private final TagRepository tagRepository;
    private final Clock clock;

    public SourceService(SourceRepository sourceRepository, SourcePaginationRepository sourcePaginationRepository,
                         AuthorService authorService, TagRepository tagRepository, Clock clock) {
        this.sourceRepository = sourceRepository;
        this.sourcePaginationRepository = sourcePaginationRepository;
        this.authorService = authorService;
        this.tagRepository = tagRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<SourceResponse> findAll(String q, UUID authorId, UUID tagId, FileFormat format,
                                        boolean includeDeleted, Pageable pageable) {
        Specification<Source> spec = SourceSpecifications.withFilter(q, authorId, tagId, format, includeDeleted);
        return sourcePaginationRepository.findAll(spec, pageable).map(SourceMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public SourceResponse findById(UUID id, boolean includeDeleted) {
        Source source;
        if (includeDeleted) {
            source = sourceRepository.findByIdIncludeDeleted(id)
                    .orElseThrow(() -> new SourceNotFoundException(id));
        } else {
            source = sourceRepository.findById(id)
                    .orElseThrow(() -> new SourceNotFoundException(id));
        }
        return SourceMapper.toResponse(source);
    }

    @Transactional(readOnly = true)
    public List<PathsEntryResponse> findPathsForReconciliation() {
        return sourceRepository.findPathsForReconciliation().stream()
                .map(p -> new PathsEntryResponse(p.getId(), p.getPath(), p.getPathLower(), p.getContentHash(), p.getDeletedAt()))
                .toList();
    }

    public SourceResponse patch(UUID id, SourcePatchRequest request) {
        Source source = sourceRepository.findById(id)
                .orElseThrow(() -> new SourceNotFoundException(id));

        source.setYear(request.year());
        source.setEdition(request.edition());
        source.setUrl(request.url());

        return SourceMapper.toResponse(sourceRepository.save(source));
    }

    public void purge(UUID id) {
        Source source = sourceRepository.findByIdIncludeDeleted(id)
                .orElseThrow(() -> new SourceNotFoundException(id));

        if (source.getDeletedAt() == null) {
            throw new ActiveSourceException(id);
        }

        sourceRepository.hardDeleteById(id);
    }

    public SourceResponse replaceTags(UUID sourceId, SourceTagsRequest request) {
        Source source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new SourceNotFoundException(sourceId));

        Set<Tag> tags = new HashSet<>();
        for (UUID tagId : request.tagIds()) {
            Tag tag = tagRepository.findById(tagId)
                    .orElseThrow(() -> new TagNotFoundException(tagId));
            tags.add(tag);
        }

        source.setTags(tags);
        return SourceMapper.toResponse(sourceRepository.save(source));
    }

    public ReconcileResponse reconcile(ReconcileRequest request) {
        int created = 0, renamed = 0, updated = 0, deleted = 0, reactivated = 0;
        List<OperationError> errors = new ArrayList<>();

        var grouped = request.operations().stream()
                .collect(Collectors.groupingBy(ReconcileOperation::type, LinkedHashMap::new, Collectors.toList()));

        List<ReconcileOperationType> order = List.of(
                ReconcileOperationType.RENAME,
                ReconcileOperationType.UPDATE,
                ReconcileOperationType.REACTIVATE,
                ReconcileOperationType.CREATE,
                ReconcileOperationType.DELETE
        );

        for (ReconcileOperationType type : order) {
            for (ReconcileOperation op : grouped.getOrDefault(type, List.of())) {
                try {
                    switch (type) {
                        case CREATE -> {
                            processCreate(op);
                            created++;
                        }
                        case RENAME -> {
                            processRename(op);
                            renamed++;
                        }
                        case UPDATE -> {
                            processUpdate(op);
                            updated++;
                        }
                        case DELETE -> {
                            processDelete(op);
                            deleted++;
                        }
                        case REACTIVATE -> {
                            processReactivate(op);
                            reactivated++;
                        }
                    }
                } catch (Exception e) {
                    errors.add(new OperationError(type, op.sourceId(), op.path(), mapErrorCode(e)));
                }
            }
        }

        int processed = created + renamed + updated + deleted + reactivated;
        return new ReconcileResponse(processed, created, renamed, updated, deleted, reactivated, errors);
    }

    private void processCreate(ReconcileOperation op) {
        if (op.name() == null || op.name().isBlank()) throw new IllegalArgumentException("MISSING_NAME");
        if (op.path() == null || op.path().isBlank()) throw new IllegalArgumentException("MISSING_PATH");
        if (op.pathLower() == null || op.pathLower().isBlank())
            throw new IllegalArgumentException("MISSING_PATH_LOWER");
        if (op.contentHash() == null || op.contentHash().isBlank())
            throw new IllegalArgumentException("MISSING_CONTENT_HASH");
        if (op.fileFormat() == null) throw new IllegalArgumentException("UNSUPPORTED_FORMAT");

        if (sourceRepository.existsByPathLowerIgnoreCaseAndDeletedAtIsNull(op.pathLower())) {
            throw new DuplicatePathException(op.pathLower());
        }

        Author author = op.authorName() != null ? authorService.findOrCreate(op.authorName()) : null;
        Source source = new Source(op.name(), op.path(), op.pathLower(), op.contentHash(), op.fileFormat(), author);

        List<Source> orphans = sourceRepository.findOrphansByContentHash(op.contentHash());
        if (orphans.size() == 1) {
            Source orphan = orphans.getFirst();
            source.setYear(orphan.getYear());
            source.setEdition(orphan.getEdition());
            source.setUrl(orphan.getUrl());
            source.setTags(orphan.getTags());
            sourceRepository.hardDeleteById(orphan.getId());
        }

        sourceRepository.save(source);
    }

    private void processRename(ReconcileOperation op) {
        if (op.sourceId() == null) throw new IllegalArgumentException("MISSING_SOURCE_ID");
        if (op.name() == null || op.name().isBlank()) throw new IllegalArgumentException("MISSING_NAME");
        if (op.path() == null || op.path().isBlank()) throw new IllegalArgumentException("MISSING_PATH");
        if (op.pathLower() == null || op.pathLower().isBlank())
            throw new IllegalArgumentException("MISSING_PATH_LOWER");
        if (op.fileFormat() == null) throw new IllegalArgumentException("UNSUPPORTED_FORMAT");
        Source source = sourceRepository.findByIdIncludeDeleted(op.sourceId())
                .orElseThrow(() -> new SourceNotFoundException(op.sourceId()));

        source.setName(op.name());
        source.setPath(op.path());
        source.setPathLower(op.pathLower());
        source.setFileFormat(op.fileFormat());

        if (op.authorName() != null) {
            source.setAuthor(authorService.findOrCreate(op.authorName()));
        }

        if (source.getDeletedAt() != null) {
            source.setDeletedAt(null);
        }

        sourceRepository.save(source);
    }

    private void processUpdate(ReconcileOperation op) {
        if (op.sourceId() == null) throw new IllegalArgumentException("MISSING_SOURCE_ID");
        if (op.contentHash() == null || op.contentHash().isBlank())
            throw new IllegalArgumentException("MISSING_CONTENT_HASH");
        Source source = sourceRepository.findByIdIncludeDeleted(op.sourceId())
                .orElseThrow(() -> new SourceNotFoundException(op.sourceId()));

        source.setContentHash(op.contentHash());
        sourceRepository.save(source);
    }

    private void processDelete(ReconcileOperation op) {
        if (op.sourceId() == null) throw new IllegalArgumentException("MISSING_SOURCE_ID");
        Source source = sourceRepository.findByIdIncludeDeleted(op.sourceId())
                .orElseThrow(() -> new SourceNotFoundException(op.sourceId()));

        if (source.getDeletedAt() == null) {
            source.setDeletedAt(clock.instant());
            sourceRepository.save(source);
        }
    }

    private void processReactivate(ReconcileOperation op) {
        if (op.sourceId() == null) throw new IllegalArgumentException("MISSING_SOURCE_ID");
        if (op.path() == null || op.path().isBlank()) throw new IllegalArgumentException("MISSING_PATH");
        if (op.contentHash() == null || op.contentHash().isBlank())
            throw new IllegalArgumentException("MISSING_CONTENT_HASH");
        Source source = sourceRepository.findByIdIncludeDeleted(op.sourceId())
                .orElseThrow(() -> new SourceNotFoundException(op.sourceId()));

        source.setDeletedAt(null);
        source.setPath(op.path());
        source.setContentHash(op.contentHash());
        sourceRepository.save(source);
    }

    private static String mapErrorCode(Exception e) {
        if (e instanceof DuplicatePathException) return "DUPLICATE_PATH";
        if (e instanceof SourceNotFoundException) return "SOURCE_NOT_FOUND";
        if (e instanceof IllegalArgumentException iae) return iae.getMessage();
        return "INTERNAL_ERROR";
    }
}
