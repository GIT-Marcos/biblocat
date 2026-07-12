package com.biblocat.hasher;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.biblocat.model.FileFormat;
import com.biblocat.model.NormalizedPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HasherTest {

    private static final String EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String HELLO_HASH = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @Test
    void hashesEmptyFile(@TempDir Path tempDir) throws Exception {
        var file = Files.createFile(tempDir.resolve("empty.pdf"));
        var hasher = new Hasher(tempDir, 30, 500, 3);
        var np = np(tempDir, file, "empty.pdf");

        var result = hasher.hash(List.of(np));

        assertEquals(EMPTY_HASH, result.get(0).contentHash());
    }

    @Test
    void hashesFileWithContent(@TempDir Path tempDir) throws Exception {
        var file = Files.writeString(tempDir.resolve("hello.pdf"), "hello");
        var hasher = new Hasher(tempDir, 30, 500, 3);
        var np = np(tempDir, file, "hello.pdf");

        var result = hasher.hash(List.of(np));

        assertEquals(HELLO_HASH, result.get(0).contentHash());
    }

    @Test
    void hashesMultipleFiles(@TempDir Path tempDir) throws Exception {
        var hasher = new Hasher(tempDir, 30, 500, 3);
        var f1 = Files.writeString(Files.createFile(tempDir.resolve("a.pdf")), "aaa");
        var f2 = Files.writeString(Files.createFile(tempDir.resolve("b.pdf")), "bbb");

        var result = hasher.hash(List.of(np(tempDir, f1, "a.pdf"), np(tempDir, f2, "b.pdf")));

        assertNotNull(result.get(0).contentHash());
        assertNotNull(result.get(1).contentHash());
        assertNotEquals(result.get(0).contentHash(), result.get(1).contentHash());
    }

    @Test
    void timeoutExceeded_returnsNull(@TempDir Path tempDir) throws Exception {
        var file = Files.writeString(Files.createFile(tempDir.resolve("slow.pdf")), "hello");
        var fastTimeout = new Hasher(tempDir, 0, 500, 3); // 0 second timeout
        var np = np(tempDir, file, "slow.pdf");

        var result = fastTimeout.hash(List.of(np));

        assertNull(result.get(0).contentHash());
    }

    @Test
    void nullHashWhenFileDoesNotExist(@TempDir Path tempDir) throws Exception {
        var hasher = new Hasher(tempDir, 30, 500, 3);
        var np = new NormalizedPath("nonexistent.pdf", "nonexistent.pdf", FileFormat.PDF, null);

        var result = hasher.hash(List.of(np));

        assertNull(result.get(0).contentHash());
    }

    @Test
    void sha256FormatIsLowercase64Chars(@TempDir Path tempDir) throws Exception {
        var file = Files.writeString(Files.createFile(tempDir.resolve("test.pdf")), "data");
        var hasher = new Hasher(tempDir, 30, 500, 3);
        var np = np(tempDir, file, "test.pdf");

        var result = hasher.hash(List.of(np));
        var hash = result.get(0).contentHash();

        assertNotNull(hash);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void largerFileHasDifferentHashThanSmallFile(@TempDir Path tempDir) throws Exception {
        var hasher = new Hasher(tempDir, 30, 500, 3);
        var small = Files.writeString(Files.createFile(tempDir.resolve("s.pdf")), "x");
        var large = Files.writeString(Files.createFile(tempDir.resolve("l.pdf")), "x".repeat(1000));

        var result = hasher.hash(List.of(np(tempDir, small, "s.pdf"), np(tempDir, large, "l.pdf")));

        assertNotEquals(result.get(0).contentHash(), result.get(1).contentHash());
    }

    @Test
    void deterministicHashForSameContent(@TempDir Path tempDir) throws Exception {
        var hasher = new Hasher(tempDir, 30, 500, 3);
        var f1 = Files.writeString(Files.createFile(tempDir.resolve("a.pdf")), "same content");
        var f2 = Files.writeString(Files.createFile(tempDir.resolve("b.pdf")), "same content");

        var result = hasher.hash(List.of(np(tempDir, f1, "a.pdf"), np(tempDir, f2, "b.pdf")));

        assertEquals(result.get(0).contentHash(), result.get(1).contentHash());
    }

    @Test
    void preservesInputListSize(@TempDir Path tempDir) throws Exception {
        var hasher = new Hasher(tempDir, 30, 500, 3);
        var file = Files.createFile(tempDir.resolve("a.pdf"));
        var np = np(tempDir, file, "a.pdf");

        var result = hasher.hash(List.of(np));

        assertEquals(1, result.size());
    }

    private static NormalizedPath np(Path rootDir, Path absoluteFile, String pathLower) {
        var relPath = rootDir.relativize(absoluteFile).toString().replace("\\", "/");
        return new NormalizedPath(relPath, pathLower, FileFormat.PDF, null);
    }
}
