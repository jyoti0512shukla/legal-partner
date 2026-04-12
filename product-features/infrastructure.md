# Infrastructure — Legal Partner

Docker stack, LLM providers, vector database, encryption, and multi-jurisdiction support.

---

## 1. Docker Stack

### Services (docker-compose.yml)

| Service | Image | Port | Purpose |
|---------|-------|------|---------|
| **postgres** | postgres:16 + pgvector | 5432 | Primary database + vector store |
| **ollama** | ollama/ollama | 11434 | Local LLM inference |
| **ollama-init** | ollama/ollama | — | Pulls models on first start (tinyllama, all-minilm) |
| **backend** | Spring Boot app | 8080 | Java application server |
| **onlyoffice** | onlyoffice/documentserver | 8443 | In-browser document editing |

### Volumes

| Volume | Purpose |
|--------|---------|
| pgdata | PostgreSQL data persistence |
| ollama_models | Downloaded LLM model weights |
| onlyoffice_data | ONLYOFFICE server data |
| document_storage | Original uploaded files (/data/documents/) |

### Production variant (docker-compose.prod.yml)

Uses GHCR container images with versioned tags instead of local builds. Managed by the `lp` CLI tool for deployment.

---

## 2. LLM Providers

The platform supports two LLM backends, configurable at the server level.

### vLLM (default)

- **API:** OpenAI-compatible REST API
- **Special capability:** Guided JSON via Outlines — constrained decoding that guarantees valid JSON output matching a provided JSON schema
- **Used for:** All AI features (risk assessment, clause checklist, drafting, extraction, summary, redlines, Q&A)
- **Configuration:**
  ```yaml
  legalpartner:
    chat-provider: vllm
    vllm:
      base-url: http://vllm:8000
      model: your-model-name
  ```
- **Three generation modes:**
  1. `guided_json` — constrained to a JSON schema (Outlines). Used for structured outputs (checklists, risk categories).
  2. `completions` — prefix-based text generation. Used for CSV-format fallbacks.
  3. `prose` — standard chat completion. Used for free-text generation (drafting, summaries).

### Gemini

- **API:** Google Gemini 2.5 Flash via API key
- **Configuration:**
  ```yaml
  legalpartner:
    chat-provider: gemini
    gemini:
      api-key: your-api-key
      model: gemini-2.5-flash
  ```

---

## 3. Embedding Model

**Ollama all-minilm** — runs locally alongside the application.

- Used for: Document chunk embeddings, query embeddings, conversation turn embeddings
- Dimensionality: 384
- Stored in: pgvector (PostgreSQL extension)

Alternative: External embedding API for low-resource development environments.

---

## 4. Vector Database (pgvector)

PostgreSQL 16 with the pgvector extension provides:
- **Cosine similarity search** over document chunk embeddings
- **HNSW indexes** for fast approximate nearest neighbor search
- **Standard SQL** for metadata filtering alongside vector search
- **Encryption at rest** — chunk content encrypted via Jasypt before storage

### Vector search flow

```
Query embedding (384-dim) → pgvector cosine similarity search
    → Top 200 candidates (configurable)
    → Matter-scoped filtering (WHERE matter_id = ...)
    → Re-ranking (vector 0.7 + keyword 0.2 + doctype 0.1)
    → Top 5 results (configurable)
    → Decrypt content → pass to LLM
```

---

## 5. Encryption

### Document chunks (at rest)

All indexed chunk content is encrypted using Jasypt `StringEncryptor` before storage in pgvector. Decrypted on retrieval.

**Important:** Embedding vectors are NOT encrypted (they need to be searchable). The plaintext content they represent IS encrypted. A database breach exposes embeddings (which cannot be easily reversed to readable text) but not the contract text itself.

```yaml
jasypt:
  encryptor:
    password: ${JASYPT_PASSWORD:change-me-in-production}
```

### JWT tokens

JWT signing key configured separately:
```yaml
legalpartner:
  jwt:
    secret: ${JWT_SECRET}
```

### Passwords

Bcrypt-hashed in the database. Password history (last 5) also stored as bcrypt hashes.

---

## 6. Multi-Jurisdiction Legal System

**Service:** `LegalSystemConfig`

Every LLM prompt is localized for the configured jurisdiction. Prompt templates contain `%MARKER%` placeholders that are replaced with jurisdiction-specific law references.

### 7 Supported Jurisdictions

| Jurisdiction | Key laws referenced |
|-------------|-------------------|
| **India** (default) | Indian Contract Act 1872, Arbitration and Conciliation Act 1996, DPDPA 2023, IT Act 2000, Companies Act 2013 |
| **USA (California)** | UCC, Federal Arbitration Act, CCPA, Cal. Civ. Code |
| **USA (New York)** | UCC, Federal Arbitration Act, NY Gen. Bus. Law |
| **USA (Delaware)** | UCC, Federal Arbitration Act, DGCL |
| **England and Wales** | English common law, Arbitration Act 1996, UK GDPR, Contracts (Rights of Third Parties) Act 1999 |
| **Singapore** | Contracts Act (Cap 53), International Arbitration Act (Cap 143A), SIAC Rules, PDPA 2012 |
| **Germany** | BGB (Civil Code), ICC Rules, GDPR, HGB (Commercial Code) |
| **France** | Code Civil, ICC Rules, GDPR |
| **Australia** | Australian Consumer Law, Privacy Act 1988, International Arbitration Act 1974 |

### What gets localized

Each jurisdiction provides:
- **Law references** for contract interpretation
- **Court seat** for governing law clauses
- **Arbitration act** reference
- **Data protection law** (GDPR, CCPA, DPDPA, PDPA, Privacy Act)
- **IP law** references
- **Tax law** references
- **Employment law** references (for employment contracts)

### Configuration

```yaml
legalpartner:
  legal-system: USA  # or INDIA, UK, SINGAPORE, GERMANY, FRANCE, AUSTRALIA
```

Can be overridden per-request for drafting (e.g., draft an NDA under Singapore law even though the server default is USA).

---

## 7. Database Schema

19 Flyway migrations manage the schema. Key tables:

| Table | Purpose |
|-------|---------|
| users | User accounts |
| user_mfa_secrets | TOTP MFA secrets |
| password_history | Last 5 password hashes |
| auth_tokens | Invite and password reset tokens |
| auth_config | Configurable auth settings |
| document_metadata | Document metadata + extraction fields |
| audit_logs | Complete audit trail |
| matters | Legal matters |
| matter_members | Matter team members |
| matter_findings | Deal intelligence findings |
| playbooks | Negotiation playbooks |
| playbook_positions | Per-clause positions |
| clause_library | Reusable clause templates |
| workflow_definitions | Workflow configurations |
| workflow_runs | Workflow execution records |
| cloud_storage_connections | Cloud provider connections |
| integration_connections | External integration connections |
| query_feedback | AI answer feedback |
| teams + team_members | User groups |
| review_pipelines + review_stages | Approval pipelines |
| matter_reviews + review_actions | Review instances and actions |
| agent_config | Deal intelligence agent settings |

---

## 8. Error Handling

`GlobalExceptionHandler` provides consistent error responses:

| Status | Trigger |
|--------|---------|
| 400 | Validation errors, malformed JSON, type mismatches, illegal arguments |
| 403 | Security exceptions (unauthorized access) |
| 404 | Resource not found |
| 413 | File upload > 50MB |
| 429 | Draft concurrency limit hit (Semaphore full) |
| 503 | LLM unavailable (vLLM/Ollama down) |
| 500 | Unhandled exceptions |

---

## 9. Frontend

26 pages built with React:

| Category | Pages |
|----------|-------|
| Core | Dashboard, Intelligence (RAG chat), Documents, Draft, Compare |
| Matters | Matters list, Matter detail |
| AI features | Extraction, Contract review, Risk assessment |
| Workflows | Workflows list, Workflow run, Workflow builder, Workflow analytics |
| Management | Clause library, Playbooks, EDGAR import, Audit log |
| Settings | Settings (Agent config, Integrations, Review pipelines, Teams, Users), Change password |
| Auth | Sign up, Forgot password, Reset password, Accept invite |
