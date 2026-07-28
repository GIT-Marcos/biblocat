package com.biblocat.api.exception;

public class TagAlreadyExistsException extends RuntimeException {

    public TagAlreadyExistsException(String name) {
        super("Tag already exists: " + name);
    }
}
