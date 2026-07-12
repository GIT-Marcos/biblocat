package com.biblocat.reconcil;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.biblocat.client.ApiClient;
import com.biblocat.config.AgentConfig;
import com.biblocat.hasher.Hasher;
import com.biblocat.sender.Sender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@MockitoSettings(strictness = Strictness.LENIENT)
class ReconciliationRunnerTest {

    @Mock
    private ApiClient apiClient;

    @Mock
    private Sender sender;

    @Test
    void runsFullPipeline(@TempDir Path tempDir) throws Exception {
        Files.writeString(Files.createFile(tempDir.resolve("book.pdf")), "content");
        Files.writeString(Files.createFile(tempDir.resolve("article.epub")), "more");

        var config = config(tempDir);
        var hasher = new Hasher(tempDir, 30, 500, 3);
        var lock = new AtomicBoolean(false);
        var runner = new ReconciliationRunner(config, apiClient, sender, hasher, lock);

        when(apiClient.getPaths()).thenReturn(List.of());

        var result = runner.runReconciliation();

        assertTrue(result);
        assertFalse(lock.get()); // lock released
        verify(sender, atLeastOnce()).send(anyList());
    }

    @Test
    void skipsWhenLockContested(@TempDir Path tempDir) {
        var config = config(tempDir);
        var hasher = new Hasher(tempDir, 30, 500, 3);
        var lock = new AtomicBoolean(true); // lock already held
        var runner = new ReconciliationRunner(config, apiClient, sender, hasher, lock);

        var result = runner.runReconciliation();

        assertFalse(result);
        verify(sender, never()).send(anyList());
    }

    @Test
    void releasesLockOnException(@TempDir Path tempDir) throws Exception {
        var config = config(tempDir);
        var hasher = new Hasher(tempDir, 30, 500, 3);
        var lock = new AtomicBoolean(false);
        var runner = new ReconciliationRunner(config, apiClient, sender, hasher, lock);

        // ApiClient throws → pipeline fails
        when(apiClient.getPaths()).thenThrow(new RuntimeException("API unreachable"));

        var result = runner.runReconciliation();

        assertFalse(result);
        assertFalse(lock.get(), "Lock must be released in finally block");
    }

    @Test
    void lockIsReleasedEvenWhenApiClientThrows(@TempDir Path tempDir) throws Exception {
        var config = config(tempDir);
        var hasher = new Hasher(tempDir, 30, 500, 3);
        var lock = new AtomicBoolean(false);
        var runner = new ReconciliationRunner(config, apiClient, sender, hasher, lock);

        when(apiClient.getPaths()).thenThrow(new RuntimeException("fail"));

        runner.runReconciliation();

        assertFalse(lock.get());
    }

    @Test
    void pipelineWithNewFilesCreatesOperations(@TempDir Path tempDir) throws Exception {
        Files.writeString(Files.createFile(tempDir.resolve("new.pdf")), "new content");

        var config = config(tempDir);
        var hasher = new Hasher(tempDir, 30, 500, 3);
        var lock = new AtomicBoolean(false);
        var runner = new ReconciliationRunner(config, apiClient, sender, hasher, lock);

        when(apiClient.getPaths()).thenReturn(List.of());

        runner.runReconciliation();

        // Sender should have been called with batches containing CREATE operations
        verify(sender, atLeastOnce()).send(anyList());
    }

    private static AgentConfig config(Path rootDir) {
        return new AgentConfig(
                rootDir, 300, 10, 30, 30, 500, 3,
                50, 3, 2, 5, "http://localhost:8080"
        );
    }
}
