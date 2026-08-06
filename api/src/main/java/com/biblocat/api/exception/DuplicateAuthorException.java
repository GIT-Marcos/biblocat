package com.biblocat.api.exception;

public class DuplicateAuthorException extends RuntimeException {

    public DuplicateAuthorException(String name) {
        super("Author already exists: " + name);
    }
}
