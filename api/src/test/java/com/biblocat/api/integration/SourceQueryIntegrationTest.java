package com.biblocat.api.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SourceQueryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void paths_ActivosAntesQueOrphans() throws Exception {
        UUID active1 = data.insertSource("a.pdf", "Autor/a.pdf", "autor/a.pdf", "h1", "Autor");
        UUID orphan = data.insertSource("b.pdf", "Autor/b.pdf", "autor/b.pdf", "h2", "Autor");
        UUID active2 = data.insertSource("c.pdf", "Autor/c.pdf", "autor/c.pdf", "h3", "Autor");
        data.softDelete(orphan);

        String body = mvc.get().uri("/api/sources/paths").exchange().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.isArray()).isTrue();
        List<String> ids = new ArrayList<>();
        json.forEach(node -> ids.add(node.get("id").asString()));
        assertThat(ids).containsExactly(active1.toString(), active2.toString(), orphan.toString());
    }

    @Test
    void paths_UnicidadPathLower_OrphanOmitidoCuandoCompartePathLowerConActivo() throws Exception {
        UUID active = data.insertSource("libro.pdf", "Autor/libro.pdf", "autor/libro.pdf", "h1", "Autor");
        UUID orphan = data.insertSourceWithMetadata("libro-copia.pdf", "Autor/libro-copia.pdf", "autor/libro.pdf", "h2",
                null, null, null, true);

        String body = mvc.get().uri("/api/sources/paths").exchange().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.size()).isEqualTo(1);
        assertThat(json.get(0).get("id").asString()).isEqualTo(active.toString());
        assertThat(json.get(0).get("pathLower").asString()).isEqualTo("autor/libro.pdf");
    }

    @Test
    void list_SinDatos_ContratoExactoDeCincoCampos() throws Exception {
        String body = mvc.get().uri("/api/sources").exchange().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.get("content").isArray()).isTrue();
        assertThat(json.get("content").size()).isZero();
        assertThat(json.get("page").asInt()).isZero();
        assertThat(json.get("size").asInt()).isEqualTo(20);
        assertThat(json.get("totalElements").asLong()).isZero();
        assertThat(json.get("totalPages").asInt()).isZero();
        assertThat(json.has("pageable")).isFalse();
        assertThat(json.has("sort")).isFalse();
        assertThat(json.has("first")).isFalse();
        assertThat(json.has("last")).isFalse();
    }

    @Test
    void list_FiltroQ_CoincidePorNombreUrlYAutor_CaseInsensitive() throws Exception {
        data.insertSource("cien-anios.pdf", "García Márquez/cien-anios.pdf", "garcía márquez/cien-anios.pdf",
                "h1", "Gabriel García Márquez");
        data.insertSource("ficciones.pdf", "Borges/ficciones.pdf", "borges/ficciones.pdf", "h2", "Jorge Luis Borges");
        data.insertSource("otros.pdf", "Otros/otros.pdf", "otros/otros.pdf", "h3", null);
        jdbcTemplate.update("UPDATE sources SET url = 'https://ejemplo.com/cien' WHERE name = 'cien-anios.pdf'");

        String body = mvc.get().uri("/api/sources").queryParam("q", "CIEN").exchange()
                .getResponse().getContentAsString();
        assertThat(namesOf(objectMapper.readTree(body))).containsExactly("cien-anios.pdf");

        body = mvc.get().uri("/api/sources").queryParam("q", "borges").exchange()
                .getResponse().getContentAsString();
        assertThat(namesOf(objectMapper.readTree(body))).containsExactly("ficciones.pdf");

        body = mvc.get().uri("/api/sources").queryParam("q", "ejemplo.com/cien").exchange()
                .getResponse().getContentAsString();
        assertThat(namesOf(objectMapper.readTree(body))).containsExactly("cien-anios.pdf");
    }

    @Test
    void list_FiltroAuthorId() throws Exception {
        UUID author = data.insertAuthor("Gabriel García Márquez");
        data.insertSource("a.pdf", "García Márquez/a.pdf", "garcía márquez/a.pdf", "h1", "Gabriel García Márquez");
        data.insertSource("b.pdf", "Borges/b.pdf", "borges/b.pdf", "h2", "Jorge Luis Borges");

        String body = mvc.get().uri("/api/sources").queryParam("authorId", author.toString()).exchange()
                .getResponse().getContentAsString();
        assertThat(namesOf(objectMapper.readTree(body))).containsExactly("a.pdf");
    }

    @Test
    void list_FiltroTagId() throws Exception {
        UUID source = data.insertSource("a.pdf", "Autor/a.pdf", "autor/a.pdf", "h1", "Autor");
        data.insertSource("b.pdf", "Autor/b.pdf", "autor/b.pdf", "h2", "Autor");
        UUID tag = data.insertTag("favorito");
        data.linkTag(source, tag);

        String body = mvc.get().uri("/api/sources").queryParam("tagId", tag.toString()).exchange()
                .getResponse().getContentAsString();
        assertThat(namesOf(objectMapper.readTree(body))).containsExactly("a.pdf");
    }

    @Test
    void list_FiltroFormat() throws Exception {
        data.insertSource("a.pdf", "Autor/a.pdf", "autor/a.pdf", "h1", "Autor");
        jdbcTemplate.update("""
                INSERT INTO sources (id, name, path, path_lower, content_hash, file_format)
                VALUES (?, 'a.epub', 'Autor/a.epub', 'autor/a.epub', 'h2', 'EPUB')
                """, UUID.randomUUID());

        String body = mvc.get().uri("/api/sources").queryParam("format", "EPUB").exchange()
                .getResponse().getContentAsString();
        assertThat(namesOf(objectMapper.readTree(body))).containsExactly("a.epub");
    }

    @Test
    void list_IncludeDeleted_ExcluyeOrphansPorDefectoYLosIncluyeConTrue() throws Exception {
        data.insertSource("a.pdf", "Autor/a.pdf", "autor/a.pdf", "h1", "Autor");
        UUID orphan = data.insertSource("b.pdf", "Autor/b.pdf", "autor/b.pdf", "h2", "Autor");
        data.softDelete(orphan);

        String body = mvc.get().uri("/api/sources").exchange().getResponse().getContentAsString();
        assertThat(namesOf(objectMapper.readTree(body))).containsExactly("a.pdf");

        body = mvc.get().uri("/api/sources").queryParam("includeDeleted", "true").exchange()
                .getResponse().getContentAsString();
        assertThat(namesOf(objectMapper.readTree(body))).containsExactlyInAnyOrder("a.pdf", "b.pdf");
    }

    @Test
    void list_SortAuthorName_LeftJoinIncluyeSourcesSinAutor_NullsAlFinalEnAsc() throws Exception {
        data.insertSource("z.pdf", "Zoe/z.pdf", "zoe/z.pdf", "h1", "Zoe");
        data.insertSource("a.pdf", "Ana/a.pdf", "ana/a.pdf", "h2", "Ana");
        data.insertSource("n.pdf", "Raiz/n.pdf", "raiz/n.pdf", "h3", null);

        String body = mvc.get().uri("/api/sources").queryParam("sort", "author.name,asc").exchange()
                .getResponse().getContentAsString();
        assertThat(namesOf(objectMapper.readTree(body))).containsExactly("a.pdf", "z.pdf", "n.pdf");
    }

    @Test
    void list_SortInvalido_400Rfc9457() throws Exception {
        String body = mvc.get().uri("/api/sources").queryParam("sort", "campoInexistente,asc").exchange()
                .getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.get("status").asInt()).isEqualTo(400);
        assertThat(json.get("title").asString()).isEqualTo("Invalid Sort Field");
    }

    @Test
    void list_ClampTamanioMaximo_500SeReduceAUno() throws Exception {
        for (int i = 0; i < 3; i++) {
            data.insertSource("f" + i + ".pdf", "Autor/f" + i + ".pdf", "autor/f" + i + ".pdf", "h" + i, "Autor");
        }

        String body = mvc.get().uri("/api/sources").queryParam("size", "500").exchange()
                .getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.get("content").size()).isEqualTo(3);
        assertThat(json.get("size").asInt()).isEqualTo(100);
        assertThat(json.get("totalElements").asLong()).isEqualTo(3);
        assertThat(json.get("totalPages").asInt()).isEqualTo(1);
    }

    @Test
    void list_PaginacionReal_OffsetSizeYTotalPages() throws Exception {
        for (int i = 0; i < 5; i++) {
            data.insertSource("f" + i + ".pdf", "Autor/f" + i + ".pdf", "autor/f" + i + ".pdf", "h" + i, "Autor");
        }

        String body = mvc.get().uri("/api/sources").queryParam("size", "2").queryParam("page", "1").exchange()
                .getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.get("content").size()).isEqualTo(2);
        assertThat(json.get("page").asInt()).isEqualTo(1);
        assertThat(json.get("totalElements").asLong()).isEqualTo(5);
        assertThat(json.get("totalPages").asInt()).isEqualTo(3);
    }

    @Test
    void list_PaginaMasAllaDeTotalPages_ContentVacio() throws Exception {
        data.insertSource("a.pdf", "Autor/a.pdf", "autor/a.pdf", "h1", "Autor");

        String body = mvc.get().uri("/api/sources").queryParam("page", "5").exchange()
                .getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.get("content").size()).isZero();
        assertThat(json.get("page").asInt()).isEqualTo(5);
        assertThat(json.get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void getById_Existente_DevuelveContrato() throws Exception {
        UUID id = data.insertSource("a.pdf", "Autor/a.pdf", "autor/a.pdf", "h1", "Autor");

        String body = mvc.get().uri("/api/sources/" + id).exchange().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.get("id").asString()).isEqualTo(id.toString());
        assertThat(json.get("name").asString()).isEqualTo("a.pdf");
        assertThat(json.get("path").asString()).isEqualTo("Autor/a.pdf");
        assertThat(json.has("contentHash")).isFalse();
        assertThat(json.get("fileFormat").asString()).isEqualTo("PDF");
        assertThat(json.has("deletedAt")).isFalse();
        assertThat(json.get("author").get("name").asString()).isEqualTo("Autor");
    }

    @Test
    void getById_Inexistente_404Rfc9457() throws Exception {
        String body = mvc.get().uri("/api/sources/" + UUID.randomUUID()).exchange()
                .getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.get("status").asInt()).isEqualTo(404);
        assertThat(json.get("title").asString()).isEqualTo("Source Not Found");
    }

    @Test
    void getById_OrphanPorDefecto404_YConIncludeDeleted200() throws Exception {
        UUID id = data.insertSource("a.pdf", "Autor/a.pdf", "autor/a.pdf", "h1", "Autor");
        data.softDelete(id);

        assertThat(mvc.get().uri("/api/sources/" + id).exchange()).hasStatus(HttpStatus.NOT_FOUND);

        String body = mvc.get().uri("/api/sources/" + id).queryParam("includeDeleted", "true").exchange()
                .getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("id").asString()).isEqualTo(id.toString());
    }

    @Test
    void patch_ActualizaCamposPersistidos() throws Exception {
        UUID id = data.insertSource("a.pdf", "Autor/a.pdf", "autor/a.pdf", "h1", "Autor");

        String request = """
                {"year": 2024, "edition": "2a", "url": "https://x.com/a"}
                """;
        assertThat(mvc.patch().uri("/api/sources/" + id).contentType("application/json").content(request).exchange()).hasStatusOk();

        assertThat(queryForString("SELECT name FROM sources WHERE id = ?", id)).isEqualTo("a.pdf");
        assertThat(queryForInt("SELECT year FROM sources WHERE id = ?", id)).isEqualTo(2024);
        assertThat(queryForString("SELECT edition FROM sources WHERE id = ?", id)).isEqualTo("2a");
        assertThat(queryForString("SELECT url FROM sources WHERE id = ?", id)).isEqualTo("https://x.com/a");
    }

    @Test
    void patch_EnvioDeNull_LimpiaElCampoEnBd() throws Exception {
        UUID id = data.insertSource("a.pdf", "Autor/a.pdf", "autor/a.pdf", "h1", "Autor");
        jdbcTemplate.update("UPDATE sources SET year = 2024, url = 'https://x.com/a' WHERE id = ?", id);

        String request = """
                {"year": null, "url": null}
                """;
        assertThat(mvc.patch().uri("/api/sources/" + id).contentType("application/json").content(request).exchange()).hasStatusOk();

        assertThat(jdbcTemplate.queryForObject("SELECT year FROM sources WHERE id = ?", Integer.class, id))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT url FROM sources WHERE id = ?", String.class, id))
                .isNull();
    }

    @Test
    void patch_Inexistente_404() throws Exception {
        String request = """
                {"name": "x.pdf"}
                """;
        assertThat(mvc.patch().uri("/api/sources/" + UUID.randomUUID()).contentType("application/json").content(request).exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void purge_Orphan_204SinFilaYSinOrphan() throws Exception {
        UUID id = data.insertSource("a.pdf", "Autor/a.pdf", "autor/a.pdf", "h1", "Autor");
        data.softDelete(id);

        assertThat(mvc.delete().uri("/api/sources/" + id).exchange()).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sources WHERE id = ?", Integer.class, id))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_tags WHERE source_id = ?",
                Integer.class, id)).isZero();
    }

    @Test
    void purge_SourceActivo_409() throws Exception {
        UUID id = data.insertSource("a.pdf", "Autor/a.pdf", "autor/a.pdf", "h1", "Autor");

        assertThat(mvc.delete().uri("/api/sources/" + id).exchange()).hasStatus(HttpStatus.CONFLICT);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sources WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void purge_Inexistente_404() throws Exception {
        assertThat(mvc.delete().uri("/api/sources/" + UUID.randomUUID()).exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void putTags_ReemplazaConjuntoCompleto() throws Exception {
        UUID source = data.insertSource("a.pdf", "Autor/a.pdf", "autor/a.pdf", "h1", "Autor");
        UUID t1 = data.insertTag("favorito");
        UUID t2 = data.insertTag("pendiente");
        data.linkTag(source, t1);
        data.linkTag(source, t2);

        UUID t3 = data.insertTag("leido");
        String request = """
                {"tagIds": ["%s", "%s"]}
                """.formatted(t2, t3);
        assertThat(mvc.put().uri("/api/sources/" + source + "/tags").contentType("application/json").content(request).exchange()).hasStatusOk();

        List<UUID> linked = jdbcTemplate.query("SELECT tag_id FROM source_tags WHERE source_id = ?",
                (rs, rowNum) -> rs.getObject("tag_id", UUID.class), source);
        assertThat(linked).containsExactlyInAnyOrder(t2, t3);
    }

    @Test
    void putTags_VacioEliminaTodos() throws Exception {
        UUID source = data.insertSource("a.pdf", "Autor/a.pdf", "autor/a.pdf", "h1", "Autor");
        UUID t1 = data.insertTag("favorito");
        data.linkTag(source, t1);

        String request = """
                {"tagIds": []}
                """;
        assertThat(mvc.put().uri("/api/sources/" + source + "/tags").contentType("application/json").content(request).exchange()).hasStatusOk();

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_tags WHERE source_id = ?",
                Integer.class, source)).isZero();
    }

    @Test
    void putTags_SourceInexistente_404() throws Exception {
        String request = """
                {"tagIds": []}
                """;
        assertThat(mvc.put().uri("/api/sources/" + UUID.randomUUID() + "/tags").contentType("application/json")
                .content(request).exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }

    private String queryForString(String sql, UUID id) {
        return jdbcTemplate.queryForObject(sql, String.class, id);
    }

    private Integer queryForInt(String sql, UUID id) {
        return jdbcTemplate.queryForObject(sql, Integer.class, id);
    }

    private List<String> namesOf(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("content").forEach(node -> names.add(node.get("name").asString()));
        return names;
    }
}
