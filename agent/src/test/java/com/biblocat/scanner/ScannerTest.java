package com.biblocat.scanner;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScannerTest {

    @Test
    void scansDirectoryTree(@TempDir Path tempDir) throws IOException {
        var sub1 = Files.createDirectory(tempDir.resolve("author1"));
        var sub2 = Files.createDirectory(tempDir.resolve("author2"));
        Files.createFile(sub1.resolve("book.pdf"));
        Files.createFile(sub1.resolve("doc.mhtml"));
        Files.createFile(sub2.resolve("article.epub"));

        var result = Scanner.scan(tempDir, 10);

        assertEquals(3, result.size());
    }

    @Test
    void respectsMaxDepth(@TempDir Path tempDir) throws IOException {
        var l1 = Files.createDirectory(tempDir.resolve("l1"));
        var l2 = Files.createDirectory(l1.resolve("l2"));
        var l3 = Files.createDirectory(l2.resolve("l3"));
        Files.createFile(l3.resolve("deep.pdf"));

        var result = Scanner.scan(tempDir, 2);

        assertTrue(result.isEmpty());
    }

    @Test
    void throwsIfRootMissing() {
        var missing = Path.of("Z:/does/not/exist");
        assertThrows(ScannerException.class, () -> Scanner.scan(missing, 10));
    }

    @Test
    void throwsIfRootIsFile(@TempDir Path tempDir) throws IOException {
        var file = Files.createFile(tempDir.resolve("file.pdf"));
        assertThrows(ScannerException.class, () -> Scanner.scan(file, 10));
    }
}
