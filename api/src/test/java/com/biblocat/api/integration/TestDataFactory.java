package com.biblocat.api.integration;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class TestDataFactory {

    private final JdbcTemplate jdbcTemplate;

    public TestDataFactory(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID insertAuthor(String name) {
        List<UUID> existing = jdbcTemplate.query(
                "SELECT id FROM authors WHERE name = ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class), name);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO authors (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    public UUID insertTag(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tags (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    public void linkTag(UUID sourceId, UUID tagId) {
        jdbcTemplate.update("INSERT INTO source_tags (source_id, tag_id) VALUES (?, ?)", sourceId, tagId);
    }

    public UUID insertSource(String name, String path, String pathLower, String contentHash, String authorName) {
        UUID id = UUID.randomUUID();
        UUID authorId = authorName == null ? null : insertAuthor(authorName);
        jdbcTemplate.update("""
                INSERT INTO sources (id, name, path, path_lower, content_hash, file_format, author_id)
                VALUES (?, ?, ?, ?, ?, 'PDF', ?)
                """, id, name, path, pathLower, contentHash, authorId);
        return id;
    }

    public UUID insertSourceWithMetadata(String name, String path, String pathLower, String contentHash,
                                         Integer year, String edition, String url, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO sources (id, name, path, path_lower, content_hash, file_format,
                                             year, edition, url, deleted_at)
                        VALUES (?, ?, ?, ?, ?, 'PDF', ?, ?, ?, ?)
                        """, id, name, path, pathLower, contentHash, year, edition, url,
                deleted ? Timestamp.from(Instant.now()) : null);
        return id;
    }

    public void softDelete(UUID id) {
        jdbcTemplate.update("UPDATE sources SET deleted_at = now() WHERE id = ?", id);
    }

    public String pathOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT path FROM sources WHERE id = ?", String.class, id);
    }

    public String contentHashOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT content_hash FROM sources WHERE id = ?", String.class, id);
    }

    public Instant deletedAtOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT deleted_at FROM sources WHERE id = ?", Instant.class, id);
    }

    public int countSourcesByName(String name) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sources WHERE name = ?", Integer.class, name);
    }

    public int countAuthorsByName(String name) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM authors WHERE name = ?", Integer.class, name);
    }

    public UUID idOf(String name) {
        return jdbcTemplate.queryForObject("SELECT id FROM sources WHERE name = ?", UUID.class, name);
    }

    public Integer yearOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT year FROM sources WHERE id = ?", Integer.class, id);
    }

    public String editionOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT edition FROM sources WHERE id = ?", String.class, id);
    }

    public String urlOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT url FROM sources WHERE id = ?", String.class, id);
    }

    public String pathLowerOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT path_lower FROM sources WHERE id = ?", String.class, id);
    }

    public int countSourcesByContentHash(String hash) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sources WHERE content_hash = ?", Integer.class, hash);
    }

    public int countTagsByName(String name) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tags WHERE name = ?", Integer.class, name);
    }

    public List<UUID> tagIdsOf(UUID sourceId) {
        return jdbcTemplate.query("SELECT tag_id FROM source_tags WHERE source_id = ?",
                (rs, rowNum) -> rs.getObject("tag_id", UUID.class), sourceId);
    }
}