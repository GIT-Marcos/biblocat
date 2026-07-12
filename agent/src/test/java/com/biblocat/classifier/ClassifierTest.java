package com.biblocat.classifier;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.biblocat.dto.Operation;
import com.biblocat.dto.SourceState;
import com.biblocat.model.FileFormat;
import com.biblocat.model.NormalizedPath;
import com.biblocat.model.OperationType;
import org.junit.jupiter.api.Test;

class ClassifierTest {

    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

    // ──────────────────────────────────────────────
    // Case A: unchanged — same path, same hash, not deleted
    // ──────────────────────────────────────────────
    @Test
    void caseA_unchanged_skips() {
        var files = List.of(np("a/doc.pdf", HASH_A));
        var sources = List.of(ss("s1", "a/doc.pdf", HASH_A, null));

        var ops = Classifier.classify(files, sources);

        assertTrue(ops.isEmpty());
    }

    // ──────────────────────────────────────────────
    // Case B: REACTIVATE — same path, same hash, soft-deleted
    // ──────────────────────────────────────────────
    @Test
    void caseB_reactivate() {
        var files = List.of(np("a/doc.pdf", HASH_A));
        var sources = List.of(ss("s1", "a/doc.pdf", HASH_A, "2024-01-01T00:00:00Z"));

        var ops = Classifier.classify(files, sources);

        assertEquals(1, ops.size());
        var op = ops.get(0);
        assertEquals(OperationType.REACTIVATE, op.type());
        assertEquals("s1", op.sourceId());
        assertEquals(HASH_A, op.contentHash());
    }

    // ──────────────────────────────────────────────
    // Case C: UPDATE — same path, different hash, active
    // ──────────────────────────────────────────────
    @Test
    void caseC_update() {
        var files = List.of(np("a/doc.pdf", HASH_B));
        var sources = List.of(ss("s1", "a/doc.pdf", HASH_A, null));

        var ops = Classifier.classify(files, sources);

        assertEquals(1, ops.size());
        var op = ops.get(0);
        assertEquals(OperationType.UPDATE, op.type());
        assertEquals("s1", op.sourceId());
        assertEquals(HASH_B, op.contentHash());
    }

    // ──────────────────────────────────────────────
    // Case D: RENAME — new path, hash exists in another source
    // ──────────────────────────────────────────────
    @Test
    void caseD_rename() {
        var files = List.of(np("b/new.pdf", HASH_A));
        var sources = List.of(ss("s1", "a/doc.pdf", HASH_A, null));

        var ops = Classifier.classify(files, sources);

        assertEquals(1, ops.size());
        var op = ops.get(0);
        assertEquals(OperationType.RENAME, op.type());
        assertEquals("s1", op.sourceId());
        assertEquals("b/new.pdf", op.path());
    }

    @Test
    void caseD_rename_prioritizesActiveOverDeleted() {
        var files = List.of(np("b/new.pdf", HASH_A));
        var sources = List.of(
                ss("s1", "a/old.pdf", HASH_A, "2024-01-01T00:00:00Z"),
                ss("s2", "a/active.pdf", HASH_A, null)
        );

        var ops = Classifier.classify(files, sources);

        assertEquals(1, ops.size());
        var op = ops.get(0);
        assertEquals(OperationType.RENAME, op.type());
        assertEquals("s2", op.sourceId());
    }

    @Test
    void caseD_rename_usesAlphabeticTiebreaker() {
        var files = List.of(np("b/new.pdf", HASH_A));
        var sources = List.of(
                ss("s1", "a/bravo.pdf", HASH_A, null),
                ss("s2", "a/alpha.pdf", HASH_A, null)
        );

        var ops = Classifier.classify(files, sources);

        // The unmatched source (bravo) becomes a DELETE
        assertEquals(2, ops.size());
        var renameOp = ops.stream()
                .filter(o -> o.type() == OperationType.RENAME)
                .findFirst()
                .orElseThrow();
        assertEquals("s2", renameOp.sourceId()); // alpha < bravo
    }

    // ──────────────────────────────────────────────
    // Case E: CREATE — new path, new hash
    // ──────────────────────────────────────────────
    @Test
    void caseE_create() {
        var files = List.of(np("b/new.pdf", HASH_C));
        var sources = List.of(ss("s1", "a/doc.pdf", HASH_A, null));

        var ops = Classifier.classify(files, sources);

        assertEquals(2, ops.size());
        var createOp = ops.stream()
                .filter(o -> o.type() == OperationType.CREATE)
                .findFirst()
                .orElseThrow();
        assertEquals("b/new.pdf", createOp.path());
        assertEquals(HASH_C, createOp.contentHash());
        assertEquals(FileFormat.PDF, createOp.fileFormat());
    }

    // ──────────────────────────────────────────────
    // Case F: DELETE — source active but not in FS
    // ──────────────────────────────────────────────
    @Test
    void caseF_delete() {
        var files = List.<NormalizedPath>of();
        var sources = List.of(ss("s1", "a/doc.pdf", HASH_A, null));

        var ops = Classifier.classify(files, sources);

        assertEquals(1, ops.size());
        var op = ops.get(0);
        assertEquals(OperationType.DELETE, op.type());
        assertEquals("s1", op.sourceId());
    }

    // ──────────────────────────────────────────────
    // Case G: already orphan — deletedAt != null, not in FS
    // ──────────────────────────────────────────────
    @Test
    void caseG_alreadyOrphan_skips() {
        var files = List.<NormalizedPath>of();
        var sources = List.of(ss("s1", "a/doc.pdf", HASH_A, "2024-01-01T00:00:00Z"));

        var ops = Classifier.classify(files, sources);

        assertTrue(ops.isEmpty());
    }

    // ──────────────────────────────────────────────
    // Case H: CREATE (orphan stays) — same path as deleted, but different hash
    // ──────────────────────────────────────────────
    @Test
    void caseH_createOrphanStays() {
        var files = List.of(np("a/doc.pdf", HASH_C));
        var sources = List.of(ss("s1", "a/doc.pdf", HASH_A, "2024-01-01T00:00:00Z"));

        var ops = Classifier.classify(files, sources);

        assertEquals(1, ops.size());
        var op = ops.get(0);
        assertEquals(OperationType.CREATE, op.type());
        assertEquals("a/doc.pdf", op.path());
    }

    // ──────────────────────────────────────────────
    // EC39: RENAME + DELETE of same sourceId in same scan
    // ──────────────────────────────────────────────
    @Test
    void ec39_renameAndDelete_sameSource_omitsDelete() {
        var files = List.of(np("b/moved.pdf", HASH_A));
        var sources = List.of(
                ss("s1", "a/original.pdf", HASH_A, null)
        );

        var ops = Classifier.classify(files, sources);

        assertEquals(1, ops.size());
        assertEquals(OperationType.RENAME, ops.get(0).type());
    }

    // ──────────────────────────────────────────────
    // Output order: RENAME < UPDATE < REACTIVATE < CREATE < DELETE
    // ──────────────────────────────────────────────
    @Test
    void outputOrder_respectsApiContract() {
        var files = List.of(
                np("c/create.pdf", HASH_C),
                np("a/update.pdf", HASH_B)
        );
        var sources = List.of(
                ss("s1", "a/update.pdf", HASH_A, null),
                ss("s2", "b/reactivate.pdf", HASH_D, "2024-01-01T00:00:00Z")
        );

        // Need a file matching a soft-deleted source for REACTIVATE
        var files2 = List.of(
                np("c/create.pdf", HASH_C),
                np("a/update.pdf", HASH_B),
                np("b/reactivate.pdf", HASH_D)
        );

        var ops = Classifier.classify(files2, sources);

        assertEquals(3, ops.size());
        assertEquals(OperationType.UPDATE, ops.get(0).type());
        assertEquals(OperationType.REACTIVATE, ops.get(1).type());
        assertEquals(OperationType.CREATE, ops.get(2).type());
    }

    // ──────────────────────────────────────────────
    // Files with null hash are excluded from classification
    // ──────────────────────────────────────────────
    @Test
    void filesWithNullHash_areExcluded() {
        var fileNoHash = new NormalizedPath("a/doc.pdf", "a/doc.pdf", FileFormat.PDF, null);
        var files = List.of(fileNoHash);
        var sources = List.<SourceState>of();

        var ops = Classifier.classify(files, sources);

        assertTrue(ops.isEmpty());
    }

    // ──────────────────────────────────────────────
    // inferAuthor
    // ──────────────────────────────────────────────
    @Test
    void inferAuthor_fromFirstSegment() {
        assertEquals("author", Classifier.inferAuthor("author/book.pdf"));
    }

    @Test
    void inferAuthor_multiLevel() {
        assertEquals("author", Classifier.inferAuthor("author/sub/book.pdf"));
    }

    @Test
    void inferAuthor_nullWhenAtRoot() {
        assertNull(Classifier.inferAuthor("book.pdf"));
    }

    @Test
    void inferAuthor_stripsWhitespace() {
        assertEquals("Author Name", Classifier.inferAuthor("  Author Name  /doc.pdf"));
    }

    @Test
    void inferAuthor_singleSegmentNoSlash() {
        assertNull(Classifier.inferAuthor("book"));
    }

    // ──────────────────────────────────────────────
    // fileName utility
    // ──────────────────────────────────────────────
    @Test
    void fileName_fromNestedPath() {
        assertEquals("doc.pdf", Classifier.fileName("a/b/doc.pdf"));
    }

    @Test
    void fileName_noDirectory() {
        assertEquals("doc.pdf", Classifier.fileName("doc.pdf"));
    }

    // ──────────────────────────────────────────────
    // CREATE includes authorName
    // ──────────────────────────────────────────────
    @Test
    void createOperation_includesAuthorName() {
        var files = List.of(np("author/new.pdf", HASH_C));
        var sources = List.<SourceState>of();

        var ops = Classifier.classify(files, sources);

        assertEquals(1, ops.size());
        assertEquals("author", ops.get(0).authorName());
    }

    @Test
    void createOperation_authorIsNullAtRoot() {
        var files = List.of(np("root.pdf", HASH_C));
        var sources = List.<SourceState>of();

        var ops = Classifier.classify(files, sources);

        assertEquals(1, ops.size());
        assertNull(ops.get(0).authorName());
    }

    // ──────────────────────────────────────────────
    // RENAME includes authorName from NEW path
    // ──────────────────────────────────────────────
    @Test
    void renameOperation_includesNewAuthorName() {
        var files = List.of(np("newauthor/doc.pdf", HASH_A));
        var sources = List.of(ss("s1", "oldauthor/doc.pdf", HASH_A, null));

        var ops = Classifier.classify(files, sources);

        assertEquals(1, ops.size());
        assertEquals(OperationType.RENAME, ops.get(0).type());
        assertEquals("newauthor", ops.get(0).authorName());
        assertEquals("newauthor/doc.pdf", ops.get(0).path());
    }

    // ──────────────────────────────────────────────
    // Mixed scenario: multiple operations in one scan
    // ──────────────────────────────────────────────
    @Test
    void mixedScenario() {
        var files = List.of(
                np("unchanged/file.pdf", HASH_A),
                np("changed/file.pdf", HASH_B),
                np("new/file.pdf", HASH_C)
        );
        var sources = List.of(
                ss("s1", "unchanged/file.pdf", HASH_A, null),
                ss("s2", "changed/file.pdf", HASH_A, null)
        );

        var ops = Classifier.classify(files, sources);

        assertEquals(2, ops.size());
        assertEquals(OperationType.UPDATE, ops.get(0).type());
        assertEquals(OperationType.CREATE, ops.get(1).type());
    }

    // ──────────────────────────────────────────────
    // Select best match when multiple candidates exist
    // ──────────────────────────────────────────────
    @Test
    void selectBestMatch_prefersActiveThenAlphabetic() {
        var sources = List.of(
                ss("s1", "z/active.pdf", HASH_A, null),
                ss("s2", "a/deleted.pdf", HASH_A, "2024-01-01T00:00:00Z"),
                ss("s3", "m/active.pdf", HASH_A, null)
        );

        var best = Classifier.selectBestMatch(sources, java.util.Set.of("s1"));

        // s3 is the active one not excluded, alphabetically first active
        assertEquals("s3", best.id());
    }

    // ──────────────────────────────────────────────
    // Duplicate pathLower in API response logs warning, uses first
    // ──────────────────────────────────────────────
    @Test
    void handlesDuplicatePathInApiResponse() {
        var files = List.of(np("doc.pdf", HASH_A));
        var sources = List.of(
                ss("s1", "doc.pdf", HASH_A, null),
                ss("s2", "doc.pdf", HASH_B, null) // same pathLower, different id
        );

        var ops = Classifier.classify(files, sources);

        // Should use the first source (s1) and match as unchanged (A) → skip
        assertTrue(ops.isEmpty());
    }

    private static NormalizedPath np(String path, String hash) {
        return new NormalizedPath(path, path.toLowerCase(), FileFormat.PDF, hash);
    }

    private static SourceState ss(String id, String path, String hash, String deletedAt) {
        return new SourceState(id, path, hash, path.toLowerCase(), deletedAt);
    }
}
