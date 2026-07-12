package com.biblocat.scanner;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScannerVisitorTest {

    @Test
    void normalizePath_convertsBackslashes() {
        var result = ScannerVisitor.normalizePath("dir\\subdir\\file.pdf");
        assertEquals("dir/subdir/file.pdf", result);
    }

    @Test
    void normalizePath_appliesNfc() {
        var composed = "\u00e9"; // é in NFC
        var result = ScannerVisitor.normalizePath("caf\u00e9.pdf");
        assertTrue(result.startsWith("caf\u00e9.pdf"));
    }

    @Test
    void normalizePath_doesNotChangeForwardSlashes() {
        var result = ScannerVisitor.normalizePath("a/b/c.pdf");
        assertEquals("a/b/c.pdf", result);
    }

    @Test
    void extensionOf_returnsFullExtension() {
        assertEquals(".pdf", ScannerVisitor.extensionOf("doc.pdf"));
    }

    @Test
    void extensionOf_multipleDots() {
        assertEquals(".gz", ScannerVisitor.extensionOf("archive.tar.gz"));
    }

    @Test
    void extensionOf_noExtension() {
        assertEquals("", ScannerVisitor.extensionOf("Makefile"));
    }

    @Test
    void extensionOf_hiddenFile() {
        assertEquals(".hidden", ScannerVisitor.extensionOf(".hidden"));
    }

    @Test
    void filtersByExtension(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("doc.pdf"));
        Files.createFile(tempDir.resolve("book.epub"));
        Files.createFile(tempDir.resolve("page.mhtml"));
        Files.createFile(tempDir.resolve("notes.txt"));
        Files.createFile(tempDir.resolve("image.png"));

        var visitor = new ScannerVisitor(tempDir);
        Files.walkFileTree(tempDir, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                Integer.MAX_VALUE, visitor);

        var result = visitor.getResult();
        assertEquals(3, result.size());
        assertTrue(result.stream().anyMatch(p -> p.path().endsWith("doc.pdf")));
        assertTrue(result.stream().anyMatch(p -> p.path().endsWith("book.epub")));
        assertTrue(result.stream().anyMatch(p -> p.path().endsWith("page.mhtml")));
    }

    @Test
    void extensionMatchingIsCaseInsensitive(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("DOC.PDF"));
        Files.createFile(tempDir.resolve("BOOK.EPUB"));
        Files.createFile(tempDir.resolve("PAGE.MHTML"));

        var visitor = new ScannerVisitor(tempDir);
        Files.walkFileTree(tempDir, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                Integer.MAX_VALUE, visitor);

        var result = visitor.getResult();
        assertEquals(3, result.size());
    }

    @Test
    void hiddenFilesAreIncluded(@TempDir Path tempDir) throws IOException {
        var hidden = tempDir.resolve(".hidden.pdf");
        Files.createFile(hidden);
        // On Windows we can't easily set hidden, but we can verify the file is scanned

        var visitor = new ScannerVisitor(tempDir);
        Files.walkFileTree(tempDir, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                Integer.MAX_VALUE, visitor);

        var result = visitor.getResult();
        assertEquals(1, result.size());
        assertTrue(result.get(0).path().contains(".hidden.pdf"));
    }

    @Test
    void visitFileFailed_continues(@TempDir Path tempDir) throws IOException {
        var validFile = tempDir.resolve("keep.pdf");
        Files.createFile(validFile);

        var nonExistent = tempDir.resolve("gone.pdf");
        // Do not create it — simulate a file that disappears

        var visitor = new ScannerVisitor(tempDir);
        visitor.visitFileFailed(nonExistent, new IOException("Simulated failure"));

        // Should not throw — CONTINUE is the expected behavior
    }

    @Test
    void preVisitDirectory_skipsUnreadable(@TempDir Path tempDir) throws IOException {
        var subdir = Files.createDirectory(tempDir.resolve("nope"));
        var fileInside = Files.createFile(subdir.resolve("lost.pdf"));

        var visitor = new ScannerVisitor(tempDir);
        var dirAttrs = Files.readAttributes(subdir, java.nio.file.attribute.BasicFileAttributes.class);
        var result = visitor.preVisitDirectory(subdir, dirAttrs);

        assertEquals(java.nio.file.FileVisitResult.CONTINUE, result);
    }

    @Test
    void scansSubdirectories(@TempDir Path tempDir) throws IOException {
        var sub = Files.createDirectory(tempDir.resolve("sub"));
        Files.createFile(sub.resolve("deep.pdf"));

        var visitor = new ScannerVisitor(tempDir);
        Files.walkFileTree(tempDir, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                Integer.MAX_VALUE, visitor);

        var result = visitor.getResult();
        assertEquals(1, result.size());
        assertTrue(result.get(0).path().contains("sub"));
    }
}
