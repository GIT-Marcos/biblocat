package com.biblocat.api.controller;

import com.biblocat.api.dto.response.*;
import com.biblocat.api.entity.FileFormat;
import com.biblocat.api.exception.ActiveSourceException;
import com.biblocat.api.exception.SourceNotFoundException;
import com.biblocat.api.service.SourceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@WebMvcTest(SourceController.class)
class SourceControllerTest {

    @Autowired
    private org.springframework.test.web.servlet.assertj.MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SourceService sourceService;

    @Test
    void list_Defaults_PasaPageablePorDefectoAlService() throws Exception {
        when(sourceService.findAll(any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mvc.get().uri("/api/sources").exchange().getResponse().getContentAsString();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(sourceService).findAll(eq(null), eq(null), eq(null), eq(null), eq(false), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "name"));
    }

    @Test
    void list_SortInvalido_400SinLlamarAlService() throws Exception {
        String body = mvc.get().uri("/api/sources").queryParam("sort", "campoInexistente,asc").exchange()
                .getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.get("status").asInt()).isEqualTo(400);
        assertThat(json.get("title").asString()).isEqualTo("Invalid Pagination Parameter");
        verify(sourceService, never()).findAll(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void list_PageNegativa_400() {
        assertThat(mvc.get().uri("/api/sources").queryParam("page", "-1").exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
        verify(sourceService, never()).findAll(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void list_SizeCero_400() {
        assertThat(mvc.get().uri("/api/sources").queryParam("size", "0").exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
        verify(sourceService, never()).findAll(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void list_FormatInvalido_400() {
        assertThat(mvc.get().uri("/api/sources").queryParam("format", "EXE").exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
        verify(sourceService, never()).findAll(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void patch_YearNoNumerico_400SinLlamarAlService() {
        String request = """
                {"year": "abc"}
                """;
        assertThat(mvc.patch().uri("/api/sources/" + UUID.randomUUID()).contentType("application/json")
                .content(request).exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        verify(sourceService, never()).patch(any(), any());
    }

    @Test
    void patch_UrlInvalida_400() {
        String request = """
                {"url": "ftp://servidor/x.pdf"}
                """;
        assertThat(mvc.patch().uri("/api/sources/" + UUID.randomUUID()).contentType("application/json")
                .content(request).exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        verify(sourceService, never()).patch(any(), any());
    }

    @Test
    void putTags_TagIdsNull_400SinLlamarAlService() {
        String request = """
                {"tagIds": null}
                """;
        assertThat(mvc.put().uri("/api/sources/" + UUID.randomUUID() + "/tags")
                .contentType("application/json").content(request).exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
        verify(sourceService, never()).replaceTags(any(), any());
    }

    @Test
    void reconcile_OperationsVacio_400SinLlamarAlService() {
        String request = """
                {"operations": []}
                """;
        assertThat(mvc.post().uri("/api/sources/reconcile").contentType("application/json").content(request)
                .exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        verify(sourceService, never()).reconcile(any());
    }

    @Test
    void getById_DelegaConIncludeDeletedFalseYDevuelveContrato() throws Exception {
        UUID id = UUID.randomUUID();
        when(sourceService.findById(id, false)).thenReturn(sourceResponse(id));

        String body = mvc.get().uri("/api/sources/" + id).exchange().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.get("id").asString()).isEqualTo(id.toString());
        assertThat(json.get("name").asString()).isEqualTo("a.pdf");
        assertThat(json.get("fileFormat").asString()).isEqualTo("PDF");
        assertThat(json.get("year").asInt()).isEqualTo(2024);
        assertThat(json.has("deletedAt")).isFalse();
        verify(sourceService).findById(id, false);
    }

    @Test
    void purge_204YDelegaAlService() {
        UUID id = UUID.randomUUID();

        assertThat(mvc.delete().uri("/api/sources/" + id).exchange()).hasStatus(HttpStatus.NO_CONTENT);
        verify(sourceService).purge(id);
    }

    @Test
    void getById_Inexistente_404Rfc9457() throws Exception {
        when(sourceService.findById(any(), anyBoolean())).thenThrow(new SourceNotFoundException(UUID.randomUUID()));

        String body = mvc.get().uri("/api/sources/" + UUID.randomUUID()).exchange()
                .getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(mvc.get().uri("/api/sources/" + UUID.randomUUID()).exchange().getResponse()
                .getHeader("Content-Type")).contains("application/problem+json");
        assertThat(json.get("status").asInt()).isEqualTo(404);
        assertThat(json.get("title").asString()).isEqualTo("Source Not Found");
        assertThat(json.get("type").asString()).isEqualTo("https://api.biblocat.local/errors/source-not-found");
    }

    @Test
    void purge_Activo_409Rfc9457() throws Exception {
        doThrow(new ActiveSourceException(UUID.randomUUID())).when(sourceService).purge(any());

        String body = mvc.delete().uri("/api/sources/" + UUID.randomUUID()).exchange()
                .getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.get("status").asInt()).isEqualTo(409);
        assertThat(json.get("title").asString()).isEqualTo("Active Source");
        assertThat(json.get("type").asString()).isEqualTo("https://api.biblocat.local/errors/active-source");
    }

    @Test
    void paths_200_SerializaContrato() throws Exception {
        UUID id = UUID.randomUUID();
        when(sourceService.findPathsForReconciliation()).thenReturn(List.of(
                new PathsEntryResponse(id, "Autor/a.pdf", "autor/a.pdf", "h1", null)));

        String body = mvc.get().uri("/api/sources/paths").exchange().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.isArray()).isTrue();
        assertThat(json.get(0).get("id").asString()).isEqualTo(id.toString());
        assertThat(json.get(0).get("path").asString()).isEqualTo("Autor/a.pdf");
        assertThat(json.get(0).get("pathLower").asString()).isEqualTo("autor/a.pdf");
        assertThat(json.get(0).get("contentHash").asString()).isEqualTo("h1");
        assertThat(json.get(0).has("deletedAt")).isFalse();
    }

    @Test
    void reconcile_200_SerializaContrato() throws Exception {
        when(sourceService.reconcile(any())).thenReturn(new ReconcileResponse(2, 1, 0, 1, 0, 0, List.of()));

        String request = """
                {"operations": [{"type": "CREATE", "name": "a.pdf", "path": "Autor/a.pdf",
                  "pathLower": "autor/a.pdf", "contentHash": "h1", "fileFormat": "PDF"}]}
                """;
        String body = mvc.post().uri("/api/sources/reconcile").contentType("application/json").content(request)
                .exchange().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.get("processed").asInt()).isEqualTo(2);
        assertThat(json.get("created").asInt()).isEqualTo(1);
        assertThat(json.get("updated").asInt()).isEqualTo(1);
        assertThat(json.get("errors").isArray()).isTrue();
        assertThat(json.get("errors").size()).isZero();
    }

    @Test
    void list_SortConSeparadorDosPuntos_400() {
        assertThat(mvc.get().uri("/api/sources").queryParam("sort", "author.name:asc").exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
        verify(sourceService, never()).findAll(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void list_SortMultipleValido_200YDelega() throws Exception {
        when(sourceService.findAll(any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mvc.get().uri("/api/sources").queryParam("sort", "name,desc").queryParam("sort", "year,asc")
                .exchange().getResponse().getContentAsString();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(sourceService).findAll(any(), any(), any(), any(), anyBoolean(), captor.capture());
        assertThat(captor.getValue().getSort().getOrderFor("name").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(captor.getValue().getSort().getOrderFor("year").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    private SourceResponse sourceResponse(UUID id) {
        return new SourceResponse(
                id,
                "a.pdf",
                "Autor/a.pdf",
                FileFormat.PDF,
                new AuthorResponse(UUID.randomUUID(), "Autor"),
                Set.of(new TagResponse(UUID.randomUUID(), "favorito")),
                2024,
                "1a",
                "https://x.com/a",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                null
        );
    }
}
