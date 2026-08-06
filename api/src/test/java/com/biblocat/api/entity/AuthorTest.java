package com.biblocat.api.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthorTest {

    @Test
    void iguales_porNombre_mismoHash() {
        Author ana1 = new Author("Ana");
        Author ana2 = new Author("Ana");

        assertThat(ana1).isEqualTo(ana2);
        assertThat(ana1.hashCode()).isEqualTo(ana2.hashCode());
    }

    @Test
    void conNombresDistintos_noIguales_distintoHash() {
        Author ana = new Author("Ana");
        Author beto = new Author("Beto");

        assertThat(ana).isNotEqualTo(beto);
        assertThat(ana.hashCode()).isNotEqualTo(beto.hashCode());
    }

    @Test
    void contraOtraClase_noIgual() {
        assertThat(new Author("Ana")).isNotEqualTo("Ana");
    }
}