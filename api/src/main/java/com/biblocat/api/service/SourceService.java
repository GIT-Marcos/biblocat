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
import com.biblocat.api.validation.ReconcileOperationValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class SourceService {

    private static final Logger log = LoggerFactory.getLogger(SourceService.class);

    private final SourceRepository sourceRepository;
    private final SourcePaginationRepository sourcePaginationRepository;
    private final AuthorService authorService;
    private final TagRepository tagRepository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final ReconcileOperationValidator reconcileValidator;

    public SourceService(SourceRepository sourceRepository, SourcePaginationRepository sourcePaginationRepository,
                         AuthorService authorService, TagRepository tagRepository, Clock clock,
                         PlatformTransactionManager transactionManager,
                         ReconcileOperationValidator reconcileValidator) {
        this.sourceRepository = sourceRepository;
        this.sourcePaginationRepository = sourcePaginationRepository;
        this.authorService = authorService;
        this.tagRepository = tagRepository;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.reconcileValidator = reconcileValidator;
    }

    @Transactional(readOnly = true)
    public Page<SourceResponse> findAll(String q, UUID authorId, UUID tagId, FileFormat format,
                                        boolean includeDeleted, Pageable pageable) {
        Specification<Source> filter = SourceSpecifications.withFilter(q, authorId, tagId, format, includeDeleted);
        return sourcePaginationRepository.findAll(filter, pageable).map(SourceMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public SourceResponse findById(UUID id, boolean includeDeleted) {
        Source source;
        if (includeDeleted) {
            source = sourceRepository.findById(id)
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

    @Transactional
    public SourceResponse patch(UUID id, SourcePatchRequest request) {
        Source source = sourceRepository.findById(id)
                .orElseThrow(() -> new SourceNotFoundException(id));

        source.setYear(request.year());
        source.setEdition(request.edition());
        source.setUrl(request.url());

        return SourceMapper.toResponse(sourceRepository.save(source));
    }

    @Transactional
    public void purge(UUID id) {
        Source source = sourceRepository.findById(id)
                .orElseThrow(() -> new SourceNotFoundException(id));

        if (source.getDeletedAt() == null) {
            throw new ActiveSourceException(id);
        }

        sourceRepository.hardDeleteById(id);
    }

    @Transactional
    public SourceResponse replaceTags(UUID sourceId, SourceTagsRequest request) {
        Source source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new SourceNotFoundException(sourceId));

        List<Tag> foundTags = tagRepository.findAllById(request.tagIds());

        if (foundTags.size() != request.tagIds().size()) {
            Set<UUID> foundIds = foundTags.stream()
                    .map(Tag::getId)
                    .collect(Collectors.toSet());
            UUID missingId = request.tagIds().stream()
                    .filter(id -> !foundIds.contains(id))
                    .findFirst()
                    .orElseThrow();
            throw new TagNotFoundException(missingId);
        }

        source.setTags(new HashSet<>(foundTags));
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
                    reconcileValidator.validate(op);
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
                    String code = mapErrorCode(e);
                    if ("INTERNAL_ERROR".equals(code)) {
                        log.error("Reconcile op failed unexpectedly: type={}, sourceId={}, path={}",
                                type, op.sourceId(), op.path(), e);
                    } else {
                        log.warn("Reconcile op failed: type={}, sourceId={}, path={}, code={}",
                                type, op.sourceId(), op.path(), code);
                    }
                    errors.add(new OperationError(type, op.sourceId(), op.path(), code));
                }
            }
        }

        int processed = created + renamed + updated + deleted + reactivated;
        return new ReconcileResponse(processed, created, renamed, updated, deleted, reactivated, errors);
    }

    private void processCreate(ReconcileOperation op) {
        FileFormat format = parseFormat(op.fileFormat());

        if (sourceRepository.existsByPathLowerAndDeletedAtIsNull(op.pathLower())) {
            throw new DuplicatePathException(op.pathLower());
        }

        Author author = op.authorName() != null ? authorService.findOrCreate(op.authorName()) : null;
        Source source = new Source(op.name(), op.path(), op.pathLower(), op.contentHash(), format, author);

        List<Source> orphans = sourceRepository.findOrphansByContentHash(op.contentHash());
        if (!orphans.isEmpty()) {
            Source orphan = orphans.stream()
                    .max(Comparator.comparing(Source::getUpdatedAt))
                    .orElseThrow();
            source.setYear(orphan.getYear());
            source.setEdition(orphan.getEdition());
            source.setUrl(orphan.getUrl());
            source.setTags(new HashSet<>(orphan.getTags()));
            sourceRepository.hardDeleteById(orphan.getId());
        }

        sourceRepository.save(source);
    }

    private void processRename(ReconcileOperation op) {
        Source source = sourceRepository.findById(op.sourceId())
                .orElseThrow(() -> new SourceNotFoundException(op.sourceId()));

        if (sourceRepository.existsByPathLowerAndDeletedAtIsNullAndIdNot(op.pathLower(), op.sourceId())) {
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
        Source source = sourceRepository.findById(op.sourceId())
                .orElseThrow(() -> new SourceNotFoundException(op.sourceId()));

        source.setContentHash(op.contentHash());
        sourceRepository.save(source);
    }

    private void processDelete(ReconcileOperation op) {
        Source source = sourceRepository.findById(op.sourceId())
                .orElseThrow(() -> new SourceNotFoundException(op.sourceId()));

        if (source.getDeletedAt() == null) {
            source.setDeletedAt(clock.instant());
            sourceRepository.save(source);
        }
    }

    private void processReactivate(ReconcileOperation op) {
        Source source = sourceRepository.findById(op.sourceId())
                .orElseThrow(() -> new SourceNotFoundException(op.sourceId()));

        if (sourceRepository.existsByPathLowerAndDeletedAtIsNullAndIdNot(op.pathLower(), op.sourceId())) {
            throw new DuplicatePathException(op.pathLower());
        }

        source.setDeletedAt(null);
        source.setPath(op.path());
        source.setPathLower(op.pathLower());
        source.setContentHash(op.contentHash());
        sourceRepository.save(source);
    }

    private static FileFormat parseFormat(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ReconcileValidationException("MISSING_FORMAT");
        }
        try {
            return FileFormat.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ReconcileValidationException("UNSUPPORTED_FORMAT");
        }
    }

    private static String mapErrorCode(Exception e) {
        if (e instanceof DuplicateAuthorException) return "DUPLICATE_AUTHOR";
        if (e instanceof DuplicatePathException) return "DUPLICATE_PATH";
        if (e instanceof SourceNotFoundException) return "SOURCE_NOT_FOUND";
        if (e instanceof ReconcileValidationException rve) return rve.getErrorCode();
        if (e instanceof DataIntegrityViolationException dive
                && dive.getMostSpecificCause().getMessage() != null
                && dive.getMostSpecificCause().getMessage().contains("uq_sources_active_path_lower")) {
            return "DUPLICATE_PATH";
        }
        return "INTERNAL_ERROR";
    }
}
