package com.biblocat.api.controller;

import com.biblocat.api.dto.response.AuthorResponse;
import com.biblocat.api.service.AuthorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebMvcTest(AuthorController.class)
class AuthorControllerTest {

    @Autowired
    private org.springframework.test.web.servlet.assertj.MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthorService authorService;

    @Test
    void list_DelegaConFiltroQ() {
        when(authorService.findAll("borges")).thenReturn(List.of());

        mvc.get().uri("/api/authors").queryParam("q", "borges").exchange();

        verify(authorService).findAll("borges");
    }

    @Test
    void list_DevuelveContratoIdYNombre() throws Exception {
        UUID id = UUID.randomUUID();
        when(authorService.findAll(null)).thenReturn(List.of(new AuthorResponse(id, "Jorge Luis Borges")));

        JsonNode json = objectMapper.readTree(mvc.get().uri("/api/authors").exchange()
                .getResponse().getContentAsString());

        assertThat(json.isArray()).isTrue();
        assertThat(json.size()).isEqualTo(1);
        assertThat(json.get(0).get("id").asString()).isEqualTo(id.toString());
        assertThat(json.get(0).get("name").asString()).isEqualTo("Jorge Luis Borges");
    }
}
