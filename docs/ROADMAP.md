# Product & Engineering Roadmap

Single consolidated view of everything planned, in priority order. References
detailed design docs where they exist. Status legend:

- ✅ **Done** — shipped to main, deploys on next `lp deploy`
- 🔧 **Partial** — code exists but not fully wired / tested
- 📋 **Planned** — designed, documented, not yet built
- 💡 **Idea** — worth doing, not yet designed

Updated: 2026-04-16

---

## P0 — Must-have before ANY customer pilot

| Item | Status | Detail doc | Notes |
|---|---|---|---|
| Hard tenant isolation (per-VM model) | ✅ Done | PRIVACY_AND_TENANCY_PLAN.md | Single-tenant-per-VM via deploy/lp |
| Ingest-time anonymization (NER + synthetic substitution) | ✅ Done | — | LLM-based; works but slow + fallible (see P1 for Presidio upgrade) |
| Dynamic firm-wide entity denylist | ✅ Done | — | Auto-derived from anonymization maps |
| Retrieval excludes non-anonymized chunks | ✅ Done | — | is_anonymized fail-safe |
| Re-index admin endpoint | ✅ Done | — | POST /api/v1/admin/reindex-anonymization |
| **Retrieval audit log** | 📋 Planned | PRIVACY_AND_TENANCY_PLAN.md Phase 0 | `rag_audit_log` table: {firm_id, user_id, matter_id, query_hash, retrieved_doc_ids, timestamp}. Table stakes — law firms will ask "prove Client A's data didn't appear in Client B's output." |
| **Self-host / ZDR documentation** | 📋 Planned | PRIVACY_AND_TENANCY_PLAN.md Phase 0 | ToS + DPA template asserting no data to third parties |

## P1 — Solo / small firm pilot (2-3 weeks)

| Item | Status | Detail doc | Notes |
|---|---|---|---|
| Async draft generation (fire-and-forget) | ✅ Done | — | Single button, strip view, polling, sweepers |
| Document summary tab | ✅ Done | — | Contract Review > Summary |
| Simplified Ask AI (contract-scoped Q&A) | ✅ Done | — | Select doc, ask question |
| Config-driven clause types | ✅ Done | — | clauses.yml + ClauseTypeRegistry |
| Config-driven contract types | ✅ Done | — | contract_types.yml + ContractTypeRegistry |
| Config-driven denylists | ✅ Done | — | denylists.yml + DenylistRegistry + DynamicEntityDenylistService |
| Prompt override via YAML | ✅ Done | — | clause_prompts.yml + PromptRepository |
| Pre-draft mode scratchpad | ✅ Done | V4_RUNTIME_IMPROVEMENTS.md #3 | Guided JSON mode check before each clause |
| Contextual Retrieval at ingest | ✅ Done | V4_RUNTIME_IMPROVEMENTS.md #4 | Anthropic technique, +49-67% recall |
| Entity denylist (static + dynamic) | ✅ Done | — | Combined YAML seed + firm-corpus-derived |
| Cross-clause consistency checker | ✅ Done | — | Notice periods, currencies |
| Sub-clause numbering QA | ✅ Done | — | Mixed-prefix detection + retry |
| Cross-article content bleed QA | ✅ Done | — | YAML + auto-derived forbidden headings |
| Output sanitizer (training artifacts + RAG headers) | ✅ Done | — | 15+ marker patterns |
| Prefix-cache optimized prompt assembly | ✅ Done | V4_RUNTIME_IMPROVEMENTS.md #6 | Invariants first |
| Backend retry wrapper (ChatRetry) | 🔧 Partial | feat/retry-and-resume-wip branch | Wired for risk/drilldown/summary/ask; draft clause retry not yet |
| Frontend axios retry | 🔧 Partial | feat/retry-and-resume-wip branch | On the WIP branch, not on main |
| Draft resume from last clause | 🔧 Partial | feat/retry-and-resume-wip branch | V22 migration + doAsyncDraft resume logic; endpoint + button pending |
| **Wire BM25 into retrieval** | 🔧 Partial | V4_RUNTIME_IMPROVEMENTS.md #7 | V24 table + Bm25SearchService created, but NOT wired into DraftContextRetriever. Need RRF score fusion. **Biggest RAG quality lever remaining.** |
| **Replace LLM NER with Presidio + legal recognizers** | 📋 Planned | PRIVACY_AND_TENANCY_PLAN.md Phase 1 | 10× faster, higher recall, confidence scores. Target F1 ≥ 0.92. |
| **Canary test suite** | 📋 Planned | PRIVACY_AND_TENANCY_PLAN.md Phase 1 | Inject 10 fake entities across test docs; CI test that drafts never surface them. |
| **Output NER for party names** | 📋 Planned | V4_RUNTIME_IMPROVEMENTS.md #2 | Regex catches $ and dates; PERSON/ORG names need NER. Add behind feature flag (~1s latency per clause). |
| **Deterministic sub-clause numbering** | 📋 Planned | V4_RUNTIME_IMPROVEMENTS.md #1 | Either XGrammar CFG on vLLM (needs client bypass) OR post-gen regex renumbering in code. Harvey does this with code, not LLM. |
| **Eval harness** | 📋 Planned | V4_TRAINING_PLAN.md Priority 5 | 100 held-out prompts, BigLaw-Bench rubric, deterministic parser checks + LLM judge. Required before v4 training AND before production quality claims. |
| **DPA template** | 📋 Planned | PRIVACY_AND_TENANCY_PLAN.md Phase 1 | ABA Opinion 512 informed-consent disclosure |
| **Mandatory metadata on upload (frontend validation)** | ✅ Done | — | Contract type dropdown required, upload button disabled until selected |

## P2 — Mid-market readiness (4-8 weeks)

| Item | Status | Detail doc | Notes |
|---|---|---|---|
| Synthetic substitution at ingest (firm-wide RAG) | ✅ Done | PRIVACY_AND_TENANCY_PLAN.md Phase 2 | Already implemented as AnonymizationService |
| Matter-scoped retrieval | ✅ Done | — | matter_id on chunks, filter in retriever |
| **Ethical wall enforcement** | 📋 Planned | PRIVACY_AND_TENANCY_PLAN.md Phase 2 | Per-user access matrix on matters; retrieval filter respects it |
| **Dual-index retrieval** (raw-by-matter + anonymized-firmwide) | 📋 Planned | PRIVACY_AND_TENANCY_PLAN.md Phase 2 | Currently all-anonymized; raw index for same-matter display |
| **SOC 2 Type I** | 📋 Planned | PRIVACY_AND_TENANCY_PLAN.md Phase 2 | 3-month auditor engagement |
| **Customer-facing audit export** | 📋 Planned | PRIVACY_AND_TENANCY_PLAN.md Phase 2 | Admin downloads {user, query, retrieved_docs, draft_entities} |
| **Golden clause candidate extraction** | 📋 Planned | — | Auto-extract from uploads → candidate queue → admin reviews → promotes to golden |
| **Rubric-based critique loop** | 📋 Planned | V4_RUNTIME_IMPROVEMENTS.md #9 | Post-gen LLM judge with manifest + rubric; gate behind config flag |
| **Model routing** (small for easy, large for hard) | 💡 Idea | V4_RUNTIME_IMPROVEMENTS.md #6 | Definitions → cheap model; Liability → fine-tuned 26B |
| **Speculative decoding** | 🔧 Partial | V4_RUNTIME_IMPROVEMENTS.md #10 | Flag added to deploy/v3-run.sh; not tested |
| **Grounding verification** | 💡 Idea | — | NLI-based check: "does each claim in the draft map to a retrieved precedent?" |
| **Monitoring / alerting** | 💡 Idea | — | Prometheus + Grafana: draft failure rate, QA retry rate, anonymization miss rate, denylist hit rate, retrieval recall |

## P3 — BigLaw / enterprise (6-12 months)

| Item | Status | Detail doc | Notes |
|---|---|---|---|
| Per-customer VPC option | 📋 Planned | PRIVACY_AND_TENANCY_PLAN.md Phase 3 | Harvey Enterprise pattern |
| BYOK encryption | 📋 Planned | PRIVACY_AND_TENANCY_PLAN.md Phase 3 | — |
| SOC 2 Type II + ISO 27001 | 📋 Planned | PRIVACY_AND_TENANCY_PLAN.md Phase 3 | 6-month audit window |
| Data residency (EU, UK, AU) | 📋 Planned | PRIVACY_AND_TENANCY_PLAN.md Phase 3 | — |
| Penetration testing (quarterly) | 📋 Planned | PRIVACY_AND_TENANCY_PLAN.md Phase 3 | — |
| DP fine-tuning (optional per-firm) | 📋 Planned | PRIVACY_AND_TENANCY_PLAN.md Phase 3 | Only if BigLaw specifically demands |

## Infrastructure / DevOps

| Item | Status | Notes |
|---|---|---|
| Auto-prune on deploy | ✅ Done | deploy/lp prunes images + build cache after health check |
| Deploy script fixes (transformers upgrade, merge path) | ✅ Done | deploy/v3-run.sh + v3_merge.py |
| N-gram speculative decoding config | 🔧 Partial | Flag in deploy script; not verified on A100 |
| **All-on-GPU deployment** | 💡 Idea | Backend + frontend + Postgres + vLLM on one RunPod pod; eliminates ngrok. Good for testing. |
| **RunPod Network Volume** | 💡 Idea | $14/mo persistent storage; cold start drops from ~10min to ~3min |
| **OCR for scanned PDFs** | 💡 Idea | Tesseract / AWS Textract; currently scanned PDFs fail silently |
| **Graceful LLM degradation** | 💡 Idea | If vLLM is down, fall back to Gemini API for non-draft tasks (risk, summary, ask) |
| **Per-user rate limiting** | 💡 Idea | RateLimitFilter exists but is IP-based; needs per-authenticated-user throttle for draft endpoint |

## Training (v4) — separate timeline

| Item | Status | Detail doc |
|---|---|---|
| Loss-masking fix (DataCollatorForCompletionOnlyLM) | 📋 Planned | V4_TRAINING_PLAN.md Priority 1 |
| LoRA dropout 0.05-0.1 | 📋 Planned | V4_TRAINING_PLAN.md Priority 1 |
| Sentinel scrub (\[INST\], \_\_TOKEN\_\_) | 📋 Planned | V4_TRAINING_PLAN.md Priority 2 |
| Per-clause dedup (MinHash LSH) | 📋 Planned | V4_TRAINING_PLAN.md Priority 2 |
| Template memorizable entities | 📋 Planned | V4_TRAINING_PLAN.md Priority 2 |
| Contract-type tags in training | 📋 Planned | V4_TRAINING_PLAN.md Priority 3 |
| RAG-shaped training samples | 📋 Planned | V4_TRAINING_PLAN.md Priority 3 |
| Production-path replay experiment | 📋 Planned | V4_RUNTIME_IMPROVEMENTS.md "Why v3 evals well standalone" |

## Research / novel directions (not scheduled)

| Item | Detail doc |
|---|---|
| Mode-Token-Gated LoRA | V4_RUNTIME_IMPROVEMENTS.md speculative #A |
| Contrastive Decoding for memorization scrubbing | V4_RUNTIME_IMPROVEMENTS.md speculative #B |
| Dynamic Grammar Composition from metadata | V4_RUNTIME_IMPROVEMENTS.md speculative #C |

---

## Docs index

| Doc | Scope |
|---|---|
| `docs/V4_TRAINING_PLAN.md` | Dataset cleanup + training pipeline for v4 adapter |
| `docs/V4_RUNTIME_IMPROVEMENTS.md` | Non-training quality levers (top-10 + eval gap analysis + novel ideas) |
| `docs/PRIVACY_AND_TENANCY_PLAN.md` | Cross-client confidentiality + pre-customer-launch phases |
| `docs/ROADMAP.md` | **This file** — consolidated view of everything |
