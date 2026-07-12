package com.biblocat.model;

public enum FileFormat {
    PDF,
    EPUB,
    MHTML;

    public static FileFormat fromExtension(String ext) {
        if (ext == null || ext.isBlank()) {
            return null;
        }
        return switch (ext.strip().toLowerCase()) {
            case ".pdf" -> PDF;
            case ".epub" -> EPUB;
            case ".mhtml" -> MHTML;
            default -> null;
        };
    }
}
