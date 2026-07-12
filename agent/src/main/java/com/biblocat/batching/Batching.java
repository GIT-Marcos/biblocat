package com.biblocat.batching;

import java.util.ArrayList;
import java.util.List;

import com.biblocat.dto.Operation;

/**
 * Partitions a list of reconciliation operations into batches for the API.
 *
 * <p>Operations are already ordered by the Classifier (agent.md §3.2.2) per the API
 * contract: RENAME → UPDATE → REACTIVATE → CREATE → DELETE. Batching preserves
 * this order and simply splits into chunks of up to {@code batchSize} elements.</p>
 */
public class Batching {

    public static List<List<Operation>> batch(List<Operation> operations, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be > 0, got: " + batchSize);
        }
        if (operations == null || operations.isEmpty()) {
            return List.of();
        }

        var batches = new ArrayList<List<Operation>>();
        for (var i = 0; i < operations.size(); i += batchSize) {
            var end = Math.min(i + batchSize, operations.size());
            batches.add(operations.subList(i, end));
        }
        return batches;
    }
}
