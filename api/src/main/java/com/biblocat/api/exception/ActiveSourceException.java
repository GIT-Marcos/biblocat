package com.biblocat.api.exception;

import java.util.UUID;

public class ActiveSourceException extends RuntimeException {

    public ActiveSourceException(UUID id) {
        super("Source is active (deleted_at IS NULL) and cannot be purged: " + id);
    }
}
