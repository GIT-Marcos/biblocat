package com.biblocat;

import com.biblocat.client.ApiClient;
import com.biblocat.config.AgentConfig;
import com.biblocat.config.ConfigurationException;
import com.biblocat.hasher.Hasher;
import com.biblocat.reconcil.ReconciliationRunner;
import com.biblocat.sender.Sender;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.http.HttpClient;
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

            var httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            var apiClient = new ApiClient(httpClient, config);
            var sender = new Sender(httpClient, config);
            var hasher = new Hasher(
                    config.rootDir(),
                    config.hashTimeoutSeconds(),
                    config.hashMaxFileSizeMb(),
                    config.hashMaxRetries()
            );
            var reconciliationInProgress = new AtomicBoolean(false);
            var runner = new ReconciliationRunner(config, apiClient, sender, hasher, reconciliationInProgress);

            var scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "scheduler");
                t.setDaemon(true);
                return t;
            });
            var poller = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "poller");
                t.setDaemon(true);
                return t;
            });

            // §3.3 step 1: register shutdown hook before starting executors
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("Shutdown hook triggered");

                scheduler.shutdown();
                poller.shutdown();

                if (reconciliationInProgress.get()) {
                    var deadline = System.currentTimeMillis()
                            + config.shutdownGracePeriodSeconds() * 1000L;
                    while (reconciliationInProgress.get() && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
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

        } catch (ConfigurationException e) {
            LOG.fatal(e.getMessage());
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
}
