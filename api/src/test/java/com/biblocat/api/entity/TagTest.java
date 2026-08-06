package com.biblocat.api.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TagTest {

    @Test
    void iguales_porNombre_mismoHash() {
        Tag fantasia = new Tag("Fantasia");
        Tag fantasia2 = new Tag("Fantasia");

        assertThat(fantasia).isEqualTo(fantasia2);
        assertThat(fantasia.hashCode()).isEqualTo(fantasia2.hashCode());
    }

    @Test
    void conNombresDistintos_noIguales_distintoHash() {
        Tag fantasia = new Tag("Fantasia");
        Tag ciencia = new Tag("Ciencia");

        assertThat(fantasia).isNotEqualTo(ciencia);
        assertThat(fantasia.hashCode()).isNotEqualTo(ciencia.hashCode());
    }

    @Test
    void contraOtraClase_noIgual() {
        assertThat(new Tag("Fantasia")).isNotEqualTo("Fantasia");
    }
}