package com.biblocat;

import com.biblocat.client.ApiClient;
import com.biblocat.config.AgentConfig;
import com.biblocat.config.ConfigurationException;
import com.biblocat.hasher.Hasher;
import com.biblocat.reconcil.ReconciliationRunner;
import com.biblocat.sender.Sender;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class App {

    private static final Logger LOG = LogManager.getLogger(App.class);

    public static void main(String[] args) {
        try {
            var config = AgentConfig.load();
            logConfig(config);

            var rootDir = waitForRootDir(config.rootDir());

            var httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            var apiClient = new ApiClient(httpClient, config);

            waitForApi(apiClient, config);

            var sender = new Sender(httpClient, config);
            var hasher = new Hasher(
                    rootDir,
                    config.hashTimeoutSeconds(),
                    config.hashMaxFileSizeMb(),
                    config.hashMaxRetries()
            );
            var reconciliationInProgress = new AtomicBoolean(false);
            var runner = new ReconciliationRunner(config, apiClient, sender, hasher, reconciliationInProgress, rootDir);

            try (var scheduler = Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "scheduler"));

                 var poller = Executors.newSingleThreadScheduledExecutor(
                         r -> new Thread(r, "poller"))) {

                // §3.3 step 1: register shutdown hook before starting executors
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    LOG.info("Shutdown hook triggered");

                    scheduler.shutdown();
                    poller.shutdown();

                    if (reconciliationInProgress.get()) {
                        try {
                            boolean completed = runner.getCompletionLatch()
                                    .await(config.shutdownGracePeriodSeconds(), TimeUnit.SECONDS);
                            if (!completed) {
                                LOG.warn("Grace period exceeded, forcing shutdown");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    scheduler.shutdownNow();
                    poller.shutdownNow();
                    hasher.shutdown();
                    httpClient.close();

                    LOG.info("Shutdown complete");
                }));

                // §3.3 step 2: cleanup orphan pending flag (EC44)
                try {
                    apiClient.postAck();
                } catch (Exception e) {
                    LOG.warn("Startup ACK failed: {}", e.getMessage());
                }

                // §3.3 step 3: start scheduler (immediate first execution)
                scheduler.scheduleWithFixedDelay(
                        runner,
                        0,
                        config.scanPeriodSeconds(),
                        TimeUnit.SECONDS
                );

                // §3.3 step 4: start poller (immediate first execution)
                poller.scheduleWithFixedDelay(
                        () -> {
                            try {
                                var pending = apiClient.getPending();
                                if (pending.pending()) {
                                    if (runner.runReconciliation()) {
                                        apiClient.postAck();
                                    } else {
                                        LOG.debug("Manual reconciliation deferred: lock contested");
                                    }
                                }
                            } catch (Exception e) {
                                LOG.error("Polling cycle failed: {}", e.getMessage(), e);
                            }
                        },
                        0,
                        config.pollIntervalSeconds(),
                        TimeUnit.SECONDS
                );

                LOG.info("Agent started successfully");

                scheduler.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        } catch (ConfigurationException e) {
            LOG.fatal("Agent configuration failed: {}", e.getMessage());
            LOG.fatal("Check agent.properties and verify root-dir is correct.");
            System.exit(1);
        }
    }

    private static void logConfig(AgentConfig config) {
        LOG.info("root-dir: {}", config.rootDir());
        LOG.info("scan.period-seconds: {}", config.scanPeriodSeconds());
        LOG.info("scan.max-depth: {}", config.scanMaxDepth());
        LOG.info("poll.interval-seconds: {}", config.pollIntervalSeconds());
        LOG.info("hash.timeout-seconds: {}", config.hashTimeoutSeconds());
        LOG.info("hash.max-file-size-mb: {}", config.hashMaxFileSizeMb());
        LOG.info("hash.max-retries: {}", config.hashMaxRetries());
        LOG.info("batch.size: {}", config.batchSize());
        LOG.info("retry.max-attempts: {}", config.retryMaxAttempts());
        LOG.info("retry.backoff-seconds: {}", config.retryBackoffSeconds());
        LOG.info("shutdown.grace-period-seconds: {}", config.shutdownGracePeriodSeconds());
        LOG.info("api.base-url: {}", config.apiBaseUrl());
    }

    private static Path waitForRootDir(Path rootDir) {
        var maxAttempts = 3;
        var baseBackoff = 5;
        for (var attempt = 0; attempt <= maxAttempts; attempt++) {
            if (Files.exists(rootDir) && Files.isDirectory(rootDir)) {
                try {
                    return rootDir.toRealPath();
                } catch (IOException e) {
                    LOG.warn("Could not resolve real path for '{}', using as-is", rootDir);
                    return rootDir;
                }
            }
            if (attempt < maxAttempts) {
                var backoff = baseBackoff * (1 << attempt);
                LOG.warn("Root directory '{}' not found. Retrying in {}s...", rootDir, backoff);
                sleepSeconds(backoff);
            }
        }
        LOG.fatal("Root directory '{}' does not exist after {} attempts. " +
                        "Verify the path exists and the network drive is mounted. Aborting.",
                rootDir, maxAttempts + 1);
        System.exit(1);
        return rootDir;
    }

    private static void waitForApi(ApiClient apiClient, AgentConfig config) {
        var maxSecs = 60;
        var waited = 0;
        while (true) {
            try {
                apiClient.getPending();
                LOG.info("API reachable at {}", config.apiBaseUrl());
                return;
            } catch (Exception e) {
                var backoff = Math.min(waited == 0 ? 5 : waited * 2, 15);
                waited += backoff;
                if (waited >= maxSecs) {
                    break;
                }
                LOG.warn("API not reachable at {}, retrying in {}s... (waited {}s)",
                        config.apiBaseUrl(), backoff, waited);
                sleepSeconds(backoff);
            }
        }
        LOG.warn("API not reachable at {} after {}s. Agent will retry in {}s via the scheduler.",
                config.apiBaseUrl(), maxSecs, config.scanPeriodSeconds());
    }

    private static void sleepSeconds(int seconds) {
        try {
            Thread.sleep(Duration.ofSeconds(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConfigurationException("Interrupted during startup wait");
        }
    }
}
