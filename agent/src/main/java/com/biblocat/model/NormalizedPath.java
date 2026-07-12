package com.biblocat.model;

public record NormalizedPath(
        String path,
        String pathLower,
        FileFormat fileFormat,
        String contentHash
) {
    public NormalizedPath(String path, String pathLower, FileFormat fileFormat) {
        this(path, pathLower, fileFormat, null);
    }

    public NormalizedPath withHash(String contentHash) {
        return new NormalizedPath(path, pathLower, fileFormat, contentHash);
    }
}
