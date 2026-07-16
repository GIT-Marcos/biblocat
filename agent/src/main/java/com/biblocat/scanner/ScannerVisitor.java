package com.biblocat.scanner;

import com.biblocat.model.FileFormat;
import com.biblocat.model.NormalizedPath;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class ScannerVisitor extends SimpleFileVisitor<Path> {

    private static final Logger LOG = LogManager.getLogger(ScannerVisitor.class);

    private final Path rootDir;
    private final List<NormalizedPath> result = new ArrayList<>();

    ScannerVisitor(Path rootDir) {
        this.rootDir = rootDir;
    }

    List<NormalizedPath> getResult() {
        return List.copyOf(result);
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (!attrs.isRegularFile()) {
            return FileVisitResult.CONTINUE;
        }

        var relPath = rootDir.relativize(file);
        var normalized = normalizePath(relPath.toString());
        ThreadContext.put("file", normalized);

        var ext = extensionOf(normalized);
        var format = FileFormat.fromExtension(ext);

        if (format == null) {
            LOG.debug("Skipping unsupported file: {} (extension: {})", normalized, ext);
            return FileVisitResult.CONTINUE;
        }

        var pathLower = normalized.toLowerCase(Locale.ROOT);
        result.add(new NormalizedPath(normalized, pathLower, format));

        try {
            if (Files.isHidden(file)) {
                LOG.debug("Processing hidden file: {}", normalized);
            }
        } catch (IOException e) {
            // Unable to determine hidden status — proceed normally
        }

        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
        LOG.warn("Failed to visit file: {} - {}", file, exc.getMessage());
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        if (!attrs.isDirectory() || !Files.isReadable(dir)) {
            LOG.warn("Skipping unreadable directory: {}", dir);
            return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
    }

    static String normalizePath(String path) {
        var withForwardSlashes = path.replace("\\", "/");
        return Normalizer.normalize(withForwardSlashes, Normalizer.Form.NFC);
    }

    static String extensionOf(String path) {
        var idx = path.lastIndexOf('.');
        return idx == -1 ? "" : path.substring(idx);
    }
}
