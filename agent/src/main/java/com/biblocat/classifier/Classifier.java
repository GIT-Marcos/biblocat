package com.biblocat.classifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.biblocat.dto.Operation;
import com.biblocat.dto.SourceState;
import com.biblocat.model.NormalizedPath;
import com.biblocat.model.OperationType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Classifies scanned file-system entries against known API state to produce
 * reconciliation operations.
 *
 * <p>This is the core decision-making phase of the Agent pipeline (agent.md §3.2.2).
 * It receives the list of scanned files (with SHA-256 hashes already computed)
 * and the known source state from the API, then applies the classification table
 * from agent.md §2.5 to determine which operations to emit:</p>
 *
 * <pre>
 *   A  unchanged          → skip
 *   B  reappeared (same hash, soft-deleted) → REACTIVATE
 *   C  modified in place  → UPDATE
 *   D  moved (new path, hash exists elsewhere) → RENAME
 *   E  genuinely new      → CREATE
 *   F  removed from FS    → DELETE
 *   G  already orphan     → skip
 *   H  new content where orphan lived → CREATE (orphan stays)
 * </pre>
 *
 * <p>Files that failed hashing (timeout, too large, write-race) are excluded from
 * classification; they will be retried on the next scan.</p>
 *
 * <p>Duplicates are handled deterministically: when multiple sources share the same
 * content hash (case D), the best candidate is selected by priority: active over
 * soft-deleted, then alphabetically by pathLower (agent.md §2.5). Sources that
 * are renamed within the same scan are excluded from the DELETE pass (EC39).</p>
 *
 * <p>The output list is ordered per API contract (agent.md §2.9):
 * RENAME → UPDATE → REACTIVATE → CREATE → DELETE.</p>
 */
public class Classifier {

    private static final Logger LOG = LogManager.getLogger(Classifier.class);

    private static final Map<OperationType, Integer> TYPE_ORDER = Map.of(
            OperationType.RENAME, 0,
            OperationType.UPDATE, 1,
            OperationType.REACTIVATE, 2,
            OperationType.CREATE, 3,
            OperationType.DELETE, 4
    );

    /**
     * Classifies scanned files against known API sources.
     *
     * @param files   scanned files with contentHash already computed (null = failed)
     * @param sources known source state from GET /api/sources/paths
     * @return operations ordered by API contract, ready for batching
     */
    public static List<Operation> classify(List<NormalizedPath> files, List<SourceState> sources) {
        var validFiles = files.stream()
                .filter(f -> f.contentHash() != null)
                .toList();

        var byPathLower = buildPathIndex(sources);
        var byHash = buildHashIndex(sources);

        var operations = new ArrayList<Operation>();
        var renamedIds = new HashSet<String>();

        for (var file : validFiles) {
            classifyFile(file, byPathLower, byHash, renamedIds, operations);
        }

        findDeletions(sources, validFiles, renamedIds, operations);

        operations.sort(Comparator.comparingInt(o -> TYPE_ORDER.get(o.type())));
        return operations;
    }

    private static Map<String, SourceState> buildPathIndex(List<SourceState> sources) {
        var index = new HashMap<String, SourceState>();
        for (var source : sources) {
            var existing = index.putIfAbsent(source.pathLower(), source);
            if (existing != null) {
                LOG.warn("Duplicate pathLower '{}' in API response, keeping first (sourceId={}), ignoring (sourceId={})",
                        source.pathLower(), existing.id(), source.id());
            }
        }
        return index;
    }

    private static Map<String, List<SourceState>> buildHashIndex(List<SourceState> sources) {
        var index = new HashMap<String, List<SourceState>>();
        for (var source : sources) {
            index.computeIfAbsent(source.contentHash(), k -> new ArrayList<>()).add(source);
        }
        return index;
    }

    private static void classifyFile(
            NormalizedPath file,
            Map<String, SourceState> byPathLower,
            Map<String, List<SourceState>> byHash,
            Set<String> renamedIds,
            List<Operation> operations
    ) {
        var sourceByPath = byPathLower.get(file.pathLower());

        if (sourceByPath != null) {
            classifyMatchedByPath(file, sourceByPath, operations);
        } else {
            classifyUnmatchedByPath(file, byHash, renamedIds, operations);
        }
    }

    private static void classifyMatchedByPath(NormalizedPath file, SourceState source, List<Operation> operations) {
        var hashMatch = source.contentHash().equals(file.contentHash());
        var deleted = source.deletedAt() != null;

        if (hashMatch && !deleted) {
            LOG.debug("Case A — unchanged, skip: {}", file.path());
        } else if (hashMatch) {
            LOG.info("Case B — REACTIVATE: {}", file.path());
            operations.add(Operation.reactivate(source.id(), file.path(), file.contentHash()));
        } else if (!deleted) {
            LOG.info("Case C — UPDATE: {}", file.path());
            operations.add(Operation.update(source.id(), file.contentHash()));
        } else {
            LOG.info("Case H — CREATE (orphan stays orphan): {}", file.path());
            addCreate(file, operations);
        }
    }

    private static void classifyUnmatchedByPath(
            NormalizedPath file,
            Map<String, List<SourceState>> byHash,
            Set<String> renamedIds,
            List<Operation> operations
    ) {
        var candidates = byHash.getOrDefault(file.contentHash(), List.of());
        var bestMatch = selectBestMatch(candidates, renamedIds);

        if (bestMatch != null) {
            LOG.info("Case D — RENAME: {} <- {}", file.path(), bestMatch.path());
            renamedIds.add(bestMatch.id());
            operations.add(Operation.rename(
                    bestMatch.id(),
                    fileName(file.path()),
                    file.path(),
                    file.pathLower(),
                    file.fileFormat(),
                    inferAuthor(file.path())
            ));
        } else {
            LOG.info("Case E — CREATE: {}", file.path());
            addCreate(file, operations);
        }
    }

    private static void addCreate(NormalizedPath file, List<Operation> operations) {
        operations.add(Operation.create(
                fileName(file.path()),
                file.path(),
                file.pathLower(),
                file.contentHash(),
                file.fileFormat(),
                inferAuthor(file.path())
        ));
    }

    private static void findDeletions(
            List<SourceState> sources,
            List<NormalizedPath> validFiles,
            Set<String> renamedIds,
            List<Operation> operations
    ) {
        var scannedPaths = validFiles.stream()
                .map(NormalizedPath::pathLower)
                .collect(Collectors.toSet());

        for (var source : sources) {
            if (source.deletedAt() != null) {
                continue;
            }
            if (renamedIds.contains(source.id())) {
                continue;
            }
            if (scannedPaths.contains(source.pathLower())) {
                continue;
            }

            LOG.info("Case F — DELETE: {}", source.path());
            operations.add(Operation.delete(source.id()));
        }
    }

    static SourceState selectBestMatch(List<SourceState> candidates, Set<String> excludedIds) {
        return candidates.stream()
                .filter(s -> !excludedIds.contains(s.id()))
                .min(Comparator
                        .comparingInt((SourceState s) -> s.deletedAt() != null ? 1 : 0)
                        .thenComparing(SourceState::pathLower))
                .orElse(null);
    }

    static String inferAuthor(String relativePath) {
        var idx = relativePath.indexOf('/');
        return idx == -1 ? null : relativePath.substring(0, idx).strip();
    }

    static String fileName(String relativePath) {
        var idx = relativePath.lastIndexOf('/');
        return idx == -1 ? relativePath : relativePath.substring(idx + 1);
    }
}
