# Legal Partner — Strategy & Technical Decisions

**Last updated: February 2026**

This document captures key strategic decisions, model selection rationale, infrastructure analysis, and product positioning for Legal Partner.

---

## 1. Model Selection: Why Legal-Domain Models Matter

### The Problem with Generic Models

The current prototype uses `tinyllama` (1.1B) for generation and `all-minilm` (384-dim) for embeddings. Neither has legal training. For a product targeting law firms at 5-8L deployment cost, this is a critical weakness.

- TinyLlama misses subtle clause implications (e.g., one-sided indemnity)
- Generic embeddings don't understand that "liquidated damages" and "penalty clause" are related concepts under ICA Section 74
- No awareness of Indian Contract Act, Companies Act, SEBI, FEMA, or DPDP Act references

### Indian-Law-Trained Models (All Open Source, Ready to Use)

Key discovery: multiple open-source models already exist that are **pre-trained on Indian legal data**. No training from scratch is needed.

#### Generation Models — Indian Legal

| Model | Base | Parameters | Training Data | Performance | License | Source |
|-------|------|-----------|--------------|-------------|---------|--------|
| **AALAP-Mistral-7B** | Mistral 7B | 7B | 22,272 Indian legal instructions, 32K context | Beats GPT-3.5 on 31% of Indian legal tasks, ties on 34% | Apache 2.0 | OpenNyAI |
| **INLegalLlama** | LLaMA 2 | 7B | 702K Indian court cases (NyayaAnumana dataset) | 90% F1 prediction, 76% accuracy (vs LLaMA-2 base 57%) | Apache 2.0 | IIT Kanpur |
| **Indian-LawGPT** | LLaMA / ChatGLM | 7B | 500K Indian judgments + 300K legal Q&A | Less benchmarked, but trained on large Indian corpus | Apache 2.0 | Permissioned AI |
| **Indian Law Chat** | LLaMA 2 7B | 7B | Indian law corpus | GGUF available on HuggingFace (2,900+ downloads) | Apache 2.0 | Community |

**AALAP-Mistral-7B is the primary recommendation.** Built by OpenNyAI (a well-known Indian legal AI research organization), it was specifically instruction-tuned on Indian legal and paralegal tasks with 32K context — enough to process a full contract in one pass. It outperforms GPT-3.5-turbo on Indian legal queries.

#### Generation Models — English Legal (Broader)

| Model | Base | Parameters | Training Data | Strengths | License |
|-------|------|-----------|--------------|-----------|---------|
| **SaulLM-7B / Saul-Instruct-v1** | Mistral 7B | 7B | 30B+ English legal tokens | State-of-the-art English legal reasoning, clause extraction | MIT |
| **Cimphony-Mistral-Law-7B** | Mistral 7B | 7B | 600M legal tokens | Outperforms GPT-4 on legal benchmarks | Apache 2.0 |

These are strong on English contract analysis but lack Indian law awareness (ICA, Companies Act, SEBI, etc.).

#### Embedding Models

| Model | Dimensions | Training Data | License | Legal Understanding |
|-------|-----------|--------------|---------|-------------------|
| **InLegalBERT** | 768 | 5.4M Indian legal documents (SC + HC, 1950-2019) | MIT | Excellent — understands Indian legal terminology, statute references, case law vocabulary |
| all-minilm | 384 | Generic web text | Apache 2.0 | Poor — doesn't distinguish "termination for cause" from "termination for convenience" |

#### Available Indian Legal Datasets (for future fine-tuning)

| Dataset | Size | Contents | License |
|---------|------|---------|---------|
| **NyayaAnumana** | 702,945 cases | SC, HC, Tribunals, District Courts (up to 2024) | Apache 2.0 |
| **BhashaBench-Legal** | 24,365 questions | 20+ legal domains, English + Hindi | Open |
| **PredEx** | 56,000+ cases | Indian judgment prediction with explanations | Open |
| **ILDC** | 35,000 cases | Indian Legal Documents Corpus with annotations | Open |
| **Indian Kanoon API** | 3 Cr+ documents | Every court order, judgment, statute in India | API (free tier) |

#### Recommended Stack

```
Production (client deployment):
  Generation:  AALAP-Mistral-7B (7B)  — Indian-law-trained, beats GPT-3.5, 32K context
  Embeddings:  InLegalBERT (768-dim)  — trained on 5.4M Indian legal documents
  Cost:        ₹0 (both Apache 2.0 / MIT, free to download and deploy commercially)

Development (local laptop, 8 GB RAM):
  Generation:  phi-3-mini (3.8B, Q4)  — best quality-to-RAM ratio for dev
  Embeddings:  all-minilm (384-dim)   — lightweight for dev/testing

Fallback / English-heavy contracts:
  Generation:  Saul-Instruct-v1 (7B)  — if contracts are purely English with no Indian law context
```

### Why AALAP Over Training Our Own

| Approach | Cost | Time | Quality |
|----------|------|------|---------|
| Use AALAP as-is | ₹0 | 1 day (convert + deploy) | Beats GPT-3.5 on Indian law |
| Fine-tune AALAP further | ₹200-400 | 1 week | Even better on contract-specific tasks |
| Train Saul-7B on Indian data from scratch | ~₹1,00,000 | 5 weeks | Uncertain — may not beat AALAP |
| Full pre-training from scratch | ₹5-15L | 2-3 months | Only justified at 10+ clients |

**AALAP already did the hard work.** The researchers at OpenNyAI spent months training on Indian legal data. We use their model for free and focus our effort on the product, not the model.

### Model Switching

The app supports model switching via `application.yml` — no code changes required. The architecture is model-agnostic through LangChain4j's Ollama integration.

---

## 2. Infrastructure Analysis: Where to Host

### Development Laptop (MacBook Pro M1, 8 GB RAM)

SaulLM-7B cannot run comfortably on 8 GB. With macOS, Postgres, Spring Boot, and the model, swap thrashing makes it unusable.

Best option for local dev: `phi-3-mini` (3.8B, Q4) at ~2.3 GB, leaving 5 GB for everything else.

### Cloud CPU (No GPU)

| Instance | RAM | vCPUs | Cost/month | Saul-7B Q4 Speed | Verdict |
|----------|-----|-------|-----------|-----------------|---------|
| AWS c6i.2xlarge | 16 GB | 8 | ~$200 (~₹17K) | 3-5 tok/sec | Usable for demo |
| AWS c6i.4xlarge | 32 GB | 16 | ~$400 (~₹34K) | 8-12 tok/sec | Good for production |
| Hetzner CCX33 | 32 GB | 8 | ~$50 (~₹4.2K) | 6-10 tok/sec | Best value (but no India DC) |

A 200-word legal answer (~300 tokens) at 8 tok/sec = ~37 seconds. Acceptable — lawyers are used to waiting for research.

### Cloud GPU in India (Data Sovereignty)

**E2E Networks (Mumbai/Delhi/Bangalore data centers, INR pricing):**

| GPU | VRAM | Price/hr | Price/month | Saul-7B Q4 Speed | Response Time |
|-----|------|---------|-------------|-----------------|---------------|
| **L4 (24 GB)** | 24 GB | ₹49/hr | **₹30,762/mo** | 30-40 tok/sec | 8-10 sec |
| A100 (40 GB) | 40 GB | ₹170/hr | ₹75,000/mo | 80-100 tok/sec | 3-4 sec |
| H100 (80 GB) | 80 GB | ₹249/hr | ₹1,56,322/mo | 150+ tok/sec | 2 sec |

**Other Indian GPU providers:**
- Jarvislabs: A100 40GB ~₹107/hr (~₹77K/mo), USD pricing
- AceCloud: A100 ~₹185/hr (~₹1.35L/mo)

### Recommended Production Setup

**E2E Networks L4 — ₹30,762/month (~₹3.7L/year)**

```
NVIDIA L4 (24 GB VRAM) + 16 vCPU + 64 GB RAM
├── Ollama + AALAP-Mistral-7B (Q4_K_M)  → 4.5 GB VRAM
├── Ollama + InLegalBERT (embeddings)    → 0.4 GB VRAM
├── PostgreSQL + PGVector                → RAM
├── Spring Boot Backend                  → RAM
└── 19 GB VRAM free for KV-cache & concurrent queries
```

Why L4 over CPU:
- Only ~₹6,000/month more than a comparable CPU instance
- 3-4x faster responses (10 sec vs 35 sec)
- Room to upgrade to larger models without infra change
- "GPU-powered private AI" is a stronger sales pitch

---

## 3. Total Cost of Ownership for Clients

### Annual Running Cost (Cloud GPU Deployment)

```
Infrastructure (Annual)
─────────────────────────────────────────
E2E L4 GPU hosting (₹30,762/mo)     ₹3,69,144
Domain + SSL certificate                ₹2,000
Backup storage (100 GB)                ₹12,000
Monitoring / alerting                   ₹6,000
─────────────────────────────────────────
Infra subtotal                       ₹3,89,144

Our Service Fee (Annual)
─────────────────────────────────────────
Managed service + support            ₹2,00,000
─────────────────────────────────────────

TOTAL ANNUAL COST TO CLIENT:         ~₹5.9L/year
```

Plus one-time deployment fee of ₹3-5L in year one.

### ROI for a 10-20 Cr Turnover Firm

What they spend today without AI:
- 1 junior associate salary: ₹6-10L/year
- Manual contract review: 4-6 hours per NDA
- Precedent search: 2-3 hours per query
- Risk of missed clauses: one bad miss = ₹10L+ in damages

What AI gives them:
- Contract review: 4-6 hours → 30 minutes
- Precedent search: 2-3 hours → 30 seconds
- Clause risk flagging: automatic, never misses
- Institutional knowledge preserved when associates leave

If it saves 1 associate's 30% time → ₹2-3L saved.
If it prevents 1 missed clause per year → ₹10L+ risk avoided.
If it speeds up 50 reviews/year by 4 hours → 200 billable hours freed → ₹10-20L revenue potential.

₹5-6L/year for ₹15-25L of value is an easy sell.

### Scaling Economics (Multi-Tenant)

After 3+ clients on shared infrastructure (with per-tenant data isolation via separate PGVector schemas):
- Hosting cost per client drops to ~₹1.2L/year
- Charge each client ₹3L/year
- Better margins, lower price point, faster sales cycle

---

## 4. Product Positioning: AI Co-Pilot, Not a Platform

### The Key Insight

Mid-tier Indian law firms already have tools:
- Document storage: Google Drive / SharePoint / NAS
- Communication: Outlook / Gmail
- Matter tracking: Excel / Clio / Counsel Crest
- Drafting: MS Word (always Word)
- Signatures: Leegality / DocuSign
- Billing: Tally / custom ERP

They will not rip out existing systems for a new "legal platform." If we build "another legal management tool" we are dead. They already have 5 tools and don't want a 6th.

### What We Are

An **AI brain that plugs into what they already use.**

```
                    ┌──────────────────────┐
                    │   THEIR EXISTING     │
                    │   WORLD              │
                    │                      │
   Google Drive ────┤                      │
   SharePoint  ────┤   Legal Partner AI    │
   Email attach ────┤   (the AI layer)     │────→ Answers in browser
   Local files ────┤                      │────→ Alerts via email
                    │   "Upload once,      │────→ Reports as PDF
                    │    query forever"    │
                    │                      │
                    └──────────────────────┘
```

### The Pitch

> "Connect your Google Drive. We'll read every contract you've ever done. Then ask anything — find clauses, compare contracts, flag risks. Your team keeps using Word, Drive, everything as-is. We just make it searchable and intelligent. Zero disruption. Try it in a week."

### What We Build vs What We Don't

**Build (AI-powered intelligence):**
- AI contract analysis with clause-level risk scoring
- AI semantic search across all documents
- AI document comparison (legal-aware, not just text diff)
- AI clause library (auto-extracted from uploaded contracts)
- AI compliance checker (Indian law references)
- Audit trail (compliance-grade logging)

**Don't build (they already have these):**
- Matter management
- Billing / invoicing
- Calendar / deadline management
- Email integration
- Document storage
- Workflow / approvals
- Client portal
- E-signatures

---

## 5. Core Feature Depth (6 Features, Done Deeply)

### Feature 1: INGEST
Upload folder / connect Drive / drop files. Auto-chunk, embed, index. Done.
- Support PDF, DOCX, HTML, scanned (OCR via Tika)
- Legal-aware chunking at clause/section boundaries
- Encrypted storage (AES-256)
- Progress indicator for large uploads

### Feature 2: ASK (Semantic Search + RAG)
Natural language queries across the entire document portfolio.
- "What are our standard indemnity caps?"
- "Which contracts expire in Q1 2027?"
- "Find all non-compete clauses across our NDAs"
- Answers with citations: document name, page number, clause reference
- Powered by Saul-7B with legal-aware RAG pipeline

### Feature 3: REVIEW (Clause-Level Risk Scoring)
Upload a new contract → AI analyzes every clause:
- HIGH risk: "Unlimited liability — unusual for this contract type"
- MEDIUM: "Non-compete 3 years — exceeds norms under ICA Section 27"
- LOW: "30-day termination notice — market standard"
- Each finding cites the specific clause and relevant Indian law section
- One-click executive summary (partners review 50-page contracts in 2 minutes)

### Feature 4: COMPARE (Legal-Aware Diff)
Compare a new draft against standard template or previous version.
- Not just text diff — legal-aware analysis
- "Counterparty changed liability cap from ₹1Cr to ₹10L"
- "3 new clauses added that aren't in our template — flagged for review"
- Side-by-side with color-coded risk indicators

### Feature 5: CLAUSE BANK (Auto-Built Knowledge Base)
Auto-extracted from all uploaded contracts. Grows as the firm uploads more.
- "Show me all force majeure clauses we've used"
- Filterable by client, contract type, year, risk level
- "How did we handle indemnity in the Reliance deal last year?"
- The firm's institutional memory — doesn't walk out when associates leave

### Feature 6: AUDIT (Compliance-Grade Logging)
Every query, every action, every user — logged.
- Who asked what, when, about which document
- Export to PDF/CSV for compliance reporting
- Role-based visibility (Partners see all, Associates see own)
- Retention policy configurable per firm

---

## 6. Model Arena (Future — Sales Differentiator)

Let clients compare model performance on their own documents:

```
POST /api/arena/compare
{
  "documentId": "nda-acme-123",
  "query": "What are the termination clauses?",
  "models": ["tinyllama", "phi3", "saul-instruct-v1", "inlegalllama"]
}
```

Each model answers the same question. Side-by-side display shows:
- Answer completeness (did it find all clauses?)
- Speed (time to generate)
- Confidence score
- Cost per query

This builds trust ("see for yourself why the legal model is better") and justifies the GPU cost.

---

## 7. Revised Pricing (Lean Approach)

### Option A: Cloud-Hosted (Recommended)

```
Deployment:     ₹3,00,000 - 5,00,000 (one-time)
                Includes setup, migration of existing contracts, training

Annual Service: ₹2,00,000 - 3,00,000 / year
                Includes hosting (E2E L4 GPU), support, model updates

Total Year 1:   ₹5,00,000 - 8,00,000
Total Year 2+:  ₹2,00,000 - 3,00,000 / year
```

### Option B: On-Premise (Client Buys Hardware)

```
Hardware:       ₹2,00,000 - 4,00,000 (one-time, client purchases)
                24 GB RAM, 8 TB storage, RTX 4060/4070 GPU

Deployment:     ₹3,00,000 - 5,00,000 (one-time)
                Software license, setup, migration, training

Annual Service: ₹1,50,000 - 2,50,000 / year
                Software updates, model upgrades, priority support

Total Year 1:   ₹6,00,000 - 11,00,000
Total Year 2+:  ₹1,50,000 - 2,50,000 / year
```

### Option C: Monthly SaaS-Style

```
Monthly:        ₹45,000 / month (hosting + support bundled)
                = ₹5,40,000 / year

Setup fee:      ₹2,00,000 (one-time, includes migration)

Total Year 1:   ₹7,40,000
Total Year 2+:  ₹5,40,000 / year
```

---

## 8. Model Training Roadmap (If Further Customization Needed)

AALAP-Mistral-7B is production-ready out of the box. However, if we want to further specialize — for example, on contract review specifically, or on a client's domain — here is the training ladder:

### Level 1: RAG Only (Current Approach) — ₹0, Immediate

No model training. Upload client documents, embed with InLegalBERT, query with AALAP. The model reads relevant document chunks as context when answering. Covers ~80% of use cases (search, review, compare). Falls short when asking about statutes or concepts not present in uploaded documents.

### Level 2: QLoRA Fine-Tuning on Contract Tasks — ₹15K, 1 Week

Take AALAP (already Indian-law-aware) and further fine-tune with QLoRA on 3,000-10,000 contract-specific instruction-response pairs. This teaches the model contract review patterns on top of its existing Indian law knowledge.

**Data sources for training pairs (all free, public):**
- NyayaAnumana: 702,945 Indian court cases (Apache 2.0)
- Indian Kanoon API: 3 Cr+ court orders and statutes (api.indiankanoon.org)
- India Code: 800+ central Acts from indiacode.nic.in
- SEBI/RBI circulars from sebi.gov.in, rbi.org.in

**Training pair format (JSONL):**
```json
{"instruction": "Identify risks in this clause under Indian law",
 "input": "[non-compete clause text]",
 "output": "HIGH RISK: This non-compete extending 5 years is likely void under Section 27 of ICA 1872. Indian courts in Percept D'Mark vs Zaheer Khan (2006) held post-employment non-competes void..."}
```

**Hardware:** Existing L4 GPU (4-8 hours), cost ~₹200-400 for GPU time. ~₹10-15K for GPT-4/Claude API to generate Q&A pairs from raw legal text.

**QLoRA config:**
- Base: AALAP-Mistral-7B
- Quantization: 4-bit NF4
- LoRA rank: 16, alpha: 32
- Target modules: q_proj, k_proj, v_proj, o_proj
- Epochs: 3, batch size: 4

**Export:** Merge LoRA adapter, convert to GGUF, import to Ollama as `legal-partner-indian`. One line change in `application.yml`.

### Level 3: Continual Pre-Training — ~₹1L, 5 Weeks

Take AALAP and further pre-train on 50+ GB of raw Indian legal text (NyayaAnumana + Indian Kanoon bulk). This deepens the model's understanding of Indian legal language at a fundamental level, beyond what instruction tuning alone can achieve.

**Hardware:** 4x A100 40GB on E2E Networks for 3-5 days (~₹82,000) + 1 week data prep + 1 week eval.

Only justified after 5+ paying clients. The ₹1L cost is amortized across all deployments.

### Level 4: Client-Specific Model (Premium Add-On) — ₹2-3L

Train a custom model on a specific firm's 10 years of contracts. This creates a model that "thinks like the firm" — knows their clause preferences, standard positions, past negotiations.

**Revenue opportunity:** Charge ₹2-3L as a premium add-on per client. ₹50K/year to retrain with new data. Ultimate lock-in — the AI embodies their institutional knowledge.

### Training Timeline

```
NOW:              Use AALAP + InLegalBERT as-is (₹0)
FIRST CLIENT:     QLoRA fine-tune on contract tasks (₹15K)
3-5 CLIENTS:      Full continual pre-training (₹1L)
10+ CLIENTS:      Client-specific models as premium upsell (₹2-3L each)
```

---

## 9. Build Roadmap (8 Weeks to Demo-Ready)

### Weeks 1-2: Model Upgrade + Review Quality
- Swap TinyLlama → AALAP-Mistral-7B (generation) — Indian-law-trained, 32K context
- Swap all-minilm → InLegalBERT (embeddings) — trained on 5.4M Indian legal docs
- Update PGVector embedding dimension (384 → 768)
- Re-embed all existing documents
- Update prompt templates for AALAP's instruction format
- Improve risk scoring with Indian law references (ICA, Companies Act, SEBI)

### Weeks 3-4: Clause-Level Intelligence
- Auto clause extraction on every upload (termination, indemnity, liability, confidentiality, governing law, force majeure, IP, non-compete)
- Clause-level risk scoring (HIGH/MEDIUM/LOW with legal reasoning)
- Indian Contract Act section mapping for each risk finding
- One-click executive summary generation

### Weeks 5-6: Clause Library + Search
- Auto-build clause library from all uploaded documents
- Semantic search across entire document portfolio
- Filter by clause type, client, contract type, year, risk level
- "Show me how we handled X in previous contracts"

### Weeks 7: Smart Comparison
- Upgrade from text diff to legal-aware comparison
- Material change detection ("liability cap changed from X to Y")
- New clause / removed clause identification
- Risk impact assessment of changes

### Week 8: Polish + Demo
- UI refinements for demo readiness
- PDF export for risk reports and clause summaries
- Demo dataset with realistic Indian contracts
- Sales deck + live demo script

---

*Document version: 2.0 | February 2026*
