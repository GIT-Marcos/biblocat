package com.biblocat.client;

import com.biblocat.config.AgentConfig;
import com.biblocat.dto.AckResponse;
import com.biblocat.dto.PendingResponse;
import com.biblocat.dto.SourceState;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static com.biblocat.client.HttpUtil.isSuccess;

public class ApiClient {

    private static final Logger LOG = LogManager.getLogger(ApiClient.class);
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    private final HttpClient httpClient;
    private final AgentConfig config;
    private final Gson gson;

    public ApiClient(HttpClient httpClient, AgentConfig config) {
        this.httpClient = httpClient;
        this.config = config;
        this.gson = new Gson();
    }

    /**
     * Fetches the known source state from the API.
     *
     * @return list of known sources, never null
     * @throws ApiException if all retries are exhausted
     */
    public List<SourceState> getPaths() {
        var body = sendGetWithRetry(config.apiBaseUrl() + "/api/sources/paths");
        Type listType = new TypeToken<List<SourceState>>() {
        }.getType();
        List<SourceState> result = gson.fromJson(body, listType);
        LOG.info("Loaded {} known sources from API", result.size());
        return result;
    }

    /**
     * Liveness probe for startup. Throws {@link ApiException} if the API is unreachable.
     * Unlike {@link #getPending()}, this method propagates connection failures.
     */
    public void checkConnectivity() {
        sendGetWithRetry(config.apiBaseUrl() + "/api/reconcile/pending");
    }

    /**
     * Polls the API for a pending manual reconciliation.
     *
     * @return pending response (defaults to not-pending if request fails)
     */
    public PendingResponse getPending() {
        try {
            var body = sendGetWithRetry(config.apiBaseUrl() + "/api/reconcile/pending");
            return gson.fromJson(body, PendingResponse.class);
        } catch (ApiException e) {
            LOG.warn("Failed to poll pending flag: {}", e.getMessage());
            return new PendingResponse(false);
        }
    }

    /**
     * Sends an ACK to reset the orphan pending flag on startup (EC44).
     * Also used by the poller after detecting a pending reconciliation.
     *
     * <p>Idempotent, no retry — a failure only logs a warning.</p>
     */
    public AckResponse postAck() {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(config.apiBaseUrl() + "/api/reconcile/ack"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (isSuccess(response.statusCode())) {
                var ack = gson.fromJson(response.body(), AckResponse.class);
                LOG.info("ACK response: acknowledged={}", ack.acknowledged());
                return ack;
            }

            LOG.warn("ACK returned non-success: {}", response.statusCode());
            return new AckResponse(false);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("ACK request failed: {}", e.getMessage());
            return new AckResponse(false);
        }
    }

    private String sendGetWithRetry(String url) {
        var lastException = (Exception) null;

        for (var attempt = 0; attempt <= config.retryMaxAttempts(); attempt++) {
            try {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                        .GET()
                        .build();

                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (isSuccess(response.statusCode())) {
                    return response.body();
                }

                lastException = new IOException("HTTP " + response.statusCode() + " for " + url);

            } catch (IOException | InterruptedException e) {
                lastException = e;
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }

            if (attempt < config.retryMaxAttempts()) {
                LOG.warn("API request failed (attempt {}/{}): {}, will retry",
                        attempt + 1, config.retryMaxAttempts() + 1, url);
                sleepBackoff(attempt);
            }
        }

        throw new ApiException("API request failed after " + (config.retryMaxAttempts() + 1)
                + " attempts: " + url, lastException);
    }

    private void sleepBackoff(int attempt) {
        HttpUtil.sleepBackoff(attempt, config.retryBackoffSeconds());
    }
}
