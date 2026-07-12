package com.biblocat.hasher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.biblocat.model.NormalizedPath;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Computes SHA-256 content hashes for scanned files.
 *
 * <p>This class maintains mutable state across scan invocations — a per-file retry counter for
 * write-race detection (agent.md §2.6). The counter persists in memory for the lifetime of the
 * Agent session and is lost on restart, as specified by design. Each successful hash resets the
 * counter for that file; consecutive write-race failures produce ERROR-level logging once the
 * configured threshold ({@code maxRetries}) is exceeded.</p>
 */
public class Hasher {

    private static final Logger LOG = LogManager.getLogger(Hasher.class);
    private static final int BUFFER_SIZE = 8192;
    private static final String SHA_256 = "SHA-256";

    private final Path rootDir;
    private final int timeoutSeconds;
    private final int maxFileSizeMb;
    private final long maxFileSizeBytes;
    private final int maxRetries;

    /**
     * Per-file retry counter for write-race detection.
     * <p>
     * Keyed by {@code pathLower} (stable across scans), value is the consecutive failure count.
     * Reset to 0 on successful hash, incremented on write-race. Volatile — lost on Agent restart,
     * as documented in agent.md §2.6.
     */
    private final Map<String, Integer> retryCounters = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "hasher");
        t.setDaemon(true);
        return t;
    });

    public Hasher(Path rootDir, int timeoutSeconds, int maxFileSizeMb, int maxRetries) {
        this.rootDir = rootDir;
        this.timeoutSeconds = timeoutSeconds;
        this.maxFileSizeMb = maxFileSizeMb;
        this.maxFileSizeBytes = maxFileSizeMb == 0 ? Long.MAX_VALUE : (long) maxFileSizeMb * 1_048_576L;
        this.maxRetries = maxRetries;
    }

    /**
     * Computes SHA-256 for each file in the input list.
     *
     * <p>Files that succeed have {@code contentHash} set to a 64-character lowercase hex string.
     * Files that fail (timeout, too large, write-race, I/O error) retain {@code contentHash = null}
     * and are excluded from further pipeline processing until the next scan.</p>
     *
     * @param files scanned files with paths relative to rootDir
     * @return list of the same size, enriched with contentHash on success
     */
    public List<NormalizedPath> hash(List<NormalizedPath> files) {
        var result = new ArrayList<NormalizedPath>(files.size());

        for (var file : files) {
            var absolutePath = rootDir.resolve(file.path());
            var hash = hashFile(file, absolutePath);
            result.add(hash != null ? file.withHash(hash) : file);
        }

        return result;
    }

    private String hashFile(NormalizedPath file, Path absolutePath) {
        try {
            var size = Files.size(absolutePath);

            if (size > maxFileSizeBytes) {
                LOG.warn("File exceeds max size ({} MB): {}", maxFileSizeMb, file.path());
                return null;
            }

            var sizeBefore = Files.size(absolutePath);
            var hash = computeWithTimeout(absolutePath);
            var sizeAfter = Files.size(absolutePath);

            if (sizeBefore != sizeAfter) {
                handleWriteRace(file);
                return null;
            }

            retryCounters.remove(file.pathLower());
            return hash;

        } catch (IOException e) {
            LOG.warn("Cannot read file (skipping): {} - {}", file.path(), e.getMessage());
            return null;
        }
    }

    private String computeWithTimeout(Path absolutePath) {
        Future<String> future = executor.submit(() -> doHash(absolutePath));

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            LOG.warn("Hash timeout after {}s: {}", timeoutSeconds, absolutePath.getFileName());
            return null;
        } catch (ExecutionException e) {
            LOG.warn("Hash failed: {} - {}", absolutePath.getFileName(), e.getCause().getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Hash interrupted: {}", absolutePath.getFileName());
            return null;
        }
    }

    private static String doHash(Path absolutePath) throws IOException {
        try {
            var digest = MessageDigest.getInstance(SHA_256);

            try (InputStream in = Files.newInputStream(absolutePath);
                 DigestInputStream dis = new DigestInputStream(in, digest)) {
                var buffer = new byte[BUFFER_SIZE];
                while (dis.read(buffer) != -1) {
                    // DigestInputStream updates the digest automatically
                }
            }

            return bytesToHex(digest.digest());

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private void handleWriteRace(NormalizedPath file) {
        var retries = retryCounters.merge(file.pathLower(), 1, Integer::sum);

        if (retries <= maxRetries) {
            LOG.warn("Write-race detected (attempt {}/{}): {}", retries, maxRetries, file.path());
        } else {
            LOG.error("Write-race persists after {} attempts: {}", maxRetries, file.path());
        }
    }

    private static String bytesToHex(byte[] bytes) {
        var hex = new StringBuilder(64);
        for (var b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Shuts down the internal executor. Called from the Agent shutdown hook.
     */
    public void shutdown() {
        executor.shutdownNow();
    }
}
