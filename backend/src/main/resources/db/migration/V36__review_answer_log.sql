-- Review calibration: each (document, question) answer counts once, however often the
-- document is re-reviewed, and can be un-counted when the document is deleted.
CREATE TABLE IF NOT EXISTS review_answer_log (
    id              UUID PRIMARY KEY,
    document_id     UUID NOT NULL,
    question_id     VARCHAR(100) NOT NULL,
    contract_type   VARCHAR(64),
    answered_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_review_answer_doc_question UNIQUE (document_id, question_id)
);
CREATE INDEX IF NOT EXISTS idx_review_answer_document ON review_answer_log (document_id);
