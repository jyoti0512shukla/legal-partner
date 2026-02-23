CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE document_metadata (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_name         VARCHAR(500) NOT NULL,
    content_type      VARCHAR(100),
    document_type     VARCHAR(50),
    practice_area     VARCHAR(50),
    client_name       VARCHAR(200),
    matter_id         VARCHAR(100),
    jurisdiction      VARCHAR(100),
    year              INTEGER,
    effective_date    DATE,
    confidential      BOOLEAN NOT NULL DEFAULT false,
    uploaded_by       VARCHAR(100) NOT NULL,
    upload_date       TIMESTAMPTZ NOT NULL DEFAULT now(),
    segment_count     INTEGER NOT NULL DEFAULT 0,
    file_size_bytes   BIGINT,
    ocr_applied       BOOLEAN NOT NULL DEFAULT false,
    processing_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX idx_doc_jurisdiction ON document_metadata(jurisdiction);
CREATE INDEX idx_doc_practice_area ON document_metadata(practice_area);
CREATE INDEX idx_doc_document_type ON document_metadata(document_type);
CREATE INDEX idx_doc_client_name ON document_metadata(client_name);
CREATE INDEX idx_doc_status ON document_metadata(processing_status);

CREATE TABLE audit_logs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    timestamp         TIMESTAMPTZ NOT NULL DEFAULT now(),
    username          VARCHAR(100) NOT NULL,
    user_role         VARCHAR(50) NOT NULL,
    action            VARCHAR(50) NOT NULL,
    endpoint          VARCHAR(500),
    http_method       VARCHAR(10),
    document_id       UUID,
    query_text        TEXT,
    retrieved_doc_ids TEXT,
    response_time_ms  BIGINT,
    ip_address        VARCHAR(50),
    success           BOOLEAN NOT NULL DEFAULT true,
    error_message     TEXT
);

CREATE INDEX idx_audit_timestamp ON audit_logs(timestamp DESC);
CREATE INDEX idx_audit_username ON audit_logs(username);
CREATE INDEX idx_audit_action ON audit_logs(action);
CREATE INDEX idx_audit_document ON audit_logs(document_id);
