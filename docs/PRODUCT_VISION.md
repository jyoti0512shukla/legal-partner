# Legal Partner — Product Vision & Infrastructure Guide

**Private Contract Intelligence for Indian Law Firms**

> Your contracts never leave your servers. Our AI extracts every obligation, deadline, and risk — across your entire portfolio — in minutes instead of days. Built for Indian law.

---

## 1. Privacy-First Architecture

Legal Partner is designed for **complete on-premise deployment**. Zero data leaves the firm's network — no cloud APIs, no external model calls, no telemetry.

This is not a limitation; it is the core selling point:

- Client-attorney privilege is preserved by design
- No dependency on third-party AI services (OpenAI, Google, etc.)
- Full compliance with Bar Council of India confidentiality rules
- Data sovereignty — the firm owns and controls everything
- Works offline after initial setup (no internet required)

---

## 2. Hardware Recommendations

### Minimum (Demo / Small Firm, 1-3 users)

| Component | Spec | Cost (approx) |
|-----------|------|---------------|
| RAM | 8 GB | — |
| Storage | 500 GB HDD | — |
| GPU | None (CPU only) | — |
| Model | Llama 3 8B (Q4) | Free |

- Response time: 30-60 seconds per query
- Suitable for demos and small firms with low volume
- Can index ~5,000 documents

### Recommended (Mid-Tier Firm, 5-15 users)

| Component | Spec | Cost (approx) |
|-----------|------|---------------|
| RAM | **24 GB** | — |
| Storage | **8 TB HDD** + 256 GB SSD (OS) | — |
| GPU | **RTX 4060/4070** (8-12 GB VRAM) | ₹30,000 - 50,000 |
| Model | Llama 3 8B | Free |

- Response time: **2-5 seconds** per query
- Supports 5-10 concurrent users
- Can index 50,000+ documents
- 8 TB stores ~500K contracts with full version history

### Optimal (Large Firm, 15-50 users)

| Component | Spec | Cost (approx) |
|-----------|------|---------------|
| RAM | 32-64 GB | — |
| Storage | 8 TB HDD + 512 GB NVMe | — |
| GPU | **RTX 4090 / A4000** (16-24 GB VRAM) | ₹1,00,000 - 2,00,000 |
| Model | **Llama 3 70B (Q4)** | Free |

- Response time: **5-10 seconds** per query (near GPT-4 quality)
- Supports 15-25 concurrent users
- Court-ready clause summaries and contract drafting
- Multi-dimensional contract comparison with detailed reasoning

### Enterprise (Large Firm / Legal Department, 50+ users)

| Component | Spec | Cost (approx) |
|-----------|------|---------------|
| RAM | 128 GB | — |
| Storage | 16 TB RAID + 1 TB NVMe | — |
| GPU | **A100 / H100** (40-80 GB VRAM) | ₹5,00,000 - 15,00,000 |
| Model | Llama 3 70B (full precision) | Free |

- Response time: **3-5 seconds** with 50+ concurrent users
- Multiple specialized models running simultaneously
- Production-grade throughput

---

## 3. Model Comparison (All Local via Ollama)

All models run through Ollama. Switching models requires changing one line in `application.yml`. No code changes.

### Generation Models

| Model | Parameters | VRAM (Q4) | Legal Reasoning | Training Data | Speed (L4 GPU) |
|-------|-----------|-----------|----------------|--------------|----------------|
| TinyLlama | 1.1B | 2 GB | Unusable | Generic | 1-2 sec |
| Phi-3 mini | 3.8B | 2.3 GB | Basic | Generic | 2-3 sec |
| Llama 3 8B | 8B | 5 GB | Good | Generic | 2-5 sec |
| **Saul-Instruct-v1** | **7B** | **4.5 GB** | **Excellent** | **30B+ legal tokens** | **8-10 sec** |
| **Cimphony-Mistral-Law-7B** | **7B** | **4.5 GB** | **Excellent** | **600M legal tokens** | **8-10 sec** |
| **INLegalLlama** | **7B** | **4.5 GB** | **Excellent (Indian law)** | **Indian courts corpus** | **8-10 sec** |
| Llama 3 70B (Q4) | 70B | 24 GB | Near GPT-4 | Generic | 8-15 sec |

### Embedding Models

| Model | Dimensions | Training Data | Legal Understanding |
|-------|-----------|--------------|-------------------|
| all-minilm | 384 | Generic web text | Poor — treats legal terms as generic English |
| **InLegalBERT** | **768** | **5.4M Indian legal documents (1950-2019)** | **Excellent — trained on Supreme Court, High Courts, civil/criminal/constitutional domains** |

### Recommended Stack

**Production (client deployment):**
- Generation: **Saul-Instruct-v1** (7B, Q4_K_M) — state-of-the-art English legal reasoning, trained on 30B+ legal tokens
- Embeddings: **InLegalBERT** (768-dim) — trained on Indian legal corpus, understands statute references and case law vocabulary
- Why: Legal-domain models dramatically outperform generic models on clause extraction, risk assessment, and legal citation

**Development (local, 8 GB RAM):**
- Generation: phi-3-mini (3.8B, Q4) — best quality-to-RAM ratio for development
- Embeddings: all-minilm (384-dim) — lightweight for dev/testing

**Indian law specialization (future):**
- Generation: INLegalLlama — if Indian judgment prediction and Hindi legal docs become priority

See `docs/STRATEGY.md` for detailed model selection rationale and benchmarks.

---

## 4. Current Features (v0.1 — Prototype)

### Document Ingestion
- Upload PDF, HTML, DOCX contracts (up to 50 MB)
- Apache Tika text extraction
- Legal-aware chunking — splits at clause/section boundaries
- AES-256 encryption at rest (Jasypt)
- PGVector embedding storage for semantic search

### AI Intelligence (RAG Pipeline)
- Query expansion with legal synonyms
- Metadata pre-filtering (jurisdiction, year, clause type)
- Re-ranking (vector similarity + keyword overlap + recency)
- Legal prompt templates tailored for Indian law
- Citation extraction with verified/unverified indicators
- Three-layer validation: structure, faithfulness, confidence calibration

### Contract Comparison
- Side-by-side dimensional analysis (Liability, Termination, Governing Law, etc.)
- Favorable-to indicators per dimension

### Risk Assessment
- Categorized risk report (HIGH / MEDIUM / LOW per category)
- Clause references for each risk finding

### Audit Trail
- Compliance-grade logging of every user action
- Filter by user, action type, date range
- CSV export for compliance reporting

### Security & RBAC
- 3 roles: Admin, Partner, Associate
- Data-level filtering (Associates cannot see confidential documents)
- HTTP Basic Auth (upgradeable to LDAP/SSO)
- Encrypted document storage

---

## 5. Product Roadmap

### Phase 1: Foundation (Current → v0.2)
**Goal**: Make existing features production-quality

- [ ] **Swap TinyLlama → Llama 3 8B** — instant quality improvement for all AI features
- [ ] **Ollama JSON mode** — force structured output, eliminate parsing failures
- [ ] **Batch embedding** — faster document indexing
- [ ] **Upload progress indicator** — real-time feedback for large documents
- [ ] **Document status polling** — auto-refresh until INDEXED

### Phase 2: Clause Intelligence (v0.3)
**Goal**: Features that directly save associate hours

- [ ] **Auto clause extraction** — identify and tag key clauses (termination, indemnity, liability, confidentiality, governing law, force majeure, IP) on every upload
- [ ] **Clause library** — searchable, filterable database of all extracted clauses across the firm's entire contract portfolio
  - "Show me all indemnity clauses from SEBI-regulated contracts"
  - "Find all termination clauses with notice period < 30 days"
- [ ] **Obligation & deadline tracker** — automatically extract dates, renewal periods, notice requirements; display in a calendar view with alerts
- [ ] **One-click contract summary** — generate a 1-page executive summary of any contract (partners review 50-page contracts in 2 minutes)
- [ ] **Clause comparison** — compare a specific clause against the firm's standard template and highlight deviations

### Phase 3: Indian Law Specialization (v0.4)
**Goal**: Deep Indian legal domain awareness — the competitive moat

- [ ] **Indian Contract Act section mapping** — automatically link contract clauses to relevant ICA 1872 sections (e.g., Section 73 for damages, Section 56 for frustration)
- [ ] **Stamp duty calculator** — compute stamp duty based on contract type, value, and state jurisdiction
- [ ] **Regulatory compliance flags** — auto-flag clauses that may conflict with:
  - SEBI regulations (for listed company contracts)
  - RBI/FEMA rules (for cross-border contracts)
  - Competition Act provisions (for M&A / JV agreements)
  - IT Act / DPDP Act (for data processing agreements)
- [ ] **Bilingual support** — process Hindi legal documents (court orders, government notices) using Tika + Indic language models
- [ ] **eCourts integration** — track case status, hearing dates, and orders via eCourts API; link cases to related contracts

### Phase 4: Contract Drafting (v0.5)
**Goal**: Move from analysis to creation

- [ ] **Template library** — pre-built templates for common Indian contract types:
  - NDA / Non-Disclosure Agreement
  - MSA / Master Services Agreement
  - Employment Agreement (with state-specific labor law compliance)
  - Lease Deed
  - Share Purchase Agreement
  - Joint Venture Agreement
- [ ] **AI-assisted drafting** — fill templates from matter metadata; AI suggests clause variations based on jurisdiction and counterparty type
- [ ] **Redlining / track changes** — side-by-side diff when counterparty sends revised version; AI highlights material changes and suggests responses
- [ ] **Negotiation assistant** — "Opposing counsel wants to cap indemnity at 1x fees. Our standard is uncapped. What's the market practice for IT services contracts in India?"

### Phase 5: Enterprise Features (v1.0)
**Goal**: Multi-firm deployment readiness

- [ ] **Multi-tenancy** — per-firm data isolation with shared infrastructure
- [ ] **LDAP / Active Directory auth** — integrate with firm's existing identity provider
- [ ] **SSO** — Google Workspace / Microsoft 365 / Okta
- [ ] **Per-matter access control** — Associate X can only see Matter Y's documents
- [ ] **Document versioning** — full history with diff view (who changed what, when)
- [ ] **Bulk upload** — migrate 5+ years of legacy contracts in one operation
- [ ] **Mobile responsive** — partners review summaries on phone/tablet
- [ ] **Backup & disaster recovery** — automated encrypted backups
- [ ] **API for integrations** — connect to firm's billing system, DMS, or practice management software

---

## 6. Competitive Positioning

### Why not cloud-based alternatives (Kira, Luminance, etc.)?

| Concern | Cloud Solutions | Legal Partner |
|---------|---------------|---------------|
| Data privacy | Data on vendor's servers | **100% on-premise** |
| Indian law | Generic / US/UK focused | **Built for Indian law** |
| Pricing | $50K-200K/year subscription | **One-time + maintenance** |
| Customization | Limited | **Fully customizable** |
| Internet dependency | Required always | **Works offline** |
| Data sovereignty | Vendor-controlled | **Firm-controlled** |

### Key differentiators
1. **Zero data leakage** — no API calls to external services, ever
2. **Legal-domain AI** — uses SaulLM-7B (trained on 30B+ legal tokens) + InLegalBERT (trained on 5.4M Indian court documents), not generic models
3. **AI co-pilot, not a platform** — plugs into their existing tools (Drive, Word, Outlook), zero disruption, no migration
4. **Indian law native** — ICA mapping, stamp duty, SEBI/RBI/FEMA compliance
5. **Open model ecosystem** — swap LLMs as better models release (no vendor lock-in)
6. **Try in a week** — upload a folder, start asking questions, no training needed

---

## 7. Pricing Framework

### Option A: Cloud-Hosted (Recommended — Lower Entry, We Manage Everything)

| Component | Cost |
|-----------|------|
| Deployment + migration + training | ₹3,00,000 - 5,00,000 (one-time) |
| Annual service (hosting + support + updates) | ₹2,00,000 - 3,00,000 / year |
| **Year 1 total** | **₹5,00,000 - 8,00,000** |
| **Year 2+ total** | **₹2,00,000 - 3,00,000 / year** |

Hosted on E2E Networks L4 GPU (Mumbai DC). Data stays in India. We manage infra.

### Option B: On-Premise (Client Buys Hardware, Full Control)

| Component | Cost |
|-----------|------|
| Server hardware (24 GB RAM, 8 TB, GPU) | ₹2,00,000 - 4,00,000 (client purchases) |
| Software license + deployment + training | ₹3,00,000 - 5,00,000 (one-time) |
| Annual support + model upgrades | ₹1,50,000 - 2,50,000 / year |
| **Year 1 total** | **₹6,00,000 - 11,00,000** |
| **Year 2+ total** | **₹1,50,000 - 2,50,000 / year** |

### Option C: Monthly (SaaS-Style, Easiest to Try)

| Component | Cost |
|-----------|------|
| Monthly service (all-inclusive) | ₹45,000 / month |
| Setup fee (one-time) | ₹2,00,000 |
| **Year 1 total** | **₹7,40,000** |
| **Year 2+ total** | **₹5,40,000 / year** |

### ROI Justification

| Metric | Before | After |
|--------|--------|-------|
| Contract review time (per contract) | 4-8 hours | 30-60 minutes |
| Clause search across portfolio | Manual (days) | Instant |
| Missed deadline risk | High | Zero (automated alerts) |
| New associate onboarding | 2-3 months | 2-3 weeks (AI-assisted) |
| Compliance audit preparation | 1-2 weeks | 1-2 hours (export) |

A mid-tier firm with 10 associates billing at ₹3,000-5,000/hour saves **200+ associate hours/month** on contract review alone. That's ₹6-10 lakhs/month in recovered billable time.

---

## 8. Tech Stack

| Layer | Technology | Why |
|-------|-----------|-----|
| Backend | Spring Boot 3.2, Java 21 | Enterprise-grade, strong typing, mature ecosystem |
| Frontend | React 18, Vite, Tailwind CSS | Modern, fast, beautiful UI |
| Database | PostgreSQL 16 + PGVector | Proven reliability + native vector search |
| LLM | Ollama (Llama 3 / Mistral / any GGUF) | Local inference, model-agnostic |
| Embeddings | all-minilm (384 dim) | Fast, lightweight, good quality |
| Search | PGVector + keyword hybrid | Best of semantic + exact match |
| Encryption | Jasypt AES-256 | Industry standard at-rest encryption |
| Auth | Spring Security (RBAC) | Upgradeable to LDAP/SSO |
| Deployment | Docker Compose | Single-command deployment |
| PDF parsing | Apache Tika 2.9 | Handles PDF, DOCX, HTML, scanned docs |

---

## 9. Security & Compliance

- **Encryption at rest**: AES-256 for all document chunks stored in PGVector
- **Encryption in transit**: HTTPS (configure TLS certificates for production)
- **Role-based access control**: Admin > Partner > Associate hierarchy
- **Audit trail**: Every action logged with user, timestamp, endpoint, response time
- **Data isolation**: Associates cannot access confidential documents
- **No external calls**: All AI inference runs locally via Ollama
- **Backup-ready**: PostgreSQL pg_dump + encrypted file backup
- **Compliance**: Designed for Bar Council of India confidentiality requirements

---

## 10. Deployment

### Cloud GPU Deployment (Recommended — E2E Networks L4)

```
E2E Networks NVIDIA L4 (24 GB VRAM) — ₹30,762/month
├── Ollama + Saul-Instruct-v1 (Q4_K_M)  → 4.5 GB VRAM
├── Ollama + InLegalBERT (embeddings)    → 0.4 GB VRAM
├── PostgreSQL + PGVector                → System RAM
├── Spring Boot Backend                  → System RAM
├── React Frontend (nginx)               → Minimal
└── 19 GB VRAM free for KV-cache & concurrent queries

Performance: 30-40 tokens/sec → ~10 sec per legal answer
Concurrent users: 5-10 comfortably
Data center: Mumbai / Delhi / Bangalore (data stays in India)
```

See `docs/STRATEGY.md` for full infrastructure comparison (CPU vs GPU, pricing tiers, provider comparison).

### Production Deployment (On-Premise, Single Server)

```bash
# 1. Install Docker on Ubuntu/RHEL server
# 2. Clone the repository
git clone <repo-url> /opt/legal-partner
cd /opt/legal-partner

# 3. Configure environment
cp .env.example .env
# Edit .env: set DB_PASSWORD, ENCRYPTION_KEY

# 4. Pull the recommended model
docker compose up -d ollama
docker exec -it legal-partner-ollama-1 ollama pull llama3

# 5. Start all services
docker compose up -d --build

# 6. Build frontend for production
cd frontend && npm install && npm run build
# Serve dist/ via nginx or similar
```

### Model Swap (Zero Downtime)

```bash
# Pull new model
docker exec -it legal-partner-ollama-1 ollama pull llama3:70b

# Update application.yml: model-name: llama3:70b
# Restart backend only
docker compose restart backend
```

---

*Document version: 0.1 | Last updated: February 2026*
