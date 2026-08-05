package com.biblocat.api.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestContainerConfig.class)
@Tag("integration")
public abstract class AbstractPostgresIntegrationTest {

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected TestDataFactory data;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM source_tags");
        jdbcTemplate.update("DELETE FROM sources");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM authors");
        jdbcTemplate.update("UPDATE reconciliation SET pending = false");
    }
}
