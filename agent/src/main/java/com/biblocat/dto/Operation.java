package com.biblocat.dto;

import com.biblocat.model.FileFormat;
import com.biblocat.model.OperationType;

public record Operation(
        OperationType type,
        String sourceId,
        String name,
        String path,
        String pathLower,
        String contentHash,
        FileFormat fileFormat,
        String authorName
) {
    public static Operation create(String name, String path, String pathLower, String contentHash, FileFormat fileFormat, String authorName) {
        return new Operation(OperationType.CREATE, null, name, path, pathLower, contentHash, fileFormat, authorName);
    }

    public static Operation rename(String sourceId, String name, String path, String pathLower, FileFormat fileFormat, String authorName) {
        return new Operation(OperationType.RENAME, sourceId, name, path, pathLower, null, fileFormat, authorName);
    }

    public static Operation update(String sourceId, String contentHash) {
        return new Operation(OperationType.UPDATE, sourceId, null, null, null, contentHash, null, null);
    }

    public static Operation delete(String sourceId) {
        return new Operation(OperationType.DELETE, sourceId, null, null, null, null, null, null);
    }

    public static Operation reactivate(String sourceId, String path, String contentHash) {
        return new Operation(OperationType.REACTIVATE, sourceId, null, path, null, contentHash, null, null);
    }
}
