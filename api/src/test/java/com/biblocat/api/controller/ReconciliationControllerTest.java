package com.biblocat.api.controller;

import com.biblocat.api.dto.response.ReconciliationAckResponse;
import com.biblocat.api.dto.response.ReconciliationPendingResponse;
import com.biblocat.api.dto.response.ReconciliationStatusResponse;
import com.biblocat.api.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebMvcTest(ReconciliationController.class)
class ReconciliationControllerTest {

    @Autowired
    private org.springframework.test.web.servlet.assertj.MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReconciliationService reconciliationService;

    @Test
    void request_DelegaYDevuelveEstado() throws Exception {
        when(reconciliationService.request()).thenReturn(new ReconciliationStatusResponse(true, "ok"));

        JsonNode json = objectMapper.readTree(mvc.post().uri("/api/reconcile").exchange()
                .getResponse().getContentAsString());

        assertThat(json.get("pending").asBoolean()).isTrue();
        assertThat(json.get("message").asString()).isEqualTo("ok");
        verify(reconciliationService).request();
    }

    @Test
    void pending_Delega() throws Exception {
        when(reconciliationService.isPending()).thenReturn(new ReconciliationPendingResponse(true));

        JsonNode json = objectMapper.readTree(mvc.get().uri("/api/reconcile/pending").exchange()
                .getResponse().getContentAsString());

        assertThat(json.get("pending").asBoolean()).isTrue();
        verify(reconciliationService).isPending();
    }

    @Test
    void ack_Delega() throws Exception {
        when(reconciliationService.ack()).thenReturn(new ReconciliationAckResponse(true));

        JsonNode json = objectMapper.readTree(mvc.post().uri("/api/reconcile/ack").exchange()
                .getResponse().getContentAsString());

        assertThat(json.get("acknowledged").asBoolean()).isTrue();
        verify(reconciliationService).ack();
    }
}
