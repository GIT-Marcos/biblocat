package com.biblocat.batching;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.biblocat.dto.Operation;
import com.biblocat.model.FileFormat;
import com.biblocat.model.OperationType;
import org.junit.jupiter.api.Test;

class BatchingTest {

    private static final Operation SAMPLE_OP = new Operation(
            OperationType.CREATE, null, "test.pdf",
            "author/test.pdf", "author/test.pdf",
            "abc", FileFormat.PDF, null
    );

    @Test
    void emptyList_returnsEmpty() {
        var result = Batching.batch(List.of(), 50);
        assertTrue(result.isEmpty());
    }

    @Test
    void nullList_returnsEmpty() {
        var result = Batching.batch(null, 50);
        assertTrue(result.isEmpty());
    }

    @Test
    void singleOperation() {
        var result = Batching.batch(List.of(SAMPLE_OP), 50);
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).size());
    }

    @Test
    void exactBatchSize() {
        var ops = createOps(50);
        var result = Batching.batch(ops, 50);

        assertEquals(1, result.size());
        assertEquals(50, result.get(0).size());
    }

    @Test
    void multipleBatches() {
        var ops = createOps(60);
        var result = Batching.batch(ops, 50);

        assertEquals(2, result.size());
        assertEquals(50, result.get(0).size());
        assertEquals(10, result.get(1).size());
    }

    @Test
    void preservesOrderAcrossBatches() {
        var ops = createOps(60);
        var result = Batching.batch(ops, 50);

        assertEquals(SAMPLE_OP, result.get(0).get(0));
        assertEquals(SAMPLE_OP, result.get(1).get(0));
    }

    @Test
    void batchSizeOfOne() {
        var ops = createOps(3);
        var result = Batching.batch(ops, 1);

        assertEquals(3, result.size());
        assertEquals(1, result.get(0).size());
        assertEquals(1, result.get(1).size());
        assertEquals(1, result.get(2).size());
    }

    @Test
    void invalidBatchSize_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> Batching.batch(List.of(SAMPLE_OP), 0));
        assertThrows(IllegalArgumentException.class,
                () -> Batching.batch(List.of(SAMPLE_OP), -1));
    }

    private static List<Operation> createOps(int count) {
        return java.util.stream.Stream.generate(() -> SAMPLE_OP)
                .limit(count)
                .toList();
    }
}
