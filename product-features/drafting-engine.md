# Drafting Engine — Legal Partner

The full contract drafting pipeline — from section planning through per-clause QA to coherence scan.

---

## 1. Drafting Modes

| Mode | Endpoint | What it produces |
|------|----------|-----------------|
| Full agreement (sync) | `POST /api/v1/ai/draft` | Complete multi-section contract HTML |
| Full agreement (streaming) | `POST /api/v1/ai/draft/stream` | Same, with real-time SSE progress |
| Single clause (workflow) | Via DRAFT_CLAUSE workflow step | One clause with RAG context |
| Clause refinement | `POST /api/v1/ai/refine-clause` | Edited clause based on instruction |

---

## 2. Full Agreement Pipeline

```
1. PLAN SECTIONS
   LLM decides which of 12 sections the contract needs
   Input: contract type, parties, practice area, industry, deal brief
   Output: ordered list of section keys
   Fallback: template-specific defaults if LLM fails
       ↓
2. FOR EACH SECTION (sequentially):
   a. Retrieve RAG context (corpus + clause library)
   b. Build prompt with 5 layers:
      - Clause-specific system prompt
      - Terminology manifest (from DEFINITIONS clause)
      - RAG grounding mandate
      - Content guardrails
      - User prompt with deal context
   c. Call LLM
   d. Strip LLM artifacts (12-step cleanup)
   e. Sanitize to HTML
   f. Run 7 QA checks
   g. If QA fails: retry with feedback (up to 2 retries, best-of-N tracking)
   h. Phase 3: if only missing sub-clauses, regenerate just those
   i. Post-process remaining placeholders as last resort
       ↓
3. COHERENCE SCAN
   Cross-clause consistency:
   - Party name drift detection
   - Defined term usage verification
       ↓
4. RETURN
   draftHtml, suggestions (with RAG reasoning), qaWarnings, coherenceIssues
```

---

## 3. 11 Contract Templates

| Template ID | Name |
|-------------|------|
| nda | Non-Disclosure Agreement |
| msa | Master Services Agreement |
| saas | SaaS Subscription Agreement |
| software_license | Software License Agreement |
| vendor | Vendor Agreement |
| supply | Supply Agreement |
| employment | Employment Agreement |
| ip_license | IP License Agreement |
| clinical_services | Clinical Services Agreement |
| fintech_msa | Fintech Master Services Agreement |
| custom | Custom (user-defined) |

### Default sections per template

| Template | Sections |
|----------|----------|
| NDA | DEFINITIONS, CONFIDENTIALITY, LIABILITY, TERMINATION, GOVERNING_LAW, GENERAL_PROVISIONS |
| Software / IP License | DEFINITIONS, IP_RIGHTS, PAYMENT, LIABILITY, TERMINATION, GOVERNING_LAW, GENERAL_PROVISIONS |
| Employment | DEFINITIONS, SERVICES, PAYMENT, CONFIDENTIALITY, IP_RIGHTS, TERMINATION, GOVERNING_LAW, GENERAL_PROVISIONS |
| Supply | DEFINITIONS, SERVICES, PAYMENT, FORCE_MAJEURE, LIABILITY, TERMINATION, GOVERNING_LAW, GENERAL_PROVISIONS |
| SaaS / Fintech / Clinical | DEFINITIONS, SERVICES, PAYMENT, CONFIDENTIALITY, DATA_PROTECTION, LIABILITY, TERMINATION, GOVERNING_LAW, GENERAL_PROVISIONS |
| Default (MSA, Vendor, Custom) | DEFINITIONS, SERVICES, PAYMENT, CONFIDENTIALITY, IP_RIGHTS, LIABILITY, TERMINATION, GOVERNING_LAW, GENERAL_PROVISIONS |

---

## 4. 12 Clause Specifications

| Key | Title | Expected Sub-clauses |
|-----|-------|---------------------|
| DEFINITIONS | Definitions | 7 |
| SERVICES | Services | 3 |
| PAYMENT | Fees and Payment | 4 |
| CONFIDENTIALITY | Confidentiality | 5 |
| IP_RIGHTS | Intellectual Property Rights | 4 |
| LIABILITY | Liability and Indemnity | 5 |
| TERMINATION | Termination | 4 |
| FORCE_MAJEURE | Force Majeure | 5 |
| REPRESENTATIONS_WARRANTIES | Representations and Warranties | 5 |
| DATA_PROTECTION | Data Protection and Privacy | 5 |
| GOVERNING_LAW | Governing Law and Dispute Resolution | 4 |
| GENERAL_PROVISIONS | General Provisions | 8 |

---

## 5. Prompt Construction (5 layers)

Each clause prompt is built from 5 layers:

### Layer 1: Clause-specific system prompt
From `PromptTemplates`. Sets the LLM's role as a legal drafting expert for this specific clause type.

### Layer 2: Terminology manifest
Extracted from the DEFINITIONS clause (if already generated). Contains:
- **Party naming mandate:** "Refer to ONLY as [Party A Name] and [Party B Name]"
- **Defined terms consistency:** List of terms the LLM must use consistently

### Layer 3: RAG grounding mandate
When RAG chunks are available:
```
=== RAG AUTHORITY MANDATE — CRITICAL ===
Follow firm precedent structure exactly. The following RAG context
contains authoritative clause examples from the firm's corpus.
Use them as structural templates.
[RAG chunks here]
=== END RAG AUTHORITY ===
```

### Layer 4: Content guardrails
`DRAFT_CONTENT_GUARDRAILS` — instructions to avoid placeholders, maintain consistent terminology, produce complete legal text.

### Layer 5: User prompt with deal context
Clause-specific user prompt template filled with: contract type, jurisdiction, counterparty type, practice area, deal brief, client position, industry regulations, draft stance.

---

## 6. Deal Context Enrichment

### Client position
- `PARTY_A` → "draft clauses favourably for Party A"
- `PARTY_B` → "draft clauses favourably for Party B"
- Default → "balanced terms preferred"

### Industry regulations (auto-injected)
- **FINTECH:** "RBI guidelines, FEMA 1999, Payment & Settlement Systems Act 2007"
- **PHARMA:** "Drugs and Cosmetics Act 1940, Clinical Establishment Act 2010, DPDPA 2023"
- **IT_SERVICES:** "IT Act 2000, DPDPA 2023, SEBI (if listed entity)"
- **MANUFACTURING:** "Factories Act 1948, Environment Protection Act 1986, GST Act 2017"

### Draft stance
- **FIRST_DRAFT:** "maximise protections, strong caps, broad indemnity carve-outs, liberal termination rights"
- **FINAL_OFFER:** "firm but commercially reasonable, avoid overreaching terms"
- **BALANCED** (default): "balanced, commercially standard terms"

---

## 7. Per-Clause QA — 7 Checks

| # | Check | What it catches |
|---|-------|----------------|
| 1 | Placeholder detection | `[some text]`, `(insert ...)`, `%MARKER%`, `TBC`, `TBD` |
| 2 | Sub-clause count | Fewer sub-clauses than expected (e.g., 2 out of 5) |
| 3 | Heading-only detection | Sub-clause body < 40 chars (just a heading, no substance) |
| 4 | Overall length | Plain text < 200 chars (clause is too short) |
| 5 | LLM artifact detection | Raw JSON, LaTeX, code comments, instruction tokens |
| 6 | Contract-type contamination | Wrong-domain terms (e.g., "landlord" in SaaS, "uptime" in NDA) |
| 7 | Semantic requirements | Missing expected keywords (e.g., PAYMENT must have "invoice", "due date") |

### Contamination signals

| Contract Type | Contamination terms |
|---------------|-------------------|
| SaaS | real property, lease agreement, lessee, lessor, landlord, tenant, mortgage, premises, rental |
| NDA | service level, uptime, subscription fee, software license, source code, purchase order |
| Employment | saas platform, uptime guarantee, api access, software subscription, real property |
| Supply | software license, saas, uptime, source code, real property, employment |
| MSA | real property, lease, tenant, mortgage, employment contract |

### Semantic requirements per clause

| Clause | Required keywords (at least one must appear) |
|--------|----------------------------------------------|
| PAYMENT | invoice, "net ", due date, late payment |
| LIABILITY | shall not exceed, aggregate, indemnif |
| TERMINATION | written notice, material breach, effect |
| CONFIDENTIALITY | confidential information, shall not disclose, exception |
| IP_RIGHTS | ownership, license, intellectual property |
| FORCE_MAJEURE | force majeure, notification, suspend |
| GOVERNING_LAW | governed by, jurisdiction, dispute |
| DATA_PROTECTION | personal data, security, breach |
| REPRESENTATIONS_WARRANTIES | authoris, compliance, conflict |

---

## 8. QA Retry Logic (Best-of-N)

```
Attempt 1: Generate clause
    ↓ Run 7 QA checks → warnings list
    ↓ Score attempt (base 1000, -100 per warning, -200 for artifacts, +length bonus)
    ↓ Track as "best so far"
    ↓
If QA issues AND retries remain (max 2):
    ↓
    If ONLY missing sub-clauses:
        Phase 3: Ask LLM to generate ONLY the missing sub-clauses
        Splice onto existing HTML
    Else:
        Phase 2: Full retry with isolated feedback prompt listing all QA failures
    ↓
    Score new attempt
    If new score > best score: replace best
    If new score <= best score: keep previous best (don't regress)
    ↓
After all retries:
    Post-process remaining placeholders as last resort
    Run final QA on post-processed result
```

### Scoring formula

```
Base: 1000
Per QA warning: -100
LLM artifact warning: additional -200
Plain text < 200 chars: -300
Plain text < 400 chars: -100
Length bonus: +min(plainTextLength, 2000) / 10
```

---

## 9. 12-Step LLM Artifact Stripping

Applied to raw LLM output before HTML sanitization:

| Step | What it strips |
|------|---------------|
| 0 | Retry directive bleed-through markers (8 patterns) |
| 1 | Instruction tokens: Mistral `[INST]`, Gemma `<\|end_of_turn\|>`, Qwen `<\|im_start\|>`, bare role labels |
| 2 | Markdown code fences |
| 3 | JSON wrappers (extracts longest string value from known keys) |
| 4 | Standalone JSON lines |
| 5 | LaTeX notation (`$\text{Name}$` → `Name`, `\textbf{}`, `\section{}`) |
| 6 | Code comments (`//`, `/* */`) |
| 7 | Broken unicode escapes |
| 8 | ASCII table garbage |
| 9 | Residual JSON field names leaked inline |
| 10 | Content extraction from JSON field values |
| 11 | Multiple blank lines, trailing commas |
| 12 | **Loop detection:** Truncates at second occurrence of any 40+ char repeated sentence |

---

## 10. Coherence Scan

After all clauses are generated, a cross-clause consistency check runs:

### Party name drift
Detects when vendor synonyms (Vendor, Supplier, Company, Contractor, Provider) or client synonyms (Customer, Buyer, Purchaser, Recipient) appear that don't match the terminology manifest from the DEFINITIONS clause.

### Defined term usage
Checks if clauses use defined terms correctly (e.g., CONFIDENTIALITY clause should use "Confidential Information" when it's defined in DEFINITIONS).

---

## 11. Placeholder Post-Processing (Last Resort)

If QA retries fail to eliminate all placeholders, the system replaces common patterns with commercially reasonable defaults:

| Pattern | Replacement |
|---------|-------------|
| `[Party A]`, `[Service Provider]` | Actual party name |
| `[effective date]`, `(insert date)` | "the Effective Date" |
| `[X days]`, `[N months]` | "thirty (30) days" |
| `[amount]`, `[$]` | "the amounts set forth in the applicable Statement of Work" |
| `[jurisdiction]` | Actual jurisdiction from request |
| `[***]`, `[___]` | "as mutually agreed in writing by the Parties" |
| `TBD`, `TBC` | "as mutually agreed by the Parties in writing" |

---

## 12. Streaming Draft (SSE Events)

The streaming endpoint (`POST /api/v1/ai/draft/stream`) emits clause-by-clause progress:

| Event | When | Payload |
|-------|------|---------|
| `planning` | Section planning started | — |
| `start` | Planning complete | `{totalClauses, plannedSections, partialHtml}` |
| `clause_start` | Before each clause | `{clauseType, label, index, totalClauses}` |
| `clause_retry` | QA retry | `{clauseType, attempt, fixing}` |
| `clause_done` | Clause generated | `{clauseType, label, index, totalClauses, qaWarnings, partialHtml}` |
| `complete` | All done | `{draftHtml, suggestions, qaWarnings, coherenceIssues}` |
| `error` | Failure | `{message}` |

The `partialHtml` in each `clause_done` event shows the full draft with completed clauses and placeholder text for pending ones: `⏳ Generating {title} clause...`

---

## 13. Concurrency Control

`DraftService` uses a `Semaphore` initialized from `legalpartner.draft.max-concurrent` (default 2). Both sync and streaming endpoints acquire a permit before drafting. If no permit is available, returns HTTP 429 immediately — no queuing.

Timeout for streaming emitter: 300,000ms (5 minutes).
