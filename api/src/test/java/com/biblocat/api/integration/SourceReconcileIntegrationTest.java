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
        UUID rename = data.insertSource("old.pdf", "Autor/old.pdf", "autor/old.pdf", "hash1", "Autor");
        UUID update = data.insertSource("update.pdf", "Autor/update.pdf", "autor/update.pdf", "hashA", "Autor");
        UUID reactivate = data.insertSource("react.pdf", "Autor/react.pdf", "autor/react.pdf", "hashB", "Autor");
        UUID delete = data.insertSource("del.pdf", "Autor/del.pdf", "autor/del.pdf", "hashC", "Autor");
        data.softDelete(reactivate);

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
                "pathLower", "autor/react.pdf",
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

        assertThat(data.pathOf(rename)).isEqualTo("Nuevo Autor/renamed.pdf");
        assertThat(data.contentHashOf(update)).isEqualTo("hashA2");
        assertThat(data.deletedAtOf(reactivate)).isNull();
        assertThat(data.deletedAtOf(delete)).isNotNull();
        assertThat(data.countSourcesByName("nuevo.pdf")).isEqualTo(1);
        assertThat(data.countAuthorsByName("Nuevo Autor")).isEqualTo(1);
    }

    @Test
    void deleteDuplicado_EsIdempotente() throws Exception {
        UUID id = data.insertSource("del.pdf", "Autor/del.pdf", "autor/del.pdf", "hashC", "Autor");

        postReconcile(List.of(op("DELETE", Map.of("sourceId", id))));
        assertThat(data.deletedAtOf(id)).isNotNull();

        String body = postReconcile(List.of(op("DELETE", Map.of("sourceId", id))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.deleted()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        assertThat(data.deletedAtOf(id)).isNotNull();
        assertThat(data.countSourcesByName("del.pdf")).isEqualTo(1);
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
                "MISSING_FORMAT",
                "MISSING_SOURCE_ID",
                "MISSING_CONTENT_HASH",
                "MISSING_PATH",
                "MISSING_PATH_LOWER",
                "MISSING_SOURCE_ID");

        assertThat(data.countSourcesByName("valida.pdf")).isEqualTo(1);
        assertThat(data.countSourcesByName("a.pdf")).isZero();
        assertThat(data.countSourcesByName("b.pdf")).isZero();
        assertThat(data.countSourcesByName("c.pdf")).isZero();
        assertThat(data.countSourcesByName("d.pdf")).isZero();
        assertThat(data.countSourcesByName("e.pdf")).isZero();
    }

    @Test
    void batchMixto_AplicaSoloLasValidas() throws Exception {
        UUID id = data.insertSource("keep.pdf", "Autor/keep.pdf", "autor/keep.pdf", "hashK", "Autor");

        List<Map<String, Object>> ops = new ArrayList<>();
        ops.add(op("UPDATE", Map.of("sourceId", UUID.randomUUID(), "contentHash", "h")));
        ops.add(op("UPDATE", Map.of("sourceId", id, "contentHash", "hashK2")));

        String body = postReconcile(ops);
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.updated()).isEqualTo(1);
        assertThat(response.processed()).isEqualTo(1);
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().error()).isEqualTo("SOURCE_NOT_FOUND");
        assertThat(data.contentHashOf(id)).isEqualTo("hashK2");
    }

    @Test
    void create_conUnOrphanMismoHash_TransfiereMetadatosYPurgaElOrphan() throws Exception {
        UUID orphan = data.insertSourceWithMetadata("old.pdf", "Autor/old.pdf", "autor/old.pdf", "hashM",
                1967, "1ª edición", "https://example.com/libro", true);
        UUID tag = data.insertTag("favorito");
        data.linkTag(orphan, tag);

        String body = postReconcile(List.of(op("CREATE", Map.of(
                "name", "nuevo.pdf",
                "path", "Autor/nuevo.pdf",
                "pathLower", "autor/nuevo.pdf",
                "contentHash", "hashM",
                "fileFormat", "PDF"))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.created()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();

        UUID nuevo = data.idOf("nuevo.pdf");
        assertThat(data.yearOf(nuevo)).isEqualTo(1967);
        assertThat(data.editionOf(nuevo)).isEqualTo("1ª edición");
        assertThat(data.urlOf(nuevo)).isEqualTo("https://example.com/libro");
        assertThat(data.tagIdsOf(nuevo)).containsExactly(tag);
        assertThat(data.countSourcesByContentHash("hashM")).isEqualTo(1);
    }

    @Test
    void create_conDosOrphansMismoHash_NoTransfierePorAmbiguedad() throws Exception {
        data.insertSourceWithMetadata("o1.pdf", "A/o1.pdf", "a/o1.pdf", "hashD", 1901, null, null, true);
        data.insertSourceWithMetadata("o2.pdf", "A/o2.pdf", "a/o2.pdf", "hashD", 1902, null, null, true);

        String body = postReconcile(List.of(op("CREATE", Map.of(
                "name", "nuevo.pdf",
                "path", "A/nuevo.pdf",
                "pathLower", "a/nuevo.pdf",
                "contentHash", "hashD",
                "fileFormat", "PDF"))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.created()).isEqualTo(1);
        UUID nuevo = data.idOf("nuevo.pdf");
        assertThat(data.yearOf(nuevo)).isNull();
        assertThat(data.countSourcesByContentHash("hashD")).isEqualTo(3);
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
        assertThat(data.yearOf(data.idOf("nuevo.pdf"))).isNull();
    }

    @Test
    void create_conAuthorExistenteCasingDistinto_ReutilizaElAutor() throws Exception {
        UUID author = data.insertAuthor("Gabriel García Márquez");

        String body = postReconcile(List.of(op("CREATE", Map.of(
                "name", "libro.pdf",
                "path", "Gabriel García Márquez/libro.pdf",
                "pathLower", "gabriel garcía márquez/libro.pdf",
                "contentHash", "hashA",
                "fileFormat", "PDF",
                "authorName", "gabriel garcía márquez"))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.created()).isEqualTo(1);
        assertThat(data.countAuthorsByName("Gabriel García Márquez")).isEqualTo(1);
        UUID authorOf = jdbcTemplate.queryForObject(
                "SELECT author_id FROM sources WHERE id = ?", UUID.class, data.idOf("libro.pdf"));
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
        assertThat(data.countAuthorsByName("Borges")).isEqualTo(1);
    }

    @Test
    void create_conPathLowerDuplicadoActivo_DuplicatePath() throws Exception {
        data.insertSource("existente.pdf", "Autor/existente.pdf", "autor/existente.pdf", "hashE", "Autor");

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
        assertThat(data.countSourcesByName("duplicado.pdf")).isZero();
    }

    @Test
    void renameSobreSoftDeleted_ReactivaYActualizaPath() throws Exception {
        UUID id = data.insertSourceWithMetadata("old.pdf", "Autor/old.pdf", "autor/old.pdf", "hashR",
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
        assertThat(data.deletedAtOf(id)).isNull();
        assertThat(data.pathOf(id)).isEqualTo("Nuevo/renamed.pdf");
        assertThat(data.yearOf(id)).isEqualTo(2001);
    }

    @Test
    void reactivate_ActualizaPathLowerYPreservaMetadatos() throws Exception {
        UUID id = data.insertSourceWithMetadata("react.pdf", "Autor/react.pdf", "autor/react.pdf", "hashH",
                1999, "2ª edición", null, true);

        String body = postReconcile(List.of(op("REACTIVATE", Map.of(
                "sourceId", id,
                "path", "Otro/react.pdf",
                "pathLower", "otro/react.pdf",
                "contentHash", "hashH2"))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.reactivated()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        assertThat(data.deletedAtOf(id)).isNull();
        assertThat(data.pathLowerOf(id)).isEqualTo("otro/react.pdf");
        assertThat(data.pathOf(id)).isEqualTo("Otro/react.pdf");
        assertThat(data.contentHashOf(id)).isEqualTo("hashH2");
        assertThat(data.yearOf(id)).isEqualTo(1999);
        assertThat(data.editionOf(id)).isEqualTo("2ª edición");
    }

    @Test
    void rename_conPathLowerOcupadoActivo_DuplicatePath() throws Exception {
        UUID target = data.insertSource("a.pdf", "Autor/a.pdf", "autor/a.pdf", "h1", "Autor");
        UUID toMove = data.insertSource("b.pdf", "Autor/b.pdf", "autor/b.pdf", "h2", "Autor");

        String body = postReconcile(List.of(op("RENAME", Map.of(
                "sourceId", toMove,
                "name", "b.pdf",
                "path", "Autor/a.pdf",
                "pathLower", "autor/a.pdf",
                "fileFormat", "PDF"))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.renamed()).isZero();
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().error()).isEqualTo("DUPLICATE_PATH");
        assertThat(data.pathOf(toMove)).isEqualTo("Autor/b.pdf");
    }

    @Test
    void reactivate_conPathLowerOcupadoActivo_DuplicatePath() throws Exception {
        UUID owner = data.insertSource("a.pdf", "Autor/a.pdf", "autor/a.pdf", "h1", "Autor");
        UUID orphan = data.insertSourceWithMetadata("b.pdf", "Autor/b.pdf", "autor/b.pdf", "h2",
                null, null, null, true);
        jdbcTemplate.update("UPDATE sources SET path_lower = 'autor/a.pdf' WHERE id = ?", orphan);

        String body = postReconcile(List.of(op("REACTIVATE", Map.of(
                "sourceId", orphan,
                "path", "Autor/x.pdf",
                "pathLower", "autor/x.pdf",
                "contentHash", "h3"))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.reactivated()).isZero();
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().error()).isEqualTo("DUPLICATE_PATH");
        assertThat(data.deletedAtOf(orphan)).isNotNull();
    }

    @Test
    void create_conFormatoDesconocido_UnsupportedFormatYElRestoProcesa() throws Exception {
        String body = postReconcile(List.of(
                op("CREATE", Map.of(
                        "name", "malo.pdf",
                        "path", "A/malo.pdf",
                        "pathLower", "a/malo.pdf",
                        "contentHash", "hashBad",
                        "fileFormat", "TXT")),
                op("CREATE", Map.of(
                        "name", "bueno.pdf",
                        "path", "A/bueno.pdf",
                        "pathLower", "a/bueno.pdf",
                        "contentHash", "hashGood",
                        "fileFormat", "PDF"))));
        ReconcileResponse response = objectMapper.readValue(body, ReconcileResponse.class);

        assertThat(response.created()).isEqualTo(1);
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().type().name()).isEqualTo("CREATE");
        assertThat(response.errors().getFirst().error()).isEqualTo("UNSUPPORTED_FORMAT");
        assertThat(data.countSourcesByName("bueno.pdf")).isEqualTo(1);
        assertThat(data.countSourcesByName("malo.pdf")).isZero();
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

}
