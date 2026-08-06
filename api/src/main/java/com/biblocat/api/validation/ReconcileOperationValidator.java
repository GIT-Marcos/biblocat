package com.biblocat.api.validation;

import com.biblocat.api.dto.request.ReconcileOperation;
import com.biblocat.api.exception.ReconcileValidationException;
import org.springframework.stereotype.Component;

@Component
public class ReconcileOperationValidator {

    public void validate(ReconcileOperation op) {
        if (op == null || op.type() == null) {
            throw new ReconcileValidationException("MISSING_TYPE");
        }
        switch (op.type()) {
            case CREATE -> validateCreate(op);
            case RENAME -> validateRename(op);
            case UPDATE -> validateUpdate(op);
            case DELETE -> validateDelete(op);
            case REACTIVATE -> validateReactivate(op);
        }
    }

    private void validateCreate(ReconcileOperation op) {
        requireNotBlank("MISSING_NAME", op.name());
        requireNotBlank("MISSING_PATH", op.path());
        requireNotBlank("MISSING_PATH_LOWER", op.pathLower());
        requireNotBlank("MISSING_CONTENT_HASH", op.contentHash());
        requireNotBlank("MISSING_FORMAT", op.fileFormat());
    }

    private void validateRename(ReconcileOperation op) {
        requireNotNull("MISSING_SOURCE_ID", op.sourceId());
        requireNotBlank("MISSING_NAME", op.name());
        requireNotBlank("MISSING_PATH", op.path());
        requireNotBlank("MISSING_PATH_LOWER", op.pathLower());
        requireNotBlank("MISSING_FORMAT", op.fileFormat());
    }

    private void validateUpdate(ReconcileOperation op) {
        requireNotNull("MISSING_SOURCE_ID", op.sourceId());
        requireNotBlank("MISSING_CONTENT_HASH", op.contentHash());
    }

    private void validateDelete(ReconcileOperation op) {
        requireNotNull("MISSING_SOURCE_ID", op.sourceId());
    }

    private void validateReactivate(ReconcileOperation op) {
        requireNotNull("MISSING_SOURCE_ID", op.sourceId());
        requireNotBlank("MISSING_PATH", op.path());
        requireNotBlank("MISSING_PATH_LOWER", op.pathLower());
        requireNotBlank("MISSING_CONTENT_HASH", op.contentHash());
    }

    private static void requireNotNull(String errorCode, Object value) {
        if (value == null) {
            throw new ReconcileValidationException(errorCode);
        }
    }

    private static void requireNotBlank(String errorCode, String value) {
        if (value == null || value.isBlank()) {
            throw new ReconcileValidationException(errorCode);
        }
    }
}
