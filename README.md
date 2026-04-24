# Legal Partner — AI Contract Drafting & Intelligence Platform

An AI-powered contract drafting and review platform with a **deal term enforcement engine** that guarantees correctness. Combines a fine-tuned legal LLM (SaulLM-54B) with deterministic rendering from a curated golden clause library — the model writes prose, the system guarantees legal accuracy.

## What It Does

| Feature | How It Works |
|---|---|
| **Contract Drafting** | Deterministic-first rendering from 84 golden clauses + LLM for prose. DealSpec extraction → golden clause resolution → rule validation → fix engine |
| **Risk Assessment** | 104 structured YES/NO questions (CUAD-based). LLM extracts facts with evidence quotes → code computes risk score. No hallucinated opinions |
| **Document Summary** | Full contract summarized with key terms, red flags. Works on uploaded docs + generated drafts |
| **Contract Q&A** | Ask questions about any document. Answers cite section numbers. Grounded in document text |
| **Clause Checklist** | Per-section extraction: parties, dates, fees, governing law, liability cap. Config-driven fields per contract type |

## Architecture

```
User → Frontend (React) → Backend (Spring Boot) → LLM (SaulLM-54B via vLLM)
                                ↓
                    ┌───────────────────────┐
                    │  Enforcement Engine    │
                    │  ┌─────────────────┐  │
                    │  │ DealSpec        │  │  ← Extract structured terms from deal brief
                    │  │ Rule Engine     │  │  ← 42 rules + 5 BLOCK rules from YAML
                    │  │ Golden Clauses  │  │  ← 84 curated clauses from real contracts
                    │  │ Fix Engine      │  │  ← RETRY → INJECT → FLAG escalation
                    │  │ Normalizer      │  │  ← Dedup, numbering, cross-ref fix
                    │  │ Risk Questions  │  │  ← 104 CUAD-based structured questions
                    │  └─────────────────┘  │
                    └───────────────────────┘
                                ↓
                    PostgreSQL + PGVector (RAG, documents, embeddings)
```

## Quick Start

### Prerequisites
- Docker + Docker Compose v2
- RunPod account (for GPU serving) OR Gemini API key (free tier)
- Node.js 18+ (frontend dev only)

### 1. Start Backend (Docker)
```bash
docker compose up -d
```
Starts: PostgreSQL + PGVector, Ollama (embeddings), Spring Boot API, OnlyOffice

### 2. Start LLM Server (RunPod)
```bash
export HF_TOKEN=hf_xxx NGROK_TOKEN=xxx
./deploy/saullm-vllm-serve.sh
```
Creates A100 pod, serves SaulLM-54B-AWQ via vLLM at 62 tok/s through ngrok.

### 3. Deploy to Test VM (GCP)
```bash
bash deploy/lp deploy test-vm
```

### 4. Alternative: Use Gemini Flash (no GPU needed)
Set in `.env`:
```
LEGALPARTNER_CHAT_PROVIDER=gemini
LEGALPARTNER_GEMINI_API_KEY=AIzaSy...
```

## Config-Driven Architecture

Everything is configurable via YAML — no code changes needed for most customizations:

| Config File | What It Controls |
|---|---|
| `contract_types.yml` | Contract templates, party roles, required fields, banned terms, default sections |
| `clauses.yml` | Clause types, expected sub-clauses, search queries, forbidden headings |
| `golden_clauses.yml` | 84 curated legal clauses per contract type + jurisdiction |
| `clause_requirements.yml` | 42 validation rules + 5 BLOCK rules + deterministic templates |
| `risk_questions.yml` | 104 risk assessment questions + configurable prompts + extraction fields |
| `industry_regulations.yml` | Industry → regulatory reference mapping per jurisdiction |
| `party_name_variants.yml` | Party name normalization variants |
| `application.yml` | LLM parameters, timeouts, storage paths, defaults |

### Adding a new contract type
1. Add entry to `contract_types.yml` (party roles, sections, required fields)
2. Add golden clauses to `golden_clauses.yml`
3. Restart server. Done.

### Adding a new risk question
1. Add entry to `risk_questions.yml` under the clause type
2. Restart server. Done.

## Supported Contract Types

| Type | Party Roles | Clauses |
|---|---|---|
| Software License | Licensor / Licensee | 11 (incl. warranties, force majeure) |
| SaaS Subscription | Provider / Customer | 11 |
| Master Services | Service Provider / Client | 11 |
| Non-Disclosure | Disclosing / Receiving Party | 6 |
| Employment | Employer / Employee | 8 |
| Supply | Supplier / Buyer | 9 |
| IP License | Licensor / Licensee | 7 |

## Draft Generation Flow

```
1. User provides deal brief (free text) + selects contract type
2. /draft/validate → extracts DealSpec, shows preview with clause selection
3. User confirms → /draft/async starts generation
4. For each clause:
   a. Golden clause available? → render deterministically (no LLM)
   b. No golden clause? → LLM generates → rule engine validates → fix engine repairs
5. DraftNormalizer: dedup, renumber, fix cross-refs, strip meta-commentary
6. Final HTML + DOCX generated
7. Draft parameters stored separately (not in exported document)
```

## Risk Assessment Flow

```
1. Read full document (uncapped)
2. Inventory clauses by keyword search (no LLM)
3. For each clause: ask 8-10 YES/NO questions with evidence quotes (LLM)
4. Code computes risk from answers (deterministic)
5. Flag missing required clauses (no LLM)
6. Return: score (0-100), per-clause breakdown, key findings, evidence
```

## Key Design Decisions

- **LLM = prose writer, System = contract brain.** The model fills in language; the enforcement engine guarantees correctness.
- **Deterministic-first.** Golden clauses render before LLM generates. LLM is the fallback, not the primary.
- **Config over code.** Add a new clause type, risk question, or contract template without touching Java.
- **Structured extraction over opinion generation.** Risk assessment asks YES/NO with evidence, not "analyze risks."
- **No hardcoded defaults.** Unresolved placeholders render as visible fill-in fields, not guessed values.

## LLM Serving

| Model | Quantization | Speed | Cost |
|---|---|---|---|
| SaulLM-54B-Instruct-AWQ | AWQ W4A16 | 62 tok/s | $1.50/hr (A100) |
| Gemini Flash (alternative) | N/A | ~100 tok/s | ~$0.01/req |

AWQ model: `jyoti0512shukla/SaulLM-54B-Instruct-AWQ` (private, 23GB)

### Deploy scripts
- `deploy/saullm-vllm-serve.sh` — one-shot: create pod → install vLLM → serve AWQ → ngrok
- `deploy/quantize_saullm_autoawq.py` — one-time AWQ quantization (~45 min on A100)

## Project Structure

```
legal-partner/
├── backend/                          # Spring Boot 3.2 + Gradle
│   ├── src/main/java/.../
│   │   ├── config/                   # Registries (clause, contract type, industry, party names)
│   │   ├── controller/               # REST endpoints
│   │   ├── model/                    # Entities + DTOs (DealSpec, DraftRequest, etc.)
│   │   ├── service/                  # Core logic
│   │   │   ├── DraftService.java     # Draft orchestration + enforcement
│   │   │   ├── ClauseRuleEngine.java # 42 rules from YAML
│   │   │   ├── FixEngine.java        # BLOCK → RETRY → INJECT → FLAG
│   │   │   ├── GoldenClauseLibrary.java  # 84 curated clauses
│   │   │   ├── DealSpecExtractor.java    # LLM + regex hybrid extraction
│   │   │   ├── DealCoverageScore.java    # Per-clause coverage scoring
│   │   │   ├── RiskQuestionEngine.java   # 104 questions from YAML
│   │   │   ├── DraftNormalizer.java      # Dedup, numbering, cross-refs
│   │   │   ├── DraftIntakeValidator.java # Pre-generation validation
│   │   │   └── HtmlToDocxConverter.java  # Draft → Word conversion
│   │   └── rag/                      # RAG pipeline (chunker, retriever, reranker)
│   └── src/main/resources/config/    # ALL YAML configs
│       ├── clauses.yml               # Clause type definitions
│       ├── contract_types.yml        # Contract templates + party roles
│       ├── golden_clauses.yml        # 84 curated clauses
│       ├── clause_requirements.yml   # Validation rules + BLOCK rules
│       ├── risk_questions.yml        # 104 risk questions + prompts
│       ├── industry_regulations.yml  # Regulatory references
│       └── party_name_variants.yml   # Party name normalization
├── frontend/                         # React 18 + Vite + Tailwind
│   └── src/pages/
│       ├── DraftPage.jsx             # Draft generation with preview + clause selection
│       ├── ContractReviewPage.jsx    # Risk assessment + summary + Q&A + checklist
│       └── DraftsRecentStrip.jsx     # Recent drafts with duration
├── deploy/
│   ├── saullm-vllm-serve.sh         # One-shot GPU serving
│   ├── saullm-serve.sh              # Legacy FastAPI serving
│   ├── quantize_saullm_autoawq.py   # AWQ quantization
│   ├── lp                           # Deploy CLI
│   └── customers/test-vm/.env       # Test VM config
├── data/                             # Sample contracts + generated drafts
└── docs/
    ├── RUNPOD_SETUP_ISSUES.md        # 14 documented GPU issues + fixes
    └── ROADMAP.md                    # Feature roadmap
```

## Tech Stack

**Backend**: Spring Boot 3.2.5, Java 21, LangChain4j 0.35.0, Apache Tika, docx4j, Flyway, PostgreSQL 16 + PGVector

**Frontend**: React 18, Vite, Tailwind CSS, Axios, Lucide React

**LLM**: SaulLM-54B-Instruct (Mixtral MoE, AWQ quantized), served via vLLM 0.19.1

**Infrastructure**: Docker Compose, RunPod (GPU), GCP (test VM), ngrok (tunneling), GitHub Actions (CI/CD)
