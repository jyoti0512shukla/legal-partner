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

| Model | Parameters | VRAM / RAM | JSON Reliability | Legal Reasoning | Speed (GPU) |
|-------|-----------|-----------|-----------------|----------------|------------|
| TinyLlama | 1.1B | 2 GB | Very poor | Unusable | 1-2 sec |
| Phi-3 mini | 3.8B | 3 GB | Decent | Basic | 2-3 sec |
| Mistral 7B | 7B | 5 GB | Good | Good | 2-4 sec |
| **Llama 3 8B** | 8B | 5 GB | **Very good** | **Very good** | **2-5 sec** |
| Qwen 2.5 14B | 14B | 10 GB | Very good | Very good | 5-8 sec |
| Mixtral 8x7B | 47B (MoE) | 16 GB | Excellent | Excellent | 5-10 sec |
| **Llama 3 70B (Q4)** | 70B | 24 GB | **Excellent** | **Near GPT-4** | **8-15 sec** |
| Llama 3 70B (full) | 70B | 40+ GB | Excellent | Near GPT-4 | 3-5 sec |

**Recommendation**: Llama 3 8B for most deployments. Llama 3 70B (Q4) when a 24 GB GPU is available.

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
2. **Indian law native** — ICA mapping, stamp duty, SEBI/RBI/FEMA compliance
3. **On-premise ownership** — the firm owns the hardware and all data
4. **Open model ecosystem** — swap LLMs as better open-source models release (no vendor lock-in)
5. **Cost structure** — one-time setup + annual maintenance, not perpetual SaaS subscription

---

## 7. Pricing Framework

### One-Time Setup (per firm)

| Component | Cost |
|-----------|------|
| Server hardware (24 GB RAM, 8 TB, GPU) | ₹2,00,000 - 4,00,000 |
| Software license + deployment | ₹6,00,000 - 8,00,000 |
| Legacy contract migration (bulk upload + indexing) | ₹1,00,000 - 2,00,000 |
| Training (2-day on-site) | Included |
| **Total** | **₹8,00,000 - 14,00,000** |

### Annual Maintenance

| Component | Cost |
|-----------|------|
| Software updates + model upgrades | ₹1,50,000 - 2,50,000 / year |
| Priority support (email + phone) | Included |
| On-site visits (quarterly) | Optional add-on |

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

### Production Deployment (Single Server)

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
