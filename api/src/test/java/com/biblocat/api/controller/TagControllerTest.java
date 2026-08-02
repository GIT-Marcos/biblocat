package com.biblocat.api.controller;

import com.biblocat.api.dto.request.TagCreateRequest;
import com.biblocat.api.dto.request.TagPatchRequest;
import com.biblocat.api.dto.response.TagResponse;
import com.biblocat.api.exception.TagAlreadyExistsException;
import com.biblocat.api.exception.TagNotFoundException;
import com.biblocat.api.service.TagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@WebMvcTest(TagController.class)
class TagControllerTest {

    @Autowired
    private org.springframework.test.web.servlet.assertj.MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TagService tagService;

    @Test
    void list_DelegaConFiltroQ() {
        when(tagService.findAll("pen")).thenReturn(List.of());

        mvc.get().uri("/api/tags").queryParam("q", "pen").exchange();

        verify(tagService).findAll("pen");
    }

    @Test
    void create_201YDelegaConElNombreNormalizadoDelRequest() throws Exception {
        UUID id = UUID.randomUUID();
        when(tagService.create(any())).thenReturn(new TagResponse(id, "favorito"));

        String request = """
                {"name": "favorito"}
                """;
        JsonNode json = objectMapper.readTree(mvc.post().uri("/api/tags").contentType("application/json")
                .content(request).exchange().getResponse().getContentAsString());

        assertThat(json.get("id").asString()).isEqualTo(id.toString());
        assertThat(json.get("name").asString()).isEqualTo("favorito");
        verify(tagService).create(new TagCreateRequest("favorito"));
    }

    @Test
    void create_Blank_400SinLlamarAlService() {
        String request = """
                {"name": "   "}
                """;
        assertThat(mvc.post().uri("/api/tags").contentType("application/json").content(request).exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
        verify(tagService, never()).create(any());
    }

    @Test
    void update_200YDelega() throws Exception {
        UUID id = UUID.randomUUID();
        when(tagService.update(eq(id), any())).thenReturn(new TagResponse(id, "leido"));

        String request = """
                {"name": "leido"}
                """;
        JsonNode json = objectMapper.readTree(mvc.patch().uri("/api/tags/" + id)
                .contentType("application/json").content(request).exchange().getResponse().getContentAsString());

        assertThat(json.get("name").asString()).isEqualTo("leido");
        verify(tagService).update(id, new TagPatchRequest("leido"));
    }

    @Test
    void update_Blank_400SinLlamarAlService() {
        String request = """
                {"name": ""}
                """;
        assertThat(mvc.patch().uri("/api/tags/" + UUID.randomUUID()).contentType("application/json")
                .content(request).exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        verify(tagService, never()).update(any(), any());
    }

    @Test
    void delete_204YDelega() {
        UUID id = UUID.randomUUID();

        assertThat(mvc.delete().uri("/api/tags/" + id).exchange()).hasStatus(HttpStatus.NO_CONTENT);
        verify(tagService).delete(id);
    }

    @Test
    void update_Inexistente_404Rfc9457() throws Exception {
        when(tagService.update(any(), any())).thenThrow(new TagNotFoundException(UUID.randomUUID()));

        String request = """
                {"name": "x"}
                """;
        String body = mvc.patch().uri("/api/tags/" + UUID.randomUUID()).contentType("application/json")
                .content(request).exchange().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.get("status").asInt()).isEqualTo(404);
        assertThat(json.get("title").asString()).isEqualTo("Tag Not Found");
        assertThat(json.get("type").asString()).isEqualTo("https://api.biblocat.local/errors/tag-not-found");
    }

    @Test
    void create_Duplicado_409Rfc9457() throws Exception {
        when(tagService.create(any())).thenThrow(new TagAlreadyExistsException("favorito"));

        String request = """
                {"name": "favorito"}
                """;
        String body = mvc.post().uri("/api/tags").contentType("application/json").content(request)
                .exchange().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.get("status").asInt()).isEqualTo(409);
        assertThat(json.get("title").asString()).isEqualTo("Tag Already Exists");
        assertThat(json.get("type").asString()).isEqualTo("https://api.biblocat.local/errors/tag-already-exists");
    }
}
