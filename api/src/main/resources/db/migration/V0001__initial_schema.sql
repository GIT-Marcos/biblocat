CREATE TYPE file_format AS ENUM ('PDF', 'EPUB', 'MHTML');

CREATE TABLE authors
(
    id   UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE sources
(
    id           UUID                   DEFAULT gen_random_uuid() PRIMARY KEY,
    name         VARCHAR(255)  NOT NULL,
    path         VARCHAR(1024) NOT NULL,
    path_lower   VARCHAR(1024) NOT NULL,
    content_hash VARCHAR(64)   NOT NULL,
    file_format  file_format   NOT NULL,
    author_id    UUID          REFERENCES authors (id) ON DELETE SET NULL,
    year         INTEGER,
    edition      VARCHAR(50),
    url          VARCHAR(2048),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_sources_active_path_lower
    ON sources (path_lower) WHERE deleted_at IS NULL;

CREATE INDEX idx_sources_content_hash ON sources (content_hash);

CREATE INDEX idx_sources_deleted_at ON sources (deleted_at);

CREATE TABLE tags
(
    id   UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE source_tags
(
    source_id UUID NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    tag_id    UUID NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (source_id, tag_id)
);

CREATE TABLE reconciliation
(
    id         INTEGER PRIMARY KEY,
    pending    BOOLEAN     NOT NULL DEFAULT false,
    version    BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT reconciliation_one_row CHECK (id = 1)
);

INSERT INTO reconciliation (id, pending)
VALUES (1, false);
