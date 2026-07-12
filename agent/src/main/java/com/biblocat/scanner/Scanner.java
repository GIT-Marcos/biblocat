package com.biblocat.scanner;

import java.io.IOException;
import java.util.EnumSet;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.biblocat.model.NormalizedPath;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Scanner {

    private static final Logger LOG = LogManager.getLogger(Scanner.class);

    public static List<NormalizedPath> scan(Path rootDir, int maxDepth) {
        if (!Files.exists(rootDir)) {
            throw new ScannerException("Root directory does not exist: " + rootDir);
        }
        if (!Files.isDirectory(rootDir)) {
            throw new ScannerException("Root path is not a directory: " + rootDir);
        }

        LOG.info("Starting scan of {} (maxDepth={})", rootDir, maxDepth);
        var visitor = new ScannerVisitor(rootDir);

        try {
            Files.walkFileTree(rootDir, EnumSet.noneOf(FileVisitOption.class), maxDepth, visitor);
        } catch (IOException e) {
            throw new ScannerException("Failed to scan directory tree: " + rootDir, e);
        }

        var result = visitor.getResult();
        LOG.info("Scan complete: {} files found", result.size());
        return result;
    }
}
