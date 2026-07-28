package com.biblocat.api.exception;

public class DuplicatePathException extends RuntimeException {

    public DuplicatePathException(String pathLower) {
        super("Active source with path_lower already exists: " + pathLower);
    }
}
