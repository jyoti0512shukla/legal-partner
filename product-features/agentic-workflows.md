# Agentic Workflows — Legal Partner

This document describes every agentic workflow in the Legal Partner platform, including the exact execution logic, quality scoring rules, data flow between steps, notification mechanisms, and configuration options.

---

## Table of Contents

1. [Workflow Architecture Overview](#1-workflow-architecture-overview)
2. [Step Types](#2-step-types)
3. [Predefined Workflows](#3-predefined-workflows)
4. [Quality Loop — Self-Healing Execution](#4-quality-loop--self-healing-execution)
5. [Quality Scoring Rules (Per Step Type)](#5-quality-scoring-rules-per-step-type)
6. [Risk-Triggered Redraft](#6-risk-triggered-redraft)
7. [Conditional Step Execution](#7-conditional-step-execution)
8. [Cross-Step Data Flow](#8-cross-step-data-flow)
9. [Drafting Agent (Full Agreement)](#9-drafting-agent-full-agreement)
10. [Drafting Agent (Single Clause with QA Retry)](#10-drafting-agent-single-clause-with-qa-retry)
11. [Contract Review Agent](#11-contract-review-agent)
12. [Matter Agent (Cross-Document Analysis)](#12-matter-agent-cross-document-analysis)
13. [Review Pipeline (Human-in-the-Loop)](#13-review-pipeline-human-in-the-loop)
14. [Connectors — Email, Slack, Teams, Webhook](#14-connectors--email-slack-teams-webhook)
15. [Real-Time Streaming (SSE Events)](#15-real-time-streaming-sse-events)
16. [Custom Workflow Builder](#16-custom-workflow-builder)
17. [Workflow Analytics](#17-workflow-analytics)

---

## 1. Workflow Architecture Overview

Every agentic workflow in Legal Partner follows this execution pattern:

```
User triggers workflow (via UI or API)
    ↓
WorkflowService creates a WorkflowRun entity (status: PENDING)
    ↓
WorkflowExecutor spawns a Java virtual thread
    ↓
For each step in the workflow definition:
    ├── Evaluate condition (skip if false)
    ├── Retrieve RAG context for the step
    ├── Execute step with quality loop (up to 3 iterations)
    ├── Score output quality (heuristic, no LLM call)
    ├── If quality < 70/100: re-execute with feedback
    ├── Store result in shared results map
    └── Emit SSE event to frontend
    ↓
After all steps: check for risk-triggered redraft
    ↓
If risk == HIGH and DRAFT_CLAUSE step exists:
    Re-run from DRAFT_CLAUSE with risk feedback injected
    ↓
Update run status to COMPLETED
    ↓
Fire connectors (email, Slack, Teams, webhook) asynchronously
    ↓
Emit workflow_complete SSE event
```

**Key architectural decisions:**
- **Virtual threads** (Java 21) — non-blocking execution, no thread pool exhaustion
- **SSE streaming** — real-time progress to frontend (infinite timeout emitter)
- **Heuristic quality scoring** — no extra LLM call for quality assessment, uses structural checks
- **Results accumulate** — each step can read prior steps' outputs from the shared `results` map
- **Cancellation support** — user can cancel a running workflow; executor checks DB status before each step

---

## 2. Step Types

Seven step types are available. Each can be used in any workflow and combined in any order.

### EXTRACT_KEY_TERMS
Extracts structured fields from an uploaded contract: parties (A and B), effective date, governing law, jurisdiction, termination triggers, payment terms, and other key provisions.
- **Requires:** Uploaded document (returns `{note: "requires uploaded document"}` without one)
- **Implementation:** `aiService.extractKeyTerms(docId, username)`
- **Output format:** JSON object with extracted fields

### RISK_ASSESSMENT
Analyzes a contract (or drafted clause) for legal and commercial risks. Categorizes risks by area (LIABILITY, INDEMNITY, TERMINATION, IP_RIGHTS, CONFIDENTIALITY, GOVERNING_LAW, FORCE_MAJEURE, etc.), rates each HIGH/MEDIUM/LOW, provides justifications with section references, and computes an overall risk rating.
- **Accepts:** Uploaded document OR drafted text from a prior DRAFT_CLAUSE step
- **Uses:** RAG context for corpus-benchmarked risk comparison
- **Implementation:** `aiService.assessRiskWithContext(docId, username, ragContext, feedbackContext, draftedText)`
- **Output format:** `{overallRisk: "HIGH"|"MEDIUM"|"LOW", categories: [{name, rating, justification, sectionRef}]}`

### CLAUSE_CHECKLIST
Audits a contract against 12 canonical clause types. For each clause, determines if it's PRESENT, WEAK, or MISSING, assigns a risk level, identifies the section reference, and provides a finding and recommendation.
- **Requires:** Uploaded document
- **Implementation:** `contractReviewService.review(...)` using VLLM guided JSON generation
- **The 12 canonical clauses:**
  1. Limitation of Liability
  2. Indemnification
  3. Termination for Convenience
  4. Termination for Cause
  5. Force Majeure
  6. Confidentiality / NDA
  7. Governing Law
  8. Dispute Resolution / Arbitration
  9. Intellectual Property Ownership
  10. Data Protection
  11. Payment Terms
  12. Assignment / Change of Control
- **Overall risk computation:**
  - **HIGH** if 3+ high-risk clauses, OR 1+ high-risk clause that is MISSING
  - **MEDIUM** if 1+ high-risk clauses, OR 4+ medium-risk clauses
  - **LOW** otherwise

### GENERATE_SUMMARY
Produces an executive summary with top concerns and actionable recommendations. Receives the entire prior results map to synthesize across all preceding analysis steps.
- **Accepts:** Uploaded document OR drafted text, plus all prior step results
- **Implementation:** `aiService.generateWorkflowSummary(docId, priorResults, username, draftedText)`
- **Output format:** `{executiveSummary, topConcerns: [...], recommendations: [...]}`

### REDLINE_SUGGESTIONS
Generates specific replacement language for weak or missing clauses. Each suggestion includes the original text, the suggested replacement, the section reference, and a rationale explaining why the replacement is legally superior.
- **Accepts:** Uploaded document OR drafted text, plus all prior step results
- **Uses:** RAG context (firm clause library) for firm-grounded suggestions
- **Implementation:** `aiService.generateRedlinesWithContext(...)`
- **Output format:** `{suggestions: [{clauseType, originalText, suggestedLanguage, sectionRef, rationale}]}`

### DRAFT_CLAUSE
Drafts a single clause or a full agreement using RAG corpus + clause library context.
- **Two modes:**
  - **Clause mode** (default): Drafts a single clause type (e.g., `clauseType=LIABILITY`). Uses `aiService.draftClauseForWorkflow(...)`.
  - **Agreement mode** (`mode=agreement`): Drafts a complete multi-section contract. Creates a `DraftRequest` from merged params, uses `draftService.generateDraft()`. Returns `{mode: "agreement", draftHtml, suggestions}`.
- **Configurable params:** `clauseType`, `mode`, `contractTypeName` (default "Master Services Agreement"), `draftStance` (default "BALANCED")
- **Merges with runtime `draftContext`:** Party names, jurisdiction, practice area, deal brief, etc.

### SEND_FOR_SIGNATURE
Placeholder for DocuSign/e-signature integration.
- **Currently returns:** `{status: "pending", note: "requires DocuSign integration"}`

---

## 3. Predefined Workflows

Seven workflows are seeded on application startup. Users can also build custom workflows.

### 3.1 Due Diligence
Full contract analysis — extract, assess, audit, summarize.

| Step | Type | Label | Max Iterations |
|------|------|-------|----------------|
| 1 | EXTRACT_KEY_TERMS | Extract Key Terms | 1 |
| 2 | RISK_ASSESSMENT | Risk Assessment (RAG) | 2 |
| 3 | CLAUSE_CHECKLIST | Clause Checklist | 2 |
| 4 | GENERATE_SUMMARY | Executive Summary | 1 |

### 3.2 Contract Review
Rapid review — audit clauses, benchmark risk against corpus, generate firm-grounded redlines.

| Step | Type | Label | Max Iterations |
|------|------|-------|----------------|
| 1 | CLAUSE_CHECKLIST | Clause Checklist | 2 |
| 2 | RISK_ASSESSMENT | Risk Assessment (RAG) | 2 |
| 3 | REDLINE_SUGGESTIONS | Redline Suggestions (Firm Clauses) | 2 |

### 3.3 Key Terms Only
Minimal workflow — just extraction.

| Step | Type | Label | Max Iterations |
|------|------|-------|----------------|
| 1 | EXTRACT_KEY_TERMS | Extract Key Terms | 1 |

### 3.4 High-Risk Deep Dive
Full analysis with conditional redlines — redlines only generated when risk is HIGH.

| Step | Type | Label | Max Iterations | Condition |
|------|------|-------|----------------|-----------|
| 1 | RISK_ASSESSMENT | Risk Assessment (RAG) | 2 | — |
| 2 | CLAUSE_CHECKLIST | Clause Checklist | 2 | — |
| 3 | REDLINE_SUGGESTIONS | Redline Suggestions (Firm Clauses) | 2 | `RISK_ASSESSMENT.overallRisk eq HIGH` |
| 4 | GENERATE_SUMMARY | Executive Summary | 1 | — |

### 3.5 Playbook Review
Checklist → corpus-benchmarked risk → firm-grounded redlines, each step self-refines up to 2 passes.

| Step | Type | Label | Max Iterations |
|------|------|-------|----------------|
| 1 | CLAUSE_CHECKLIST | Clause Checklist (Refined) | 2 |
| 2 | RISK_ASSESSMENT | Risk Benchmark vs Corpus | 2 |
| 3 | REDLINE_SUGGESTIONS | Redlines from Firm Playbook | 2 |
| 4 | GENERATE_SUMMARY | Executive Memo | 1 |

### 3.6 Draft & Assess Loop
Drafts a clause → assesses risk → refines with redlines if risky → produces summary. Each step iterates until quality passes.

| Step | Type | Label | Max Iterations | Condition | Params |
|------|------|-------|----------------|-----------|--------|
| 1 | DRAFT_CLAUSE | Draft Liability Clause | 2 | — | `clauseType=LIABILITY` |
| 2 | RISK_ASSESSMENT | Assess Drafted Clause | 2 | — | — |
| 3 | REDLINE_SUGGESTIONS | Refine with Firm Playbook | 2 | `RISK_ASSESSMENT.overallRisk in HIGH,MEDIUM` | — |
| 4 | GENERATE_SUMMARY | Draft Summary | 1 | — | — |

**Additionally:** After all steps, if RISK_ASSESSMENT.overallRisk == HIGH, the executor re-runs from step 1 (DRAFT_CLAUSE) with risk feedback injected (see Section 6).

### 3.7 Draft Full Agreement
Drafts a complete contract → assesses → redlines weak clauses → executive summary.

| Step | Type | Label | Max Iterations | Condition | Params |
|------|------|-------|----------------|-----------|--------|
| 1 | DRAFT_CLAUSE | Draft Full Agreement | 1 | — | `mode=agreement` |
| 2 | RISK_ASSESSMENT | Assess Agreement | 2 | — | — |
| 3 | REDLINE_SUGGESTIONS | Redline Weak Clauses | 2 | `RISK_ASSESSMENT.overallRisk in HIGH,MEDIUM` | — |
| 4 | GENERATE_SUMMARY | Executive Summary | 1 | — | — |

---

## 4. Quality Loop — Self-Healing Execution

Every step in every workflow runs inside a quality loop that automatically detects incomplete or low-quality outputs and re-executes with targeted feedback.

### How it works

```
For iter = 0 to maxIterations - 1 (clamped to [1, 3]):
    1. Execute step (with RAG context + any feedback from prior iteration)
    2. Score output quality using heuristic rules (no LLM call)
    3. If score >= 70/100 OR this is the last iteration:
         → Accept the result, break
    4. If score < 70/100 AND more iterations remain:
         → Build feedback from specific gaps
         → Feed the gap list back into the next iteration
         → Continue loop
```

### Feedback format injected on retry

```
=== REFINEMENT REQUIRED (Pass 2) ===
Your previous output was incomplete. Address ALL of the following:
* Only 3 risk categories identified — need at least 5 (LIABILITY, INDEMNITY, TERMINATION, IP_RIGHTS, CONFIDENTIALITY, GOVERNING_LAW, FORCE_MAJEURE)
* No specific section references — cite the actual clause number or section name from the contract
=== PRODUCE AN IMPROVED VERSION NOW ===
```

### Error handling within the loop

If a step throws an exception:
- If it's NOT the last iteration: the error message becomes the feedback context for the next try
- If it IS the last iteration: the exception is rethrown and the workflow fails

### SSE events during quality loop

- `step_iteration` event emitted on each retry with `{stepIndex, stepType, iteration, maxIterations}`
- 400ms sleep between iterations (prevents rapid-fire LLM calls)

---

## 5. Quality Scoring Rules (Per Step Type)

All scoring is heuristic — no LLM call. Passing threshold: **70/100**.

### RISK_ASSESSMENT (max 100)

| Check | Points | Condition | Gap feedback if failed |
|-------|--------|-----------|----------------------|
| Base | 30 | Always | — |
| Category count | +25 (if ≥5) or +5 per category | Number of risk categories | "Only N risk categories identified — need at least 5 (LIABILITY, INDEMNITY, TERMINATION, IP_RIGHTS, CONFIDENTIALITY, GOVERNING_LAW, FORCE_MAJEURE)" |
| Justifications | +20 | Every category has justification > 20 chars | "Some risk categories have no justification — explain WHY each rating was assigned with reference to the contract text" |
| Section references | +15 | Any category has sectionRef (not blank, not "See contract") | "No specific section references — cite the actual clause number or section name (e.g. 'Clause 8.2', 'Section 12 — Force Majeure')" |
| Overall risk set | +10 | overallRisk field not blank | "Overall risk level (HIGH/MEDIUM/LOW) not set" |

### CLAUSE_CHECKLIST (max 100)

| Check | Points | Condition | Gap feedback if failed |
|-------|--------|-----------|----------------------|
| Base | 35 | Always | — |
| Clause count | +30 (if ≥8) or +3 per clause | Number of clauses audited | "Only N clauses checked — audit at least 8 standard clauses (Definitions, Confidentiality, Liability, Termination, Force Majeure, Governing Law, IP Rights, Warranties, Payment, Notices, Assignment, Entire Agreement)" |
| Assessments | +25 | Any clause has assessment > 15 chars | "Clause assessments are empty or too brief — explain specifically what is weak or missing and why it matters" |
| Critical missing field | +10 | criticalMissingClauses node exists | — |

### REDLINE_SUGGESTIONS (max 100)

| Check | Points | Condition | Gap feedback if failed |
|-------|--------|-----------|----------------------|
| Base | 25 | Always | — |
| Suggestion count | +30 (if ≥3) or +8 per suggestion | Number of suggestions | "No redline suggestions" or "Only N suggestion(s) — provide at least 3" |
| Suggested language | +25 | All suggestions have suggestedLanguage > 60 chars | "Suggested language is too brief or generic — write full clause-level text (minimum 2 sentences) that could be inserted directly into the contract" |
| Rationale | +20 | All suggestions have rationale > 20 chars | "Rationale is missing or too brief — explain why the suggested language is legally superior" |

### GENERATE_SUMMARY (max 100)

| Check | Points | Condition | Gap feedback if failed |
|-------|--------|-----------|----------------------|
| Base | 35 | Always | — |
| Summary length | +25 | executiveSummary ≥ 150 chars | "Executive summary too short — write at least 3-4 sentences covering: contract purpose, key parties, primary obligations, and main risks" |
| Top concerns | +20 | ≥ 2 concerns listed | "Fewer than 2 top concerns — identify the most significant legal or commercial risks from the analysis" |
| Recommendations | +20 | ≥ 1 recommendation | "No actionable recommendations — suggest specific negotiation points or protective clauses the party should request" |

### EXTRACT_KEY_TERMS (max 95)

| Check | Points | Condition | Gap feedback if failed |
|-------|--------|-----------|----------------------|
| Base | 35 | Always | — |
| Each of 4 key fields | +15 each | partyA, partyB, effectiveDate, governingLaw not blank and not "N/A" | "Could not extract: [missing fields] — search the full contract text for these values" |

### DRAFT_CLAUSE (max 100)

| Check | Points | Condition | Gap feedback if failed |
|-------|--------|-----------|----------------------|
| Base | 30 | Always | — |
| Content length | +35 | Content > 600 chars | "Draft clause is too short — expand each sub-clause with complete, commercially reasonable legal text (minimum 3 sub-clauses)" |
| No placeholders | +25 | No `[...]`, `INSERT`, `TBD` patterns found | "Draft contains unfilled placeholders ([...], INSERT, TBD) — replace every placeholder with generic but legally sound language" |
| Multiple sentences | +10 | 3+ sentences detected | "Draft appears to be heading-only — write the full substantive clause text, not just titles" |

### SEND_FOR_SIGNATURE
Always returns score 100 (no quality check).

---

## 6. Risk-Triggered Redraft

After all workflow steps complete, the executor performs a cross-step intelligence check:

```
If a DRAFT_CLAUSE step exists in the workflow
AND the RISK_ASSESSMENT step returned overallRisk == "HIGH":
    1. Build risk feedback from all HIGH-rated categories:
       === RISK FEEDBACK — REDRAFT REQUIRED ===
       The drafted clause was assessed as HIGH overall risk. Address every HIGH-rated area:
       * CategoryName [HIGH]: justification text
       * ...
       Rewrite the clause to eliminate these risks while preserving commercial balance.
       === END RISK FEEDBACK ===
    2. Clear results for all steps from DRAFT_CLAUSE onward
    3. Emit SSE event: workflow_refinement {reason, draftStepIndex}
    4. Re-run all steps from DRAFT_CLAUSE to end, with risk feedback injected into the draft step
```

This means the DRAFT_CLAUSE step is called again with explicit knowledge of what was risky, producing a revised draft that addresses the HIGH-risk areas. All downstream steps (RISK_ASSESSMENT, REDLINE_SUGGESTIONS, SUMMARY) also re-run on the improved draft.

---

## 7. Conditional Step Execution

Any step can have a `condition` that determines whether it runs. Conditions reference the output of prior steps.

### Condition structure

```json
{
  "field": "RISK_ASSESSMENT.overallRisk",
  "op": "in",
  "value": "HIGH,MEDIUM"
}
```

### Supported operators

| Operator | Behavior |
|----------|----------|
| `eq` | Exact match (case-insensitive) |
| `neq` | Not equal (case-insensitive) |
| `in` | Value is in comma-separated list (case-insensitive) |

### Evaluation logic

1. Serializes the accumulated results map to JSON
2. Traverses the dot-path (e.g., `RISK_ASSESSMENT` → `overallRisk`)
3. If any traversal step returns null: condition evaluates to **false** (step skipped)
4. Unknown operators default to **true** (step runs)
5. On exception: logs warning, returns **true** (step runs — fail-open)

### Example: Conditional redlines

In the "High-Risk Deep Dive" workflow, the REDLINE_SUGGESTIONS step has condition `RISK_ASSESSMENT.overallRisk eq HIGH`. If the risk assessment returns MEDIUM or LOW, redlines are skipped entirely, saving time and cost.

---

## 8. Cross-Step Data Flow

The `results` map (keyed by `WorkflowStepType.name()`) is the central state store shared across all steps in a workflow run.

### Data flow diagram

```
EXTRACT_KEY_TERMS ──→ results["EXTRACT_KEY_TERMS"]
                          ↓ (available to all subsequent steps)
RISK_ASSESSMENT ─────→ results["RISK_ASSESSMENT"]
                          ↓ overallRisk checked by conditions
                          ↓ categories used for risk-triggered redraft feedback
CLAUSE_CHECKLIST ────→ results["CLAUSE_CHECKLIST"]
                          ↓
DRAFT_CLAUSE ────────→ results["DRAFT_CLAUSE"]
                          ↓ .content extracted by extractDraftedText()
                          ↓ passed as draftedText to downstream steps
                          ↓ (enables document-less draft → review workflows)
REDLINE_SUGGESTIONS ─→ results["REDLINE_SUGGESTIONS"]
                          ↓
GENERATE_SUMMARY ────→ results["GENERATE_SUMMARY"]
                          (receives ALL prior results for cross-step synthesis)
```

### Key data flow patterns

1. **Draft → Review (no document):** When a DRAFT_CLAUSE step produces text, `extractDraftedText()` reads the `content` field from the result. This text is passed to RISK_ASSESSMENT, REDLINE_SUGGESTIONS, and GENERATE_SUMMARY as `draftedText` — enabling a complete "draft → review → fix" cycle without any uploaded document.

2. **Risk → Conditional Redlines:** RISK_ASSESSMENT stores `overallRisk` in its result. Subsequent steps with conditions like `RISK_ASSESSMENT.overallRisk in HIGH,MEDIUM` read this value to decide whether to run.

3. **Risk → Redraft Feedback:** After all steps, the executor reads `RISK_ASSESSMENT.categories` and filters for HIGH-rated ones. The names and justifications become the feedback string injected into the redraft.

4. **All Results → Summary:** GENERATE_SUMMARY receives the entire `priorResults` map, allowing it to synthesize findings from extraction, risk, checklist, and redlines into a coherent executive summary.

5. **RAG context per step:** Each step gets its own RAG context retrieved via `workflowContextService.getContextForStep()`, using the document metadata and matter context to scope the retrieval.

---

## 9. Drafting Agent (Full Agreement)

When the DRAFT_CLAUSE step runs with `mode=agreement`, it invokes the full agreement drafting pipeline in `DraftService`.

### Full agreement drafting flow

```
1. PLAN SECTIONS
   LLM decides which sections to include based on contract type, parties,
   practice area, industry, and deal brief.
   Falls back to defaults if LLM response is unparseable.
       ↓
2. FOR EACH PLANNED SECTION:
   a. Retrieve RAG context (firm clause library)
   b. Build prompt with:
      - Clause-specific system prompt
      - Terminology manifest (party names, defined terms from DEFINITIONS clause)
      - RAG grounding ("follow firm precedent structure exactly")
      - Draft content guardrails
      - Deal context (brief, client position, industry regulations, stance)
   c. Call LLM
   d. Strip LLM artifacts (12-step cleanup)
   e. Sanitize clause text → HTML formatting
   f. Run QA checks (7 checks)
   g. If QA fails: retry with targeted feedback (up to 2 retries)
      - Best-of-N tracking: keeps highest-scoring attempt, not latest
      - Phase 3: if only missing sub-clauses, regenerate just those
   h. Post-process any remaining placeholders as last resort
       ↓
3. COHERENCE SCAN
   Cross-clause consistency check:
   - Party name drift (vendor/client synonym consistency)
   - Defined term usage (e.g., "Confidential Information" used correctly)
       ↓
4. RETURN: draftHtml, suggestions (with RAG source reasoning), qaWarnings, coherenceIssues
```

### Section planning — LLM-powered

The LLM receives the contract details and returns a JSON array of section keys. If it fails, the system falls back to template-specific defaults:

| Contract Type | Default Sections |
|---------------|-----------------|
| NDA | DEFINITIONS, CONFIDENTIALITY, LIABILITY, TERMINATION, GOVERNING_LAW, GENERAL_PROVISIONS |
| Software License / IP License | DEFINITIONS, IP_RIGHTS, PAYMENT, LIABILITY, TERMINATION, GOVERNING_LAW, GENERAL_PROVISIONS |
| Employment | DEFINITIONS, SERVICES, PAYMENT, CONFIDENTIALITY, IP_RIGHTS, TERMINATION, GOVERNING_LAW, GENERAL_PROVISIONS |
| Supply | DEFINITIONS, SERVICES, PAYMENT, FORCE_MAJEURE, LIABILITY, TERMINATION, GOVERNING_LAW, GENERAL_PROVISIONS |
| SaaS / Fintech MSA / Clinical Services | DEFINITIONS, SERVICES, PAYMENT, CONFIDENTIALITY, DATA_PROTECTION, LIABILITY, TERMINATION, GOVERNING_LAW, GENERAL_PROVISIONS |
| Default (MSA, Vendor, Custom) | DEFINITIONS, SERVICES, PAYMENT, CONFIDENTIALITY, IP_RIGHTS, LIABILITY, TERMINATION, GOVERNING_LAW, GENERAL_PROVISIONS |

### 12 clause specifications

Each clause type has a title, system prompt, user prompt template, and expected sub-clause count:

| Clause Key | Title | Expected Sub-clauses |
|-----------|-------|---------------------|
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

### Deal context enrichment

The deal context passed to the LLM includes:
- **Deal brief** (user-provided)
- **Client position:** PARTY_A → "draft clauses favourably for Party A"; PARTY_B → favorable for Party B; default → "balanced terms preferred"
- **Industry regulations:**
  - FINTECH: "RBI guidelines, FEMA 1999, Payment & Settlement Systems Act 2007"
  - PHARMA: "Drugs and Cosmetics Act 1940, Clinical Establishment Act 2010, DPDPA 2023"
  - IT_SERVICES: "IT Act 2000, DPDPA 2023, SEBI (if listed entity)"
  - MANUFACTURING: "Factories Act 1948, Environment Protection Act 1986, GST Act 2017"
- **Draft stance:** FIRST_DRAFT → "maximise protections, strong caps, broad indemnity carve-outs, liberal termination rights"; FINAL_OFFER → "firm but commercially reasonable, avoid overreaching terms"; default → "balanced, commercially standard terms"

### Matter hydration

If the draft is associated with a matter:
- Pre-fills blank `partyA` with `matter.clientName`
- Pre-fills blank `practiceArea` with `matter.practiceArea`
- Appends matter name, reference, and deal type to the deal brief

---

## 10. Drafting Agent (Single Clause with QA Retry)

When drafting a single clause (the `generateClauseWithQa` method), a more granular QA and retry loop runs per clause.

### Per-clause QA retry loop

```
1. Build context (contract type, jurisdiction, counterparty, practice area, deal context)
2. Format initial prompt from clause template
3. Add terminology manifest constraint (if DEFINITIONS clause exists)
4. Add RAG grounding ("follow firm precedent structure exactly")
5. Add content guardrails
6. Call LLM → strip artifacts → sanitize HTML
7. Run 7 QA checks
8. Track score (best-of-N: keeps highest-scoring attempt)
9. If QA issues exist AND retries remain (max 2):
   a. If ONLY missing sub-clauses: Phase 3 — regenerate just those sub-clauses
   b. Otherwise: Phase 2 — full retry with isolated feedback prompt
   c. Score new attempt. Keep if better, discard if worse.
10. After all retries: post-process remaining placeholders as last resort
11. Run final QA and return
```

### 7 QA checks

1. **Placeholder detection:** Regex scan for `[some text]`, `(insert ...)`, `%MARKER%`, `TBC`, `TBD`
2. **Sub-clause count:** Checks if HTML contains expected number of `clause-sub` elements
3. **Heading-only detection:** Flags sub-clauses where body after `<strong>` tag is < 40 chars
4. **Overall length:** Warns if plain text < 200 chars
5. **LLM artifact detection:** Checks for raw JSON, LaTeX notation, code comments, instruction tokens
6. **Contract-type contamination:** Detects terms from wrong contract types (e.g., "landlord" in a SaaS agreement, "software license" in an NDA)
7. **Semantic requirement check:** Verifies expected keywords per clause type (e.g., PAYMENT must mention "invoice", "due date", "late payment")

### Clause scoring (best-of-N selection)

```
Base: 1000
- Per QA warning: -100
- LLM artifact warning: additional -200
- Plain text < 200 chars: -300
- Plain text < 400 chars: -100
+ Length bonus: +min(plainLength, 2000) / 10
```

### Phase 3 — Targeted sub-clause regeneration

When the only QA issues are missing or heading-only sub-clauses, instead of regenerating the entire clause, the system asks the LLM to generate ONLY the missing numbered sub-clauses (e.g., "Write ONLY sub-clauses 4 through 5"), then splices them onto the existing HTML. This preserves good content and fixes only the gaps.

### 12-step LLM artifact stripping

Applied before the clause sanitizer:

0. Truncate at retry directive bleed-through markers
1. Strip instruction tokens (Mistral/Llama `[INST]`, Gemma `<|end_of_turn|>`, Qwen `<|im_start|>`, bare role labels)
2. Strip markdown code fences
3. Extract prose from JSON wrappers (finds longest string value)
4. Remove standalone JSON lines
5. Strip LaTeX notation (`$\text{Name}$` → `Name`)
6. Strip code comments (`//`, `/* */`)
7. Fix broken unicode escapes
8. Strip ASCII table garbage
9. Remove residual JSON field names leaked inline
10. Extract content from JSON field values
11. Clean up multiple blank lines and trailing commas
12. **Loop detection:** Truncate at second occurrence of any 40+ char sentence fragment

### Concurrency control

`DraftService` uses a `Semaphore` (default max 2 concurrent drafts, configurable via `legalpartner.draft.max-concurrent`). If no permit available, returns HTTP 429 (Too Many Requests) immediately — no queuing.

---

## 11. Contract Review Agent

The `ContractReviewService` handles clause checklist analysis using guided JSON generation.

### Review flow

```
1. Load document metadata
2. Retrieve full document text via fullTextRetriever
3. If text is blank: return error "Document not yet indexed"
4. PRIMARY PATH: Call VLLM with guided JSON schema
   - System prompt: CHECKLIST_SYSTEM_GUIDED
   - User prompt: CHECKLIST_USER_GUIDED
   - Structured output: StructuredSchemas.CHECKLIST_SCHEMA (maxTokens=1200)
   - Parse JSON response
5. FALLBACK PATH (if guided JSON returns no clauses):
   - Call VLLM with text generation
   - Prefix: "LIABILITY_LIMIT=" (maxTokens=400)
   - Try CSV parsing first, then pipe-delimited regex
6. Compute: present count, missing count, weak count
7. Identify critical missing clauses (MISSING + HIGH risk)
8. Extract recommendations (up to 5, from non-PRESENT or HIGH-risk PRESENT clauses)
9. Compute overall risk rating
10. Return ChecklistResult
```

### Output format per clause

```json
{
  "clauseId": "LIABILITY_LIMIT",
  "clauseName": "Limitation of Liability",
  "status": "PRESENT|WEAK|MISSING",
  "riskLevel": "HIGH|MEDIUM|LOW",
  "sectionRef": "Clause 8.2",
  "finding": "The liability cap is set at 12 months of fees but excludes IP indemnification",
  "recommendation": "Negotiate to include IP infringement within the liability cap or add a separate IP indemnity cap"
}
```

---

## 12. Matter Agent (Cross-Document Analysis)

The `MatterAgentService` performs automated analysis across all documents in a legal matter.

### Analysis flow

```
1. Acquire semaphore permit (max 3 concurrent analyses, configurable)
2. Load agent configuration (checkPlaybook, crossReferenceDocs flags)
3. Validate matter exists and status is ACTIVE
4. Validate document exists
5. Publish AGENT_ANALYSIS_TRIGGERED audit event
6. Run enabled analysis types:
   a. PLAYBOOK COMPARISON (if enabled and matter has default playbook):
      - Compare document clauses against negotiation playbook
      - Detect deviations from firm's standard positions
      - Generate findings with severity levels
   b. CROSS-DOCUMENT CONFLICT DETECTION (if enabled):
      - Compare document against all other documents in the matter
      - Detect conflicting or inconsistent terms
      - Generate findings with severity levels
7. Save all findings to database
8. Notify if needed (via AgentNotificationService)
9. Publish AGENT_ANALYSIS_COMPLETED audit event
```

### Batch re-analysis

`reanalyzeAllDocuments(matterId)`: Deletes existing PLAYBOOK_DEVIATION and PLAYBOOK_NON_NEGOTIABLE findings for the matter, then re-runs `analyzeDocument()` for every document in the matter.

---

## 13. Review Pipeline (Human-in-the-Loop)

The `ReviewPipelineService` provides multi-stage human approval workflows for contract documents. This is NOT AI-driven — it orchestrates human reviewers through a defined approval process.

### Pipeline structure

A pipeline has ordered **stages**, each with:
- `stageOrder` — execution order (1, 2, 3...)
- `name` — human-readable stage name (e.g., "Associate Review", "Partner Approval")
- `requiredRole` — who can act at this stage (PARTNER, ASSOCIATE, PARALEGAL, ADMIN)
- `actions` — comma-separated valid actions (e.g., "APPROVE,RETURN")
- `autoNotify` — whether to automatically notify stage members when the review reaches this stage

### Review flow

```
startReview(pipelineId, matterId, documentId)
    → Creates MatterReview with status IN_PROGRESS, currentStage = first stage
    → If first stage has autoNotify: sends email/Slack/Teams to stage members
    ↓
takeAction(reviewId, action, username)
    → Validates action is allowed for current stage
    → Logs ReviewAction for audit trail
    → APPROVE: advances to next stage (or APPROVED if last stage)
    → RETURN: moves back to previous stage
    → SEND: sets status to SENT
    → If new stage has autoNotify: notifies new stage members
```

### Notification channels

When a review reaches a new stage with autoNotify enabled:
1. **Slack:** Looks up each stage member's Slack integration, sends via webhook
2. **Microsoft Teams:** Looks up each stage member's Teams integration, sends via webhook
3. **Email:** Sends one HTML email to all stage members with:
   - Color-coded action badge (approved=green, returned=amber, started=indigo)
   - Pipeline name, current stage, matter name, document name, actor name
   - "Take Action" button linking to the matter page

### Dashboard

`getDashboard(username, role)` returns:
- **Needs action:** Reviews where current stage matches user's role
- **Team activity:** Other in-progress reviews on user's matters
- **Recently completed:** Up to 10 recently APPROVED reviews

---

## 14. Connectors — Email, Slack, Teams, Webhook

Connectors fire asynchronously after a workflow run completes. Each connector type is independent — a workflow can have multiple connectors of different types.

### Email Connector

- **Recipients:** Comma/semicolon/whitespace-separated list of email addresses
- **Matter access validation:** If the run is associated with a matter, each recipient is checked for matter membership. Non-members are filtered out.
- **Subject:** Defaults to "Legal Partner — {workflowName} completed" (overridable)
- **Body:** HTML email with:
  - Dark header bar: "Workflow Completed" + "Legal Partner" subtitle
  - White card: workflow name, document ID (or "Draft run"), duration (Xs or Xm Ys), green COMPLETED badge
  - Steps completed: bulleted list of step types
  - "View Full Results" button linking to `/workflows/run/{runId}`
  - Footer: "Legal Partner — Automated notification"

### Slack Connector

- Looks up user's Slack integration connection from the database
- Sends via the user's configured webhook URL
- Message: `*{workflowName}* — {status}\nRun: \`{runId}\`\nUser: {username}`

### Microsoft Teams Connector

- Looks up user's Teams integration connection
- Sends via the user's configured webhook URL
- Title: `"{workflowName} — {status}"`
- Body: `"Run: {runId}<br>User: {username}"`

### Webhook Connector

- Requires `url` in config; optional `secret`
- POST payload: `{event: "workflow.completed", runId, workflowName, documentId, username, status, startedAt, completedAt, results}`
- Headers: `Content-Type: application/json`, optional `X-LegalPartner-Secret`

### Audit logging

Each connector firing creates an audit entry with:
- Connector type
- Success/failure status
- Timestamp
- Details (recipient list for email, webhook URL, etc.)

Audit action types: `CONNECTOR_EMAIL_SENT`, `CONNECTOR_EMAIL_FAILED`, `CONNECTOR_SLACK_SENT`, `CONNECTOR_SLACK_FAILED`, `CONNECTOR_TEAMS_SENT`, `CONNECTOR_TEAMS_FAILED`, `CONNECTOR_WEBHOOK_SENT`, `CONNECTOR_WEBHOOK_FAILED`

---

## 15. Real-Time Streaming (SSE Events)

Every workflow run streams progress to the frontend via Server-Sent Events (SSE). The emitter has infinite timeout (timeout = 0).

### SSE event types

| Event | When | Payload |
|-------|------|---------|
| `workflow_start` | Run begins | `{runId, workflowName, totalSteps}` |
| `step_start` | Before executing a step | `{stepIndex, stepType, label}` |
| `step_skipped` | Condition not met | `{stepIndex, stepType, label, reason}` |
| `step_iteration` | Quality loop retry | `{stepIndex, stepType, iteration, maxIterations}` |
| `step_complete` | Step finished | `{stepIndex, stepType, label, result}` |
| `workflow_refinement` | Risk-triggered redraft starts | `{reason, draftStepIndex}` |
| `workflow_complete` | All done | `{runId, results, skippedSteps}` |
| `workflow_error` | Exception | `{error}` |
| `workflow_cancelled` | User cancelled | `{reason}` |

### Draft streaming (separate SSE stream)

The `streamDraft()` method in DraftService has its own SSE stream for real-time clause-by-clause progress:

| Event Type | When | Payload |
|------------|------|---------|
| `planning` | Section planning started | — |
| `start` | Planning complete | `{totalClauses, plannedSections, partialHtml}` |
| `clause_start` | Before each clause | `{clauseType, label, index, totalClauses}` |
| `clause_retry` | QA retry within clause | `{clauseType, attempt, fixing}` |
| `clause_done` | Clause generated | `{clauseType, label, index, totalClauses, qaWarnings, partialHtml}` |
| `complete` | All clauses done | `{draftHtml, suggestions, qaWarnings, coherenceIssues}` |
| `error` | Failure | `{message}` |

The `partialHtml` in each `clause_done` event contains the full draft HTML with completed clauses rendered and pending clauses showing placeholder text: `⏳ Generating {title} clause...`

---

## 16. Custom Workflow Builder

Users can create their own workflows through the API or UI.

### Creating a custom workflow

```
POST /api/v1/workflows
{
  "name": "My Custom Review",
  "description": "Extract terms, then conditional deep dive",
  "steps": [
    {"type": "EXTRACT_KEY_TERMS", "label": "Extract", "maxIterations": 1},
    {"type": "RISK_ASSESSMENT", "label": "Risk Check", "maxIterations": 2},
    {"type": "REDLINE_SUGGESTIONS", "label": "Redlines",
     "condition": {"field": "RISK_ASSESSMENT.overallRisk", "op": "eq", "value": "HIGH"},
     "maxIterations": 2}
  ],
  "connectors": [
    {"type": "EMAIL", "config": {"recipients": "john@firm.com", "subject": "Review done"}}
  ],
  "team": true
}
```

### Configuration options per step

- **type:** One of the 7 step types
- **label:** Human-readable name (shown in SSE events and UI)
- **maxIterations:** 1-3 (clamped). Default 1. Controls quality loop retries.
- **condition:** Optional. References prior step output. Step skipped if condition is false.
- **params:** Step-type-specific params (e.g., `clauseType`, `mode` for DRAFT_CLAUSE)

### Workflow-level options

- **team:** If true, visible to all team members (not just creator)
- **autoTrigger:** Flag for automatic workflow triggering (on document upload, etc.)
- **connectors:** Fired after workflow completes. Multiple connectors of different types supported.

### Management

- Custom workflows can be deleted by the creator
- Predefined workflows cannot be deleted or modified
- `promoteToTeam()` toggles the team-visibility flag

---

## 17. Workflow Analytics

The platform tracks execution metrics for all workflows.

### Analytics endpoint

```
GET /api/v1/workflows/analytics
```

Returns:
- **totalRuns:** Total workflow executions by the user
- **completedRuns:** Successfully completed
- **failedRuns:** Failed with error
- **runningRuns:** Currently in progress
- **completionRate:** `completedRuns / totalRuns * 100` (rounded to 1 decimal)
- **avgDurationMs:** Average execution time in milliseconds
- **byWorkflow:** Per-workflow-name breakdown (runs count, completed count)
- **byDay:** Daily run counts for the last 7 days

---

## API Endpoints

### Execute a workflow

```
POST /api/v1/workflows/runs?definitionId={uuid}&documentId={uuid}
Content-Type: application/json

{
  "runtimeConnectors": [
    {"type": "EMAIL", "config": {"recipients": "user@firm.com"}}
  ],
  "draftContext": {
    "partyA": "Acme Corp",
    "partyB": "Beta Inc",
    "jurisdiction": "New York"
  }
}
```

Response: `text/event-stream` (SSE)

### Cancel a running workflow

```
POST /api/v1/workflows/runs/{runId}/cancel
```

### Export workflow results

```
GET /api/v1/workflows/runs/{runId}/export
```

Returns full JSON with all step results, metadata, and audit trail.
