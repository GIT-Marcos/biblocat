package com.biblocat.api.repository;

import com.biblocat.api.entity.Source;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SourceRepository extends JpaRepository<Source, UUID>, JpaSpecificationExecutor<Source> {

    boolean existsByPathLowerAndDeletedAtIsNull(String pathLower);

    boolean existsByPathLowerAndDeletedAtIsNullAndIdNot(String pathLower, UUID id);

    @Query(value = """
            SELECT * FROM (
                SELECT DISTINCT ON (s.path_lower)
                    s.id                    AS id,
                    s.path                  AS path,
                    s.path_lower            AS pathLower,
                    s.content_hash          AS contentHash,
                    s.deleted_at            AS deletedAt
                FROM sources s
                ORDER BY s.path_lower,
                    CASE WHEN s.deleted_at IS NULL THEN 0 ELSE 1 END
            ) paths
            ORDER BY CASE WHEN paths.deletedAt IS NULL THEN 0 ELSE 1 END, paths.pathLower
            """, nativeQuery = true)
    List<PathsProjection> findPathsForReconciliation();

    @Query(value = """
            SELECT s.* FROM sources s
            WHERE s.content_hash = :hash
              AND s.deleted_at IS NOT NULL
            """, nativeQuery = true)
    List<Source> findOrphansByContentHash(@Param("hash") String hash);

    @Query(value = """
            SELECT s.* FROM sources s
            WHERE s.id = :id
              AND s.deleted_at IS NULL
            """, nativeQuery = true)
    Optional<Source> findActiveById(@Param("id") UUID id);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM sources WHERE id = :id", nativeQuery = true)
    void hardDeleteById(@Param("id") UUID id);

    interface PathsProjection {
        UUID getId();

        String getPath();

        String getPathLower();

        String getContentHash();

        Instant getDeletedAt();
    }
}
