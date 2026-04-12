# Document Management — Legal Partner

Everything related to uploading, storing, indexing, editing, and importing documents.

---

## 1. Document Upload and Indexing

**Endpoint:** `POST /api/v1/documents/upload`

### Upload flow

```
User uploads file (max 50MB, any format)
    ↓
Store original file on disk (/data/documents/)
    ↓
Create DocumentMetadata entity (status: PENDING)
    ↓
Async processing pipeline:
    ├── Apache Tika extracts text from PDF, DOCX, DOC, HTML, etc.
    ├── LegalDocumentChunker splits text into legal-aware segments
    │   (target 80 tokens, max 100, min 20, overlap 15 tokens)
    │   Respects article/section boundaries
    ├── Each chunk encrypted via Jasypt before storage
    ├── Chunks embedded via Ollama all-minilm
    └── Embedded vectors stored in pgvector
    ↓
Status: INDEXED (or FAILED on error)
    ↓
Publish DocumentIndexedEvent
    ├── Triggers auto-workflows (if configured)
    └── If linked to a matter: triggers deal intelligence agent
```

### Metadata fields

| Field | Description |
|-------|-------------|
| jurisdiction | Legal jurisdiction (USA, India, England and Wales, etc.) |
| year | Document year |
| confidential | If true, hidden from ASSOCIATE role |
| documentType | NDA, MSA, SOW, EMPLOYMENT, LEASE, MOU, NOTICE, POWER_OF_ATTORNEY, PETITION, PARTNERSHIP, VENDOR, LOAN, OTHER |
| practiceArea | CORPORATE, LITIGATION, IP, EMPLOYMENT, REAL_ESTATE, etc. |
| clientName | Client associated with the document |
| matterId | Optional matter association |
| industry | Industry sector |
| source | USER (uploaded), EDGAR (corpus seed), CLOUD (cloud import), DRAFTED (AI-generated) |

### Extraction fields (populated by AI extraction)

partyA, partyB, effectiveDate, expiryDate, contractValue, liabilityCap, governingLawJurisdiction, noticePeriodDays, arbitrationVenue

---

## 2. Document Listing and Access

**Endpoint:** `GET /api/v1/documents`

- Paginated document list
- ASSOCIATE role cannot see confidential documents
- EDGAR-sourced documents hidden from the main list (they're corpus seed data)
- Filterable by documentType, jurisdiction, practiceArea

---

## 3. Corpus Statistics

**Endpoint:** `GET /api/v1/documents/stats`

Returns:
- Total documents in corpus
- Total indexed segments (chunks)
- Breakdown by jurisdiction (count per jurisdiction)
- Breakdown by practice area (count per practice area)

---

## 4. Document Deletion

**Endpoint:** `DELETE /api/v1/documents/{id}`

Deletes the document metadata, all indexed chunks from the vector store, and the original file from disk.

---

## 5. In-Browser Document Editing (ONLYOFFICE)

**Endpoint:** `GET /api/v1/editor/{documentId}/config`

### How it works

```
User clicks "Edit" on a document
    ↓
Frontend requests editor config from backend
    ↓
Backend returns ONLYOFFICE config:
  - documentUrl (where ONLYOFFICE can fetch the file)
  - callbackUrl (where ONLYOFFICE sends the saved file)
  - fileType (docx, doc, pdf, xlsx, html)
  - userInfo (name for collaborative editing)
    ↓
Frontend loads ONLYOFFICE editor iframe with the config
    ↓
User edits in-browser (full Word-like editing experience)
    ↓
On save: ONLYOFFICE calls the callback URL
    ↓
Backend downloads the edited file from ONLYOFFICE and overwrites the stored copy
```

### Supported formats

DOCX, DOC, PDF, XLSX, HTML — full editing with formatting, tracked changes, comments, headers/footers.

### Infrastructure

ONLYOFFICE Document Server runs as a Docker container on port 8443. Configured via:
- `legalpartner.onlyoffice.url` — browser-facing URL (for the iframe)
- `legalpartner.onlyoffice.backend-url` — Docker-internal URL (for file serving between containers)

---

## 6. EDGAR Import (SEC Filing Corpus Seed)

**Endpoint:** `POST /api/v1/edgar/import`

Import real commercial contracts from SEC EDGAR filings to seed the RAG corpus.

### Flow

```
User selects a preset or enters a custom search query
    ↓
POST /api/v1/edgar/search
    ↓
Backend searches EDGAR EFTS API for EX-10.x exhibit filings (2019-2024)
    - EX-10.x exhibits contain material contracts (MSAs, licenses, employment agreements)
    ↓
Returns: list of matching filings with title, filer, date, filing URL
    ↓
User selects which filings to import
    ↓
POST /api/v1/edgar/import
    ↓
For each selected filing:
    - Download document (max 500KB)
    - Create DocumentMetadata with source=EDGAR, jurisdiction=USA
    - Process through standard indexing pipeline
    - Rate-limited to respect EDGAR's 10 req/s limit
```

### 10 preset search queries

| Preset | Description |
|--------|-------------|
| IT_SERVICES_MSA | IT services master agreements |
| SAAS_AGREEMENT | SaaS subscription agreements |
| NDA | Non-disclosure agreements |
| SOFTWARE_LICENSE | Software license agreements |
| VENDOR_AGREEMENT | Vendor/supplier agreements |
| FINTECH_MSA | Financial technology service agreements |
| PHARMA_SERVICES | Pharmaceutical service agreements |
| MANUFACTURING | Manufacturing agreements |
| EMPLOYMENT | Executive employment agreements |
| IP_LICENSE | Intellectual property license agreements |

---

## 7. Cloud Storage Integration

**Endpoint:** `POST /api/v1/cloud-storage/import`

Import documents directly from cloud storage providers.

### Supported providers

| Provider | Auth | Capabilities |
|----------|------|-------------|
| Google Drive | OAuth2 | Browse folders, import files, save documents back |
| OneDrive | OAuth2 | Browse folders, import files, save documents back |
| Dropbox | OAuth2 | Browse folders, import files, save documents back |

### Flow

```
User connects cloud storage via OAuth2 flow
    ↓
GET /api/v1/cloud-storage/files?path=/contracts
    ↓
User selects files to import
    ↓
POST /api/v1/cloud-storage/import
    ↓
Backend downloads file from cloud storage
Creates DocumentMetadata with source=CLOUD
Processes through standard indexing pipeline
```

### Save to cloud

`POST /api/v1/cloud-storage/save` — push a document (e.g., a generated draft) back to the user's cloud storage.

---

## 8. Legal-Aware Document Chunking

The `LegalDocumentChunker` splits documents at legal boundaries rather than arbitrary character positions.

### Parameters

| Parameter | Value |
|-----------|-------|
| Target chunk size | 80 tokens |
| Maximum chunk size | 100 tokens |
| Minimum chunk size | 20 tokens |
| Overlap | 15 tokens |

### Boundary detection

The chunker detects and respects:
- Article boundaries (`ARTICLE 1 —`, `Article 2.`)
- Section boundaries (`Section 1.1`, `1.2`)
- Clause boundaries (numbered paragraphs)
- Definition blocks
- Schedule/exhibit boundaries

This ensures that a single clause is never split across two chunks, which would degrade RAG retrieval quality.

---

## 9. Encryption at Rest

All indexed chunk content is encrypted before storage in the vector database using Jasypt (`StringEncryptor`). Content is decrypted on retrieval before being passed to the RAG pipeline.

The embedding vectors themselves are NOT encrypted (they need to be searchable via pgvector similarity), but the plaintext content they represent IS encrypted. This means a database breach would expose embeddings but not readable contract text.

Configuration: `jasypt.encryptor.password` (must be changed from default in production).

---

## 10. Draft Saving

**Endpoint:** `POST /api/v1/ai/draft/save`

Saves an AI-generated draft as a Document in the corpus:
- Creates a DocumentMetadata entity with source=DRAFTED
- Optionally links to a matter (matterId)
- Stores the HTML draft as a file
- Indexes through the standard pipeline (so it becomes searchable in RAG)
