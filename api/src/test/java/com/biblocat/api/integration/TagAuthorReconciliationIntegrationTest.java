package com.biblocat.api.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TagAuthorReconciliationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void tags_ListVacio_DevuelveArrayVacio() throws Exception {
        String body = mvc.get().uri("/api/tags").exchange().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.isArray()).isTrue();
        assertThat(json.size()).isZero();
    }

    @Test
    void tags_Create_NormalizaTrimYMinusculas_201() throws Exception {
        String request = """
                {"name": "  Favorito  "}
                """;
        JsonNode json = exchangeJson(mvc.post().uri("/api/tags").contentType("application/json")
                .content(request).exchange().getResponse().getContentAsString());

        assertThat(json.get("name").asString()).isEqualTo("favorito");
        assertThat(json.get("id").asString()).isNotBlank();
        assertThat(countTagsByName("favorito")).isEqualTo(1);
    }

    @Test
    void tags_Create_DuplicadoIgnoreCase_409() throws Exception {
        insertTag("favorito");

        String request = """
                {"name": "FAVORITO"}
                """;
        String body = mvc.post().uri("/api/tags").contentType("application/json").content(request)
                .exchange().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.get("status").asInt()).isEqualTo(409);
        assertThat(countTagsByName("favorito")).isEqualTo(1);
    }

    @Test
    void tags_List_FiltroQ_SubstringIgnoreCase() throws Exception {
        insertTag("favorito");
        insertTag("pendiente");

        String body = mvc.get().uri("/api/tags").queryParam("q", "PEN").exchange()
                .getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.size()).isEqualTo(1);
        assertThat(json.get(0).get("name").asString()).isEqualTo("pendiente");
    }

    @Test
    void tags_Patch_RenombraNormalizando_200() throws Exception {
        UUID id = insertTag("favorito");

        String request = """
                {"name": " Leido "}
                """;
        JsonNode json = exchangeJson(mvc.patch().uri("/api/tags/" + id).contentType("application/json")
                .content(request).exchange().getResponse().getContentAsString());

        assertThat(json.get("name").asString()).isEqualTo("leido");
        assertThat(countTagsByName("leido")).isEqualTo(1);
        assertThat(countTagsByName("favorito")).isZero();
    }

    @Test
    void tags_Patch_MismoNombreConCasingDiferente_NoLanza409() throws Exception {
        UUID id = insertTag("favorito");

        String request = """
                {"name": "FAVORITO"}
                """;
        JsonNode json = exchangeJson(mvc.patch().uri("/api/tags/" + id).contentType("application/json")
                .content(request).exchange().getResponse().getContentAsString());

        assertThat(json.get("name").asString()).isEqualTo("favorito");
    }

    @Test
    void tags_Patch_NombreDeOtroTagExistente_409() throws Exception {
        insertTag("favorito");
        UUID otro = insertTag("pendiente");

        String request = """
                {"name": "FAVORITO"}
                """;
        String body = mvc.patch().uri("/api/tags/" + otro).contentType("application/json").content(request)
                .exchange().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get("status").asInt()).isEqualTo(409);
    }

    @Test
    void tags_Patch_Inexistente_404() throws Exception {
        String request = """
                {"name": "x"}
                """;
        assertThat(mvc.patch().uri("/api/tags/" + UUID.randomUUID()).contentType("application/json")
                .content(request).exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void tags_Delete_204ConCascadeASourceTags() throws Exception {
        UUID source = insertSource("a.pdf", "Autor/a.pdf", "autor/a.pdf", "h1");
        UUID tag = insertTag("favorito");
        jdbcTemplate.update("INSERT INTO source_tags (source_id, tag_id) VALUES (?, ?)", source, tag);

        assertThat(mvc.delete().uri("/api/tags/" + tag).exchange()).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(countTagsByName("favorito")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_tags WHERE tag_id = ?",
                Integer.class, tag)).isZero();
    }

    @Test
    void tags_Delete_Inexistente_404() throws Exception {
        assertThat(mvc.delete().uri("/api/tags/" + UUID.randomUUID()).exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void authors_List_DevuelveTodosConIdYNombre() throws Exception {
        insertAuthor("Gabriel García Márquez");
        insertAuthor("Jorge Luis Borges");

        String body = mvc.get().uri("/api/authors").exchange().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.size()).isEqualTo(2);
        assertThat(json.get(0).get("name").asString()).isNotBlank();
        assertThat(json.get(0).get("id").asString()).isNotBlank();
    }

    @Test
    void authors_List_FiltroQ_SubstringIgnoreCase() throws Exception {
        insertAuthor("Gabriel García Márquez");
        insertAuthor("Jorge Luis Borges");

        String body = mvc.get().uri("/api/authors").queryParam("q", "borges").exchange()
                .getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.size()).isEqualTo(1);
        assertThat(json.get(0).get("name").asString()).isEqualTo("Jorge Luis Borges");
    }

    @Test
    void reconcile_Request_MarcaPendingYDevuelveEstado() throws Exception {
        JsonNode json = exchangeJson(mvc.post().uri("/api/reconcile").exchange()
                .getResponse().getContentAsString());

        assertThat(json.get("pending").asBoolean()).isTrue();
        assertThat(json.has("message")).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT pending FROM reconciliation WHERE id = 1",
                Boolean.class)).isTrue();
    }

    @Test
    void reconcile_Pending_ReflejaEstadoActual() throws Exception {
        JsonNode json = exchangeJson(mvc.get().uri("/api/reconcile/pending").exchange()
                .getResponse().getContentAsString());
        assertThat(json.get("pending").asBoolean()).isFalse();

        mvc.post().uri("/api/reconcile").exchange();
        json = exchangeJson(mvc.get().uri("/api/reconcile/pending").exchange()
                .getResponse().getContentAsString());
        assertThat(json.get("pending").asBoolean()).isTrue();
    }

    @Test
    void reconcile_Ack_LimpiaPendingYEsIdempotente() throws Exception {
        mvc.post().uri("/api/reconcile").exchange();

        JsonNode json = exchangeJson(mvc.post().uri("/api/reconcile/ack").exchange()
                .getResponse().getContentAsString());
        assertThat(json.get("acknowledged").asBoolean()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT pending FROM reconciliation WHERE id = 1",
                Boolean.class)).isFalse();

        json = exchangeJson(mvc.post().uri("/api/reconcile/ack").exchange()
                .getResponse().getContentAsString());
        assertThat(json.get("acknowledged").asBoolean()).isTrue();
    }

    private JsonNode exchangeJson(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private int countTagsByName(String name) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tags WHERE name = ?", Integer.class, name);
    }

    private UUID insertTag(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tags (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private UUID insertAuthor(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO authors (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private UUID insertSource(String name, String path, String pathLower, String contentHash) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sources (id, name, path, path_lower, content_hash, file_format)
                VALUES (?, ?, ?, ?, ?, 'PDF')
                """, id, name, path, pathLower, contentHash);
        return id;
    }
}
