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
import com.biblocat.api.exception.*;
import com.biblocat.api.mapper.SourceMapper;
import com.biblocat.api.repository.SourcePaginationRepository;
import com.biblocat.api.repository.SourceRepository;
import com.biblocat.api.repository.TagRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class SourceService {

    private static final Set<String> KNOWN_OPERATION_ERRORS = Set.of(
            "MISSING_NAME",
            "MISSING_PATH",
            "MISSING_PATH_LOWER",
            "MISSING_CONTENT_HASH",
            "MISSING_SOURCE_ID",
            "UNSUPPORTED_FORMAT"
    );

    private final SourceRepository sourceRepository;
    private final SourcePaginationRepository sourcePaginationRepository;
    private final AuthorService authorService;
    private final TagRepository tagRepository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public SourceService(SourceRepository sourceRepository, SourcePaginationRepository sourcePaginationRepository,
                         AuthorService authorService, TagRepository tagRepository, Clock clock,
                         PlatformTransactionManager transactionManager) {
        this.sourceRepository = sourceRepository;
        this.sourcePaginationRepository = sourcePaginationRepository;
        this.authorService = authorService;
        this.tagRepository = tagRepository;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
            source = sourceRepository.findActiveById(id)
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

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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
                    transactionTemplate.executeWithoutResult(status -> {
                        switch (type) {
                            case CREATE -> processCreate(op);
                            case RENAME -> processRename(op);
                            case UPDATE -> processUpdate(op);
                            case DELETE -> processDelete(op);
                            case REACTIVATE -> processReactivate(op);
                        }
                    });
                    switch (type) {
                        case CREATE -> created++;
                        case RENAME -> renamed++;
                        case UPDATE -> updated++;
                        case DELETE -> deleted++;
                        case REACTIVATE -> reactivated++;
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
        FileFormat format = parseFormat(op.fileFormat());

        if (sourceRepository.existsByPathLowerIgnoreCaseAndDeletedAtIsNull(op.pathLower())) {
            throw new DuplicatePathException(op.pathLower());
        }

        Author author = op.authorName() != null ? authorService.findOrCreate(op.authorName()) : null;
        Source source = new Source(op.name(), op.path(), op.pathLower(), op.contentHash(), format, author);

        List<Source> orphans = sourceRepository.findOrphansByContentHash(op.contentHash());
        if (orphans.size() == 1) {
            Source orphan = orphans.getFirst();
            source.setYear(orphan.getYear());
            source.setEdition(orphan.getEdition());
            source.setUrl(orphan.getUrl());
            source.setTags(new HashSet<>(orphan.getTags()));
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
        Source source = sourceRepository.findByIdIncludeDeleted(op.sourceId())
                .orElseThrow(() -> new SourceNotFoundException(op.sourceId()));

        if (sourceRepository.existsByPathLowerIgnoreCaseAndDeletedAtIsNullAndIdNot(op.pathLower(), op.sourceId())) {
            throw new DuplicatePathException(op.pathLower());
        }

        source.setName(op.name());
        source.setPath(op.path());
        source.setPathLower(op.pathLower());
        source.setFileFormat(parseFormat(op.fileFormat()));

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

        if (sourceRepository.existsByPathLowerIgnoreCaseAndDeletedAtIsNullAndIdNot(source.getPathLower(), op.sourceId())) {
            throw new DuplicatePathException(source.getPathLower());
        }

        source.setDeletedAt(null);
        source.setPath(op.path());
        source.setContentHash(op.contentHash());
        sourceRepository.save(source);
    }

    private static FileFormat parseFormat(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("UNSUPPORTED_FORMAT");
        }
        try {
            return FileFormat.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("UNSUPPORTED_FORMAT");
        }
    }

    private static String mapErrorCode(Exception e) {
        if (e instanceof DuplicateAuthorException) return "DUPLICATE_PATH";
        if (e instanceof DuplicatePathException) return "DUPLICATE_PATH";
        if (e instanceof SourceNotFoundException) return "SOURCE_NOT_FOUND";
        if (e instanceof IllegalArgumentException iae && KNOWN_OPERATION_ERRORS.contains(iae.getMessage())) {
            return iae.getMessage();
        }
        if (e instanceof DataIntegrityViolationException dive
                && dive.getMostSpecificCause().getMessage() != null
                && dive.getMostSpecificCause().getMessage().contains("uq_sources_active_path_lower")) {
            return "DUPLICATE_PATH";
        }
        return "INTERNAL_ERROR";
    }
}
