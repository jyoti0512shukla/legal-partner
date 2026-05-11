-- Index for client name lookups
CREATE INDEX IF NOT EXISTS idx_doc_client_lower ON document_metadata(LOWER(client_name));
CREATE INDEX IF NOT EXISTS idx_doc_party_a_lower ON document_metadata(LOWER(party_a));
CREATE INDEX IF NOT EXISTS idx_doc_party_b_lower ON document_metadata(LOWER(party_b));
