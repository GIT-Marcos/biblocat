package com.biblocat.api.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextSmokeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    void contextoArrancaYFlywayAplicaEsquema() {
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reconciliation", Integer.class);
        assertThat(rows).isEqualTo(1);

        Boolean pending = jdbcTemplate.queryForObject(
                "SELECT pending FROM reconciliation WHERE id = 1", Boolean.class);
        assertThat(pending).isFalse();
    }

    @Test
    void getTagsDevuelveListaVacia() {
        assertThat(mvc.get().uri("/api/tags")).hasStatusOk().hasBodyTextEqualTo("[]");
    }
}
