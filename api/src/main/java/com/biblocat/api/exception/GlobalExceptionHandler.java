package com.biblocat.api.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SourceNotFoundException.class)
    ProblemDetail handle(SourceNotFoundException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        detail.setType(URI.create("https://api.biblocat.local/errors/source-not-found"));
        detail.setTitle("Source Not Found");
        return detail;
    }

    @ExceptionHandler(TagNotFoundException.class)
    ProblemDetail handle(TagNotFoundException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        detail.setType(URI.create("https://api.biblocat.local/errors/tag-not-found"));
        detail.setTitle("Tag Not Found");
        return detail;
    }

    @ExceptionHandler(ActiveSourceException.class)
    ProblemDetail handle(ActiveSourceException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setType(URI.create("https://api.biblocat.local/errors/active-source"));
        detail.setTitle("Active Source");
        return detail;
    }

    @ExceptionHandler(TagAlreadyExistsException.class)
    ProblemDetail handle(TagAlreadyExistsException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setType(URI.create("https://api.biblocat.local/errors/tag-already-exists"));
        detail.setTitle("Tag Already Exists");
        return detail;
    }

    @ExceptionHandler(DuplicatePathException.class)
    ProblemDetail handle(DuplicatePathException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setType(URI.create("https://api.biblocat.local/errors/duplicate-path"));
        detail.setTitle("Duplicate Path");
        return detail;
    }

    @ExceptionHandler(InvalidSortFieldException.class)
    ProblemDetail handle(InvalidSortFieldException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setType(URI.create("https://api.biblocat.local/errors/invalid-sort-field"));
        detail.setTitle("Invalid Sort Field");
        return detail;
    }

    @ExceptionHandler(InvalidPaginationParameterException.class)
    ProblemDetail handle(InvalidPaginationParameterException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setType(URI.create("https://api.biblocat.local/errors/invalid-pagination-parameter"));
        detail.setTitle("Invalid Pagination Parameter");
        return detail;
    }

    @ExceptionHandler(PropertyReferenceException.class)
    ProblemDetail handle(PropertyReferenceException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Invalid sort field: " + ex.getPropertyName());
        detail.setType(URI.create("https://api.biblocat.local/errors/invalid-sort-field"));
        detail.setTitle("Invalid Sort Field");
        return detail;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handle(MethodArgumentTypeMismatchException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Invalid parameter: " + ex.getName());
        detail.setType(URI.create("https://api.biblocat.local/errors/invalid-request-parameter"));
        detail.setTitle("Invalid Request Parameter");
        return detail;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handle(ConstraintViolationException ex) {
        log.warn("Constraint violation: {}", ex.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Validation failed: " + ex.getMessage());
        detail.setType(URI.create("https://api.biblocat.local/errors/constraint-violation"));
        detail.setTitle("Constraint Violation");
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handle(IllegalArgumentException ex) {
        log.warn("Illegal argument rejected: {}", ex.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setType(URI.create("https://api.biblocat.local/errors/invalid-request-parameter"));
        detail.setTitle("Invalid Request Parameter");
        return detail;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handle(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred.");
        detail.setType(URI.create("https://api.biblocat.local/errors/internal-error"));
        detail.setTitle("Internal Server Error");
        return detail;
    }
}
