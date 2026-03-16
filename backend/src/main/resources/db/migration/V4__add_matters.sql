-- Matter management table
CREATE TABLE matters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    matter_ref VARCHAR(100) NOT NULL UNIQUE,
    client_name VARCHAR(255) NOT NULL,
    practice_area VARCHAR(100),
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    description TEXT,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_matters_client_name ON matters(client_name);
CREATE INDEX idx_matters_status ON matters(status);
CREATE INDEX idx_matters_created_by ON matters(created_by);

-- Add matter FK to document_metadata
ALTER TABLE document_metadata ADD COLUMN matter_uuid UUID REFERENCES matters(id);
CREATE INDEX idx_document_metadata_matter ON document_metadata(matter_uuid);

-- Add structured extraction fields to document_metadata
ALTER TABLE document_metadata ADD COLUMN party_a VARCHAR(500);
ALTER TABLE document_metadata ADD COLUMN party_b VARCHAR(500);
ALTER TABLE document_metadata ADD COLUMN expiry_date DATE;
ALTER TABLE document_metadata ADD COLUMN contract_value VARCHAR(200);
ALTER TABLE document_metadata ADD COLUMN liability_cap VARCHAR(200);
ALTER TABLE document_metadata ADD COLUMN governing_law_jurisdiction VARCHAR(255);
ALTER TABLE document_metadata ADD COLUMN notice_period_days INTEGER;
ALTER TABLE document_metadata ADD COLUMN arbitration_venue VARCHAR(255);
ALTER TABLE document_metadata ADD COLUMN extraction_status VARCHAR(50) DEFAULT 'PENDING';
