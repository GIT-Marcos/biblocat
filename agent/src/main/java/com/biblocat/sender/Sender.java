package com.biblocat.sender;

import com.biblocat.client.HttpUtil;
import com.biblocat.config.AgentConfig;
import com.biblocat.dto.Operation;
import com.biblocat.dto.ReconcileRequest;
import com.biblocat.dto.ReconcileResponse;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class Sender {

    private static final Logger LOG = LogManager.getLogger(Sender.class);
    private static final int REQUEST_TIMEOUT_SECONDS = 60;
    private static final String DUPLICATE_PATH = "DUPLICATE_PATH";

    private record SendResult(int processed, boolean duplicatePath) {
    }

    private final HttpClient httpClient;
    private final AgentConfig config;
    private final Gson gson;
    private final String reconcileUrl;

    public Sender(HttpClient httpClient, AgentConfig config) {
        this.httpClient = httpClient;
        this.config = config;
        this.gson = new Gson();
        this.reconcileUrl = config.apiBaseUrl() + "/api/sources/reconcile";
    }

    public void send(List<List<Operation>> batches) {
        if (batches == null || batches.isEmpty()) {
            LOG.debug("No batches to send");
            return;
        }

        var totalBatches = batches.size();
        var totalProcessed = 0;
        var sentBatches = 0;

        for (List<Operation> batch : batches) {
            var processed = sendBatch(batch);
            if (processed >= 0) {
                totalProcessed += processed;
                sentBatches++;
            }
        }

        LOG.info("Reconciliation complete: {}/{} batches sent, {} operations processed",
                sentBatches, totalBatches, totalProcessed);
    }

    private int sendBatch(List<Operation> batch) {
        if (batch == null || batch.isEmpty()) {
            return 0;
        }

        ThreadContext.put("batchSize", String.valueOf(batch.size()));
        var json = gson.toJson(new ReconcileRequest(batch));
        var requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(reconcileUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(json));

        var lastException = (Exception) null;
        var hasRetriedDuplicatePath = false;

        for (var attempt = 0; attempt <= config.retryMaxAttempts(); attempt++) {
            try {
                var request = requestBuilder.build();
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                var status = response.statusCode();

                if (HttpUtil.isSuccess(status)) {
                    var result = handleSuccess(response.body());
                    if (result.duplicatePath() && !hasRetriedDuplicatePath) {
                        LOG.warn("DUPLICATE_PATH detected, retrying batch once ({} ops)", batch.size());
                        hasRetriedDuplicatePath = true;
                        sleepBackoff(0);
                        continue;
                    }
                    return result.processed();
                }

                if (status == 409) {
                    LOG.warn("Received 409 for batch ({} ops), retrying once", batch.size());
                    var retry = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (HttpUtil.isSuccess(retry.statusCode())) {
                        var result = handleSuccess(retry.body());
                        if (result.duplicatePath() && !hasRetriedDuplicatePath) {
                            LOG.warn("DUPLICATE_PATH detected after 409 retry, retrying batch once ({} ops)",
                                    batch.size());
                            hasRetriedDuplicatePath = true;
                            sleepBackoff(0);
                            continue;
                        }
                        return result.processed();
                    }
                    LOG.error("409 retry failed with status {} for batch of {} ops, abandoning",
                            retry.statusCode(), batch.size());
                    return -1;
                }

                if (HttpUtil.isClientError(status)) {
                    LOG.error("Received {} for batch of {} ops, abandoning (no retry for client error)",
                            status, batch.size());
                    LOG.debug("Response body: {}", response.body());
                    return -1;
                }

                LOG.warn("Received {} for batch of {} ops (attempt {}/{}), will retry",
                        status, batch.size(), attempt + 1, config.retryMaxAttempts() + 1);

            } catch (IOException | InterruptedException e) {
                lastException = e;
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (attempt < config.retryMaxAttempts()) {
                    LOG.warn("Send failed for batch of {} ops (attempt {}/{}): {}",
                            batch.size(), attempt + 1, config.retryMaxAttempts() + 1, e.getMessage());
                }
            }

            if (attempt == config.retryMaxAttempts()) {
                LOG.error("Exhausted {} retries for batch of {} ops, abandoning",
                        config.retryMaxAttempts(), batch.size(), lastException);
                return -1;
            }

            sleepBackoff(attempt);
        }

        return -1;
    }

    private SendResult handleSuccess(String responseBody) {
        ReconcileResponse response;
        try {
            response = gson.fromJson(responseBody, ReconcileResponse.class);
        } catch (JsonSyntaxException e) {
            LOG.error("Failed to parse reconcile response: {}", e.getMessage());
            return new SendResult(-1, false);
        }

        if (response == null) {
            LOG.warn("Reconcile response body was null");
            return new SendResult(0, false);
        }

        LOG.info("Batch processed: {} (created={}, renamed={}, updated={}, deleted={}, reactivated={})",
                response.processed(), response.created(), response.renamed(),
                response.updated(), response.deleted(), response.reactivated());

        var hasDuplicatePath = false;
        var errors = response.errors();
        if (errors != null && !errors.isEmpty()) {
            for (var err : errors) {
                var target = err.path() != null ? err.path() : err.sourceId();
                LOG.warn("Operation error [{}] on {}: {}", err.type(), target, err.error());
                if (DUPLICATE_PATH.equals(err.error())) {
                    hasDuplicatePath = true;
                }
            }
        }

        return new SendResult(response.processed(), hasDuplicatePath);
    }

    private void sleepBackoff(int attempt) {
        HttpUtil.sleepBackoff(attempt, config.retryBackoffSeconds());
    }
}
