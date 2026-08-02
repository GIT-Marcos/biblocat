package com.biblocat.api.integration;

import com.biblocat.api.dto.response.OperationError;
import com.biblocat.api.dto.response.ReconcileResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class SourceReconcileIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void batchConLosCincoTipos_ProcesaEnOrdenCanonico() throws Exception {
        UUID rename = insertSource("old.pdf", "Autor/old.pdf", "autor/old.pdf", "hash1", "Autor");
        UUID update = insertSource("update.pdf", "Autor/update.pdf", "autor/update.pdf", "hashA", "Autor");
        UUID reactivate = insertSource("react.pdf", "Autor/react.pdf", "autor/react.pdf", "hashB", "Autor");
        UUID delete = insertSource("del.pdf", "Autor/del.pdf", "autor/del.pdf", "hashC", "Autor");
        jdbcTemplate.update("UPDATE sources SET deleted_at = now() WHERE id = ?", reactivate);

        List<Map<String, Object>> ops = new ArrayList<>();
        ops.add(op("DELETE", Map.of("sourceId", delete)));
        ops.add(op("CREATE", Map.of(
                "name", "nuevo.pdf",
                "path", "Nuevo Autor/nuevo.pdf",
                "pathLower", "nuevo autor/nuevo.pdf",
                "contentHash", "hashN",
                "fileFormat", "PDF",
                "authorName", "Nuevo Autor")));
        ops.add(op("REACTIVATE", Map.of(
                "sourceId", reactivate,
                "path", "Autor/react.pdf",
                "contentHash", "hashB2")));
        ops.add(op("UPDATE", Map.of("sourceId", update, "contentHash", "hashA2")));
        ops.add(op("RENAME", Map.of(
                "sourceId", rename,
                "name", "renamed.pdf",
                "path", "Nuevo Autor/renamed.pdf",
                "pathLower", "nuevo autor/renamed.pdf",
                "fileFormat", "PDF",
                "authorName", "Nuevo Autor")));

        String body = postReconcile(ops);
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.processed()).isEqualTo(5);
        assertThat(response.created()).isEqualTo(1);
        assertThat(response.renamed()).isEqualTo(1);
        assertThat(response.updated()).isEqualTo(1);
        assertThat(response.deleted()).isEqualTo(1);
        assertThat(response.reactivated()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();

        assertThat(pathOf(rename)).isEqualTo("Nuevo Autor/renamed.pdf");
        assertThat(contentHashOf(update)).isEqualTo("hashA2");
        assertThat(deletedAtOf(reactivate)).isNull();
        assertThat(deletedAtOf(delete)).isNotNull();
        assertThat(countSourcesByName("nuevo.pdf")).isEqualTo(1);
        assertThat(countAuthorsByName("Nuevo Autor")).isEqualTo(1);
    }

    @Test
    void deleteDuplicado_EsIdempotente() throws Exception {
        UUID id = insertSource("del.pdf", "Autor/del.pdf", "autor/del.pdf", "hashC", "Autor");

        postReconcile(List.of(op("DELETE", Map.of("sourceId", id))));
        assertThat(deletedAtOf(id)).isNotNull();

        String body = postReconcile(List.of(op("DELETE", Map.of("sourceId", id))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.deleted()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        assertThat(deletedAtOf(id)).isNotNull();
        assertThat(countSourcesByName("del.pdf")).isEqualTo(1);
    }

    @Test
    void updateConSourceIdInexistente_RespondeSourceNotFound() throws Exception {
        UUID missing = UUID.randomUUID();

        String body = postReconcile(List.of(op("UPDATE", Map.of("sourceId", missing, "contentHash", "hashX"))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.updated()).isZero();
        assertThat(response.processed()).isZero();
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().error()).isEqualTo("SOURCE_NOT_FOUND");
        assertThat(response.errors().getFirst().type().name()).isEqualTo("UPDATE");
    }

    @Test
    void matrizDeCamposRequeridos_RespondeCodigoPorOperacion() throws Exception {
        List<Map<String, Object>> ops = new ArrayList<>();
        ops.add(op("CREATE", Map.of("path", "a.pdf", "pathLower", "a.pdf", "contentHash", "h", "fileFormat", "PDF")));
        ops.add(op("CREATE", Map.of("name", "b.pdf", "pathLower", "b.pdf", "contentHash", "h", "fileFormat", "PDF")));
        ops.add(op("CREATE", Map.of("name", "c.pdf", "path", "c.pdf", "contentHash", "h", "fileFormat", "PDF")));
        ops.add(op("CREATE", Map.of("name", "d.pdf", "path", "d.pdf", "pathLower", "d.pdf", "fileFormat", "PDF")));
        ops.add(op("CREATE", Map.of("name", "e.pdf", "path", "e.pdf", "pathLower", "e.pdf", "contentHash", "h")));
        ops.add(op("RENAME", Map.of("name", "f.pdf", "path", "f.pdf", "pathLower", "f.pdf", "fileFormat", "PDF")));
        ops.add(op("UPDATE", Map.of("sourceId", UUID.randomUUID())));
        ops.add(op("REACTIVATE", Map.of("sourceId", UUID.randomUUID(), "contentHash", "h")));
        ops.add(op("REACTIVATE", Map.of("sourceId", UUID.randomUUID(), "path", "g.pdf")));
        ops.add(op("DELETE", Map.of()));
        ops.add(op("CREATE", Map.of(
                "name", "valida.pdf",
                "path", "valida.pdf",
                "pathLower", "valida.pdf",
                "contentHash", "hashV",
                "fileFormat", "PDF")));

        String body = postReconcile(ops);
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.created()).isEqualTo(1);
        assertThat(response.processed()).isEqualTo(1);
        assertThat(response.errors()).hasSize(10);

        List<String> codes = response.errors().stream().map(OperationError::error).toList();
        assertThat(codes).containsExactlyInAnyOrder(
                "MISSING_NAME",
                "MISSING_PATH",
                "MISSING_PATH_LOWER",
                "MISSING_CONTENT_HASH",
                "UNSUPPORTED_FORMAT",
                "MISSING_SOURCE_ID",
                "MISSING_CONTENT_HASH",
                "MISSING_PATH",
                "MISSING_CONTENT_HASH",
                "MISSING_SOURCE_ID");

        assertThat(countSourcesByName("valida.pdf")).isEqualTo(1);
        assertThat(countSourcesByName("a.pdf")).isZero();
        assertThat(countSourcesByName("b.pdf")).isZero();
        assertThat(countSourcesByName("c.pdf")).isZero();
        assertThat(countSourcesByName("d.pdf")).isZero();
        assertThat(countSourcesByName("e.pdf")).isZero();
    }

    @Test
    void batchMixto_AplicaSoloLasValidas() throws Exception {
        UUID id = insertSource("keep.pdf", "Autor/keep.pdf", "autor/keep.pdf", "hashK", "Autor");

        List<Map<String, Object>> ops = new ArrayList<>();
        ops.add(op("UPDATE", Map.of("sourceId", UUID.randomUUID(), "contentHash", "h")));
        ops.add(op("UPDATE", Map.of("sourceId", id, "contentHash", "hashK2")));

        String body = postReconcile(ops);
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.updated()).isEqualTo(1);
        assertThat(response.processed()).isEqualTo(1);
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().error()).isEqualTo("SOURCE_NOT_FOUND");
        assertThat(contentHashOf(id)).isEqualTo("hashK2");
    }

    @Test
    void create_conUnOrphanMismoHash_TransfiereMetadatosYPurgaElOrphan() throws Exception {
        UUID orphan = insertSourceWithMetadata("old.pdf", "Autor/old.pdf", "autor/old.pdf", "hashM",
                1967, "1ª edición", "https://example.com/libro", true);
        UUID tag = insertTag("favorito");
        linkTag(orphan, tag);

        String body = postReconcile(List.of(op("CREATE", Map.of(
                "name", "nuevo.pdf",
                "path", "Autor/nuevo.pdf",
                "pathLower", "autor/nuevo.pdf",
                "contentHash", "hashM",
                "fileFormat", "PDF"))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.created()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();

        UUID nuevo = idOf("nuevo.pdf");
        assertThat(yearOf(nuevo)).isEqualTo(1967);
        assertThat(editionOf(nuevo)).isEqualTo("1ª edición");
        assertThat(urlOf(nuevo)).isEqualTo("https://example.com/libro");
        assertThat(tagIdsOf(nuevo)).containsExactly(tag);
        assertThat(countSourcesByContentHash("hashM")).isEqualTo(1);
    }

    @Test
    void create_conDosOrphansMismoHash_NoTransfierePorAmbiguedad() throws Exception {
        insertSourceWithMetadata("o1.pdf", "A/o1.pdf", "a/o1.pdf", "hashD", 1901, null, null, true);
        insertSourceWithMetadata("o2.pdf", "A/o2.pdf", "a/o2.pdf", "hashD", 1902, null, null, true);

        String body = postReconcile(List.of(op("CREATE", Map.of(
                "name", "nuevo.pdf",
                "path", "A/nuevo.pdf",
                "pathLower", "a/nuevo.pdf",
                "contentHash", "hashD",
                "fileFormat", "PDF"))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.created()).isEqualTo(1);
        UUID nuevo = idOf("nuevo.pdf");
        assertThat(yearOf(nuevo)).isNull();
        assertThat(countSourcesByContentHash("hashD")).isEqualTo(3);
    }

    @Test
    void create_sinOrphansConEseHash_NoTransfiere() throws Exception {
        String body = postReconcile(List.of(op("CREATE", Map.of(
                "name", "nuevo.pdf",
                "path", "A/nuevo.pdf",
                "pathLower", "a/nuevo.pdf",
                "contentHash", "hashN",
                "fileFormat", "PDF"))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.created()).isEqualTo(1);
        assertThat(yearOf(idOf("nuevo.pdf"))).isNull();
    }

    @Test
    void create_conAuthorExistenteCasingDistinto_ReutilizaElAutor() throws Exception {
        UUID author = insertAuthor("Gabriel García Márquez");

        String body = postReconcile(List.of(op("CREATE", Map.of(
                "name", "libro.pdf",
                "path", "Gabriel García Márquez/libro.pdf",
                "pathLower", "gabriel garcía márquez/libro.pdf",
                "contentHash", "hashA",
                "fileFormat", "PDF",
                "authorName", "gabriel garcía márquez"))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.created()).isEqualTo(1);
        assertThat(countAuthorsByName("Gabriel García Márquez")).isEqualTo(1);
        UUID authorOf = jdbcTemplate.queryForObject(
                "SELECT author_id FROM sources WHERE id = ?", UUID.class, idOf("libro.pdf"));
        assertThat(authorOf).isEqualTo(author);
    }

    @Test
    void create_conAuthorNuevo_CreaElAutor() throws Exception {
        String body = postReconcile(List.of(op("CREATE", Map.of(
                "name", "ficciones.pdf",
                "path", "Borges/ficciones.pdf",
                "pathLower", "borges/ficciones.pdf",
                "contentHash", "hashB",
                "fileFormat", "PDF",
                "authorName", "Borges"))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.created()).isEqualTo(1);
        assertThat(countAuthorsByName("Borges")).isEqualTo(1);
    }

    @Test
    void create_conPathLowerDuplicadoActivo_DuplicatePath() throws Exception {
        insertSource("existente.pdf", "Autor/existente.pdf", "autor/existente.pdf", "hashE", "Autor");

        String body = postReconcile(List.of(op("CREATE", Map.of(
                "name", "duplicado.pdf",
                "path", "Autor/duplicado.pdf",
                "pathLower", "autor/existente.pdf",
                "contentHash", "hashX",
                "fileFormat", "PDF"))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.created()).isZero();
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().error()).isEqualTo("DUPLICATE_PATH");
        assertThat(countSourcesByName("duplicado.pdf")).isZero();
    }

    @Test
    void renameSobreSoftDeleted_ReactivaYActualizaPath() throws Exception {
        UUID id = insertSourceWithMetadata("old.pdf", "Autor/old.pdf", "autor/old.pdf", "hashR",
                2001, null, null, true);

        String body = postReconcile(List.of(op("RENAME", Map.of(
                "sourceId", id,
                "name", "renamed.pdf",
                "path", "Nuevo/renamed.pdf",
                "pathLower", "nuevo/renamed.pdf",
                "fileFormat", "PDF"))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.renamed()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        assertThat(deletedAtOf(id)).isNull();
        assertThat(pathOf(id)).isEqualTo("Nuevo/renamed.pdf");
        assertThat(yearOf(id)).isEqualTo(2001);
    }

    @Test
    void reactivate_NoModificaPathLowerYPreservaMetadatos() throws Exception {
        UUID id = insertSourceWithMetadata("react.pdf", "Autor/react.pdf", "autor/react.pdf", "hashH",
                1999, "2ª edición", null, true);

        String body = postReconcile(List.of(op("REACTIVATE", Map.of(
                "sourceId", id,
                "path", "Otro/react.pdf",
                "contentHash", "hashH2"))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.reactivated()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        assertThat(deletedAtOf(id)).isNull();
        assertThat(pathLowerOf(id)).isEqualTo("autor/react.pdf");
        assertThat(pathOf(id)).isEqualTo("Otro/react.pdf");
        assertThat(contentHashOf(id)).isEqualTo("hashH2");
        assertThat(yearOf(id)).isEqualTo(1999);
        assertThat(editionOf(id)).isEqualTo("2ª edición");
    }

    private String postReconcile(List<Map<String, Object>> operations) throws Exception {
        String json = objectMapper.writeValueAsString(Map.of("operations", operations));
        return mvc.post().uri("/api/sources/reconcile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange()
                .getResponse()
                .getContentAsString();
    }

    private static Map<String, Object> op(String type, Map<String, Object> fields) {
        Map<String, Object> map = new HashMap<>(fields);
        map.put("type", type);
        return map;
    }

    private UUID insertAuthor(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO authors (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private UUID insertSource(String name, String path, String pathLower, String contentHash, String authorName) {
        UUID id = UUID.randomUUID();
        UUID authorId = authorName == null ? null : insertAuthor(authorName);
        jdbcTemplate.update("""
                INSERT INTO sources (id, name, path, path_lower, content_hash, file_format, author_id)
                VALUES (?, ?, ?, ?, ?, 'PDF', ?)
                """, id, name, path, pathLower, contentHash, authorId);
        return id;
    }

    private String pathOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT path FROM sources WHERE id = ?", String.class, id);
    }

    private String contentHashOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT content_hash FROM sources WHERE id = ?", String.class, id);
    }

    private java.time.Instant deletedAtOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT deleted_at FROM sources WHERE id = ?", java.time.Instant.class, id);
    }

    private int countSourcesByName(String name) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sources WHERE name = ?", Integer.class, name);
    }

    private int countAuthorsByName(String name) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM authors WHERE name = ?", Integer.class, name);
    }

    private UUID insertSourceWithMetadata(String name, String path, String pathLower, String contentHash,
                                          Integer year, String edition, String url, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO sources (id, name, path, path_lower, content_hash, file_format,
                                             year, edition, url, deleted_at)
                        VALUES (?, ?, ?, ?, ?, 'PDF', ?, ?, ?, ?)
                        """, id, name, path, pathLower, contentHash, year, edition, url,
                deleted ? java.time.Instant.now() : null);
        return id;
    }

    private UUID insertTag(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tags (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private void linkTag(UUID sourceId, UUID tagId) {
        jdbcTemplate.update("INSERT INTO source_tags (source_id, tag_id) VALUES (?, ?)", sourceId, tagId);
    }

    private UUID idOf(String name) {
        return jdbcTemplate.queryForObject("SELECT id FROM sources WHERE name = ?", UUID.class, name);
    }

    private Integer yearOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT year FROM sources WHERE id = ?", Integer.class, id);
    }

    private String editionOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT edition FROM sources WHERE id = ?", String.class, id);
    }

    private String urlOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT url FROM sources WHERE id = ?", String.class, id);
    }

    private String pathLowerOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT path_lower FROM sources WHERE id = ?", String.class, id);
    }

    private int countSourcesByContentHash(String hash) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sources WHERE content_hash = ?", Integer.class, hash);
    }

    private List<UUID> tagIdsOf(UUID sourceId) {
        return jdbcTemplate.query("SELECT tag_id FROM source_tags WHERE source_id = ?",
                (rs, rowNum) -> rs.getObject("tag_id", UUID.class), sourceId);
    }
}
