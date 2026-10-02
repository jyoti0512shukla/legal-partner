# AI Quality Foundations — Design

Status: implemented on `feat/ai-quality-foundations` · Owner: AI platform · Date: 2026-09-25

## Problem

Our own generated drafts routinely fail our own review. Investigation found the
failure is mostly in the plumbing between drafting and review, not in the prose:

| # | Root cause | Effect |
|---|---|---|
| 1 | vLLM removed `guided_json` in v0.12; we run 0.19.1 and still send it | Every "structured" call silently falls back to `{`-priming + regex |
| 2 | Review re-parses the draft HTML and flattens all whitespace to one line | Line-anchored heading regex finds nothing; questions run on wrong 4K windows |
| 3 | Drafts are saved as `DocumentType.OTHER`, review never learns the template | Wrong required clauses and questions for the contract type |
| 4 | Two unrelated rubrics: `clause_requirements.yml` (draft) vs `risk_questions.yml` (review) | The drafter never targets what the reviewer asks |
| 5 | Review ignores which party we drafted for | Intentionally one-sided first drafts scored as risk |
| 6 | No end-to-end eval | We can't tell whether a change helped |

## Goals

- A draft's review reads exactly what the drafter produced (no re-parsing).
- One logical clause spec: the semantic requirements the reviewer asks are the
  same requirements the drafter is prompted with and verified against.
- A repeatable, CI-gatable eval with a **round-trip metric** (review score of our own drafts).
- Structured output that actually constrains decoding on vLLM ≥ 0.12 and on
  hosted OpenAI-compatible providers (enables a GPU-free model bake-off).

## Non-goals (this change)

- Plan → adapt → verify → repair drafting redesign (next phase; this change builds its verifier).
- Physical consolidation of the two YAML files (see "Why a logical merge first").
- LangChain4j 1.x upgrade, model switch, Temporal.

## Design

### WS1a — Structured output and provider portability

- `VllmGuidedClient` sends OpenAI-standard `response_format: {type: json_schema}`
  (supported by vLLM with xgrammar and by OpenRouter/DeepInfra). Mode is configurable:
  `legalpartner.llm.structured-output-mode = response_format | structured_outputs | none`
  (`structured_outputs` = vLLM-native field; `none` = prompt-only).
- `legalpartner.chat-api-key` (env `LEGALPARTNER_CHAT_API_KEY`, default `no-op`) is used
  by every LangChain4j model bean and the direct HTTP client.
- `legalpartner.llm.request-extras` (JSON) is merged into direct HTTP request bodies —
  e.g. `{"chat_template_kwargs":{"enable_thinking":false}}` (vLLM/Qwen) or
  `{"reasoning":{"enabled":false}}` (OpenRouter).
- Every chat model bean is wrapped in `ReasoningStrippingChatModel`, which removes
  `<think>…</think>` blocks so reasoning models cannot break downstream parsers.
- `generateText` (raw `/completions` + Mistral `[INST]` template) is only used for
  Mistral-family models; other models go through chat completions with a response prefix.

### WS1b — Draft → review handoff

- At submit time the draft row gets its real `documentType` (from `contract_types.yml`).
- On completion `DraftService` writes a **draft manifest** sidecar `<docId>.draft.json`:
  `templateId`, `reviewType`, `clientPosition`, `draftStance`, `jurisdiction`, and the
  ordered sections `{key, title, article, text}`.
- Review (`assessRisk`, `assessRiskStreaming`) checks for a manifest first. If present,
  clause inventory comes from the manifest sections (mapped to review clause keys via
  `ClauseSpecRegistry`), contract type comes from `reviewType`, and client position is
  applied. Otherwise the existing text-based inventory runs unchanged.
- HTML → text conversion (`HtmlText`) keeps block boundaries as newlines, so the text
  path also finds `ARTICLE n` headings for drafts without a manifest (older drafts).
- Clause inventory is extracted from `AiService` into `ClauseInventory` for unit testing.

### WS1c — Position-aware review

- Risk questions gain `position_sensitive: true` (mutual cap / indemnity / termination /
  confidentiality). When a draft was written for a party (`clientPosition` = PARTY_A or
  PARTY_B), those questions are recorded as `WAIVED` with a note and excluded from scoring.
  Limitation: we do not detect *which* party a one-sided term favours; a one-sided term
  against our client is not flagged by this question. Follow-up: add a direction question.

### WS3 — One logical clause spec

`ClauseSpecRegistry` is the single API both sides use:

- **Semantic requirements** — the review questions (`risk_questions.yml`) filtered by
  review clause key, review contract type and client position. Used by review (unchanged
  per-question evaluation) and now by drafting (prompt injection + verification).
- **Deterministic requirements** — drafting rules (`clause_requirements.yml`), deal-bound,
  drafting-only.
- **Key mapping** — drafting clause keys → review clause keys
  (e.g. `REPRESENTATIONS_WARRANTIES → WARRANTIES`, `SERVICES → SLA` for SaaS).
- **Contract type mapping** — `templateId → reviewType` via a new `review_type` field in
  `contract_types.yml`.

Drafting integration (`DraftVerifier`, enabled by `legalpartner.draft.verify.enabled`):

1. Semantic requirements (weight ≥ `min-weight`, default 7, not waived) are appended to the
   clause generation prompt.
2. After rule-engine fixes, the clause is checked with **the same evaluator review uses**
   (`SemanticRequirementChecker`, extracted from `AiService`) so draft-time and review-time
   verdicts cannot diverge by method.
3. Failed requirements trigger one targeted repair ("revise minimally to satisfy X; keep
   everything else"). The repair is kept only if semantic passes increase and deterministic
   rule failures do not.

#### Why a logical merge first

The two files encode different kinds of checks (deal-bound deterministic vs. semantic),
both have uncommitted edits in flight, and a big-bang YAML rewrite would be the riskiest
part of this change for the least behavioural gain. The registry gives one contract in
code now; physically consolidating into `clause_spec.yml` is a mechanical follow-up once
the registry is the only reader.

### CI checks (deterministic, no LLM)

- `ConfigConsistencyTest`: every `required_fields`/`recommended_fields` path resolves on
  `DealSpec`; every template has a known `review_type`; every review-required clause for a
  template is produced by its default sections (after key mapping); every risk question's
  `applies_to` uses a known review type; golden clause keys are known clause types.
- `GoldenClauseRulesTest`: renders every golden clause with a fixture `DealSpec` per
  contract type and asserts no deterministic BLOCK/CRITICAL rule fails.
- `GoldenClauseReviewLlmTest` (runs only when `LP_EVAL_LLM_URL` is set): asks each golden
  clause the semantic requirements with the production checker; reports failures.

### WS2 — Eval harness

`eval/drafting/run_drafting_eval.py` drives the real API:

1. `POST /draft/validate` → `POST /draft/async` → poll → fetch HTML.
2. Deterministic checks: expected deal terms present, banned terms absent, no unresolved
   placeholders, sequential article numbering, valid cross-references, no invented amounts.
3. Round-trip: `POST /risk-assessment/{id}?regenerate=true` → risk score, overall risk,
   HIGH clauses, missing clauses.
4. Optional LLM judge (`--judge-*`): LENS-CRAFT-style severity 1–5 per article across nine
   dimensions, max-severity rule.
5. Writes `eval/results/<run>/summary.json` + `report.md`; `--baseline` compares and exits
   non-zero on regression beyond thresholds (CI gate).

Seed set: `eval/drafting/briefs.yml` (12 briefs across all 9 templates, both stances,
party positions). Target 30–50.

## Rollout

1. Merge behind defaults that preserve today's behaviour except the structured-output fix
   and the handoff (both strictly corrective).
2. `draft.verify.enabled` defaults to `true`; flip to `false` if latency is unacceptable.
3. Record a baseline with the eval, then use it to judge the model bake-off.

## Risks

| Risk | Mitigation |
|---|---|
| Provider rejects `response_format` json_schema | `structured-output-mode` switch; existing fallbacks retained |
| Verification adds latency (~1 + N calls per clause) | Weight filter, config switch, round-trip metric to justify |
| Repair degrades a golden clause | Accept only if semantic passes ↑ and rule failures don't ↑ |
| Manifest missing for older drafts | Text path fixed by `HtmlText`; manifest is additive |

## Addendum — config over code (hardcoding removal)

An audit of `service/`, `rag/` and `config/` found legal knowledge, prompts and tunables
hardcoded in Java — several as copies of YAML that had already drifted.

| Was hardcoded | Now | Note |
|---|---|---|
| 8 jurisdictions × 25 statute/court references (positional 25-arg constructors) | `config/jurisdictions.yml` | Verified output-identical for 15 jurisdiction strings × 2 server defaults before the Java was removed. Fix: "New South Wales" resolved to English law. |
| Template picker list (`TemplateService`) — duplicate of `contract_types.yml`; `vendor`/`custom` silently fell back to MSA | `contract_types.yml` (`description`, explicit `vendor`, `custom`) | |
| Contamination terms (`DraftService`) — duplicate of `banned_terms` (which was dead config); loop was inverted | `contract_types.yml banned_terms`, whole-word match | SaaS drafts no longer flag "uptime" as contamination. `banned_terms` are now enforced — have a lawyer review them (e.g. employment bans "license"). |
| Coherence-scan party synonyms — ignored `party_name_variants.yml`, flagged the template's own roles | `party_name_variants.yml` minus template roles | |
| Placeholder defaults, form defaults, prompt defaults, style directive | `config/drafting_defaults.yml` | Fix: "45 (thirty) days". Set values to `[●]` to render gaps instead of defaults. |
| Golden-first clause set | `clauses.yml golden_first` | |
| Agreement ref prefixes (switch) | `contract_types.yml ref_prefix` | |
| Drafting register per jurisdiction | `jurisdictions.yml DRAFTING_REGISTER` | |
| Checklist taxonomy: schema + fallback prompt used 12 IDs, system prompt asked for different IDs, parsers dropped the rest | `risk_questions.yml checklist_clauses` drives prompt, schema, parsers | Liability/indemnity/payment etc. were being discarded from every checklist. |
| Required-clause fallback, inventory keywords | `risk_questions.yml` (`_default` mandatory, `clause_keywords`) | Fallback had drifted (missing GENERAL_PROVISIONS). |
| ~20 inline prompts (DealSpec, semantic checker, contextual retrieval, draft retry/RAG/terminology, FixEngine, obligations, playbook, workflow clause, extraction, QA suggestions) | `PromptTemplates` (overridable in `clause_prompts.yml`) | `HardcodingGuardsTest` fails if code references a missing prompt id. |
| QA retries, sub-clause overage, BLOCK retries, workflow quality threshold | `application.yml` | |
| Two rules that failed every golden clause (`DEF_MUST_DEFINE_PARTIES` required both "shall mean" AND "refers to"; `LICENSE_GRANT_REQUIRED` applied to DEFINITIONS) | fixed in `clause_requirements.yml` | Golden clauses were being sent to the LLM for "repair" on every draft. |

Intentionally left in code: JSON schemas (parser contracts), HTML/regex parsing, password
blocklist, lifecycle state machine, email/notification templates.

Second tier (done in a follow-up pass):

| Was hardcoded | Now | Note |
|---|---|---|
| `QueryExpander` synonyms/acronyms, `ReRanker` acronyms + authority weights, `LegalDocumentChunker` clause keywords, `ContractTypeDetector` signals | `config/vocabulary.yml` (`LegalVocabulary`) | |
| `AiService.detectContractType(String)` — a cruder second copy of `ContractTypeDetector` | deleted; delegates to the detector | |
| Legacy 7-category risk view defined 5 times (drilldown keywords, CSV labels, proximity stems, a label switch, compare dimensions) + fallback risk phrases + context queries + clause-based type hints | `vocabulary.yml` (`risk_categories`, `risk_phrases`, `document_context_queries`, `clause_hints`) | |
| Intake field labels | `contract_types.yml field_labels` | CI: every required/recommended field has a label |
| Golden-clause aliases, literals, party-role placeholders | `golden_clauses.yml` | Removed the dead `placeholder_defaults` block (never loaded). CI: every `{{placeholder}}` resolves. |
| Golden fill-in fields were HTML spans that got HTML-escaped into visible markup | `GoldenClauseLibrary.toHtml` renders markers after escaping | Bug fix |
| `DRAFT_TERMINOLOGY_MANDATE` hardcoded "never Vendor" | built from the template's roles and `party_name_variants.yml` | Same rule as the coherence scan |
| Upload extension allowlist, coverage severity weights | `application.yml` | |

`ConfigBeansContextTest` wires every YAML-backed bean in a Spring slice (no DB/LLM) to catch wiring
and binding errors that hand-constructed unit tests can't.

### Third tier — learning loop, lifecycle, workflows, model artifacts

| Was in code | Now in | Why it matters for extensibility |
|---|---|---|
| Learning-loop thresholds, signed statuses, AI/EDGAR source lists, firm-clause positions + stance order, MinHash parameters, templating placeholders/suffixes/generic words, calibration thresholds, firm-norm rules + messages | `config/learning.yml` (`LearningConfig`) | Firms tune learning without a build; a new benchmarked deal term is a `norms.rules` entry (reads the `DocumentMetadata` property by name) |
| Money/date/email/phone regexes (duplicated in AnonymizationService and ClauseTemplater) | `vocabulary.yml entity_patterns` | One definition shared by the leak check and clause templating |
| US/India/UK "jurisdiction family" substring lists in DraftContextRetriever | `jurisdictions.yml` (`family`, default = root of `extends`) | A new jurisdiction automatically gets correct precedent scoping |
| Contract status transitions, lock/unlock statuses, finalize rules | `config/contract_lifecycle.yml` | A firm's approval flow is config; the API already serves allowed transitions to the UI |
| 8 predefined workflows | `config/workflows.yml` | New packaged workflows without code |
| Workflow step quality rubric (points, thresholds, gap/feedback wording) | `config/workflow_quality.yml` | New step types get a rubric in YAML; verified identical to the old scorer on 3,200 randomized results |
| LLM output cleanup (chat-template tokens, echoed prompt markers, meta-commentary phrases, retry-triggering artifact checks) | `config/output_cleanup.yml` (`LlmOutputSanitizer`) | Switching models = adding its artifacts to YAML; verified identical to the old code on 5,000 fuzzed outputs |
| Stance / client-position / deal-terms prompt text; learning prompt headers | `PromptTemplates` (overridable via `clause_prompts.yml`) | A new stance is a new `DRAFT_STANCE_<X>` prompt |

Consistency tests: `LearningPolicyTest`, `PolicyConfigTest`, `LlmOutputSanitizerTest`,
`LegalSystemConfigTest.jurisdictionFamiliesComeFromTheRegistry`.

Deliberately kept in code: JSON schemas, persisted status values (CANDIDATE/APPROVED…),
algorithms (MinHash, LSH, Beta calibration, edit distance), check/step *operations* whose
parameters live in YAML.

Behaviour changes (intentional):
- ```` ```html ```` / ```` ```json ```` are no longer truncation markers — a fenced answer after any preamble was being cut away entirely.
- "Texas", "Illinois", "Florida", "USA", "London", "United Kingdom" and major Indian cities/states now resolve to their jurisdiction (previously fell through to the India fallback for prompt localization).
- `lock_on` lists EXECUTED explicitly (no practical change: EXECUTED is only reachable from PENDING_SIGNATURE, which already locks).
