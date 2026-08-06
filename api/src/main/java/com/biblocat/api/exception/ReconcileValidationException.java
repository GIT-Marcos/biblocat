package com.biblocat.api.exception;

/**
 * Excepción lanzada cuando una operación de reconciliación falla por datos inválidos.
 * El código de error se envía en el array "errors" de la respuesta.
 */
public class ReconcileValidationException extends RuntimeException {
    private final String errorCode;

    public ReconcileValidationException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
