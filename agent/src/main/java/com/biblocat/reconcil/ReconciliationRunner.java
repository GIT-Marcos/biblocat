package com.biblocat.reconcil;

import com.biblocat.batching.Batching;
import com.biblocat.classifier.Classifier;
import com.biblocat.client.ApiClient;
import com.biblocat.config.AgentConfig;
import com.biblocat.hasher.Hasher;
import com.biblocat.scanner.Scanner;
import com.biblocat.sender.Sender;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReconciliationRunner implements Runnable {

    private static final Logger LOG = LogManager.getLogger(ReconciliationRunner.class);

    private final AgentConfig config;
    private final ApiClient apiClient;
    private final Sender sender;
    private final Hasher hasher;
    private final AtomicBoolean reconciliationInProgress;
    private volatile CountDownLatch completionLatch = new CountDownLatch(0);

    public ReconciliationRunner(
            AgentConfig config,
            ApiClient apiClient,
            Sender sender,
            Hasher hasher,
            AtomicBoolean reconciliationInProgress
    ) {
        this.config = config;
        this.apiClient = apiClient;
        this.sender = sender;
        this.hasher = hasher;
        this.reconciliationInProgress = reconciliationInProgress;
    }

    public CountDownLatch getCompletionLatch() {
        return completionLatch;
    }

    @Override
    public void run() {
        runReconciliation();
    }

    /**
     * Executes a full reconciliation pipeline.
     *
     * @return true if the lock was acquired and the pipeline was executed
     *         (including error cases), false if skipped due to lock contention
     *         or aborted due to root directory disappearance
     */
    public boolean runReconciliation() {
        var latch = new CountDownLatch(1);
        completionLatch = latch;

        if (!reconciliationInProgress.compareAndSet(false, true)) {
            LOG.debug("Reconciliation already in progress, skipping");
            return false;
        }

        var startNanos = System.nanoTime();

        try {
            LOG.info("Reconciliation started");

            var knownSources = apiClient.getPaths();

            var scanned = Scanner.scan(config.rootDir(), config.scanMaxDepth());
            LOG.info("Scanned {} files", scanned.size());

            if (!Files.exists(config.rootDir()) || !Files.isReadable(config.rootDir())) {
                LOG.error("Root directory not accessible, aborting reconciliation");
                return false;
            }

            var hashed = hasher.hash(scanned);
            LOG.info("Hashed {} files", hashed.size());

            var operations = Classifier.classify(hashed, knownSources);
            LOG.info("Classified {} operations", operations.size());

            if (operations.isEmpty()) {
                LOG.info("Nothing to reconcile");
                return true;
            }

            var batches = Batching.batch(operations, config.batchSize());
            LOG.info("Split into {} batches", batches.size());

            sender.send(batches);

            LOG.info("Reconciliation completed");
            return true;

        } catch (Exception e) {
            LOG.error("Reconciliation failed: {}", e.getMessage(), e);
            return false;
        } finally {
            var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            LOG.info("Reconciliation finished ({} ms)", elapsedMs);
            reconciliationInProgress.set(false);
            latch.countDown();
        }
    }
}
