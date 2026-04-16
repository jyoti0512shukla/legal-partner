# Confidentiality, Tenant Isolation, and Pre-Customer-Launch Plan

Addresses the cross-client confidentiality problem in RAG-based contract
drafting: when the model pulls Client A's precedent to draft for Client B,
it can copy A's names/figures/dates verbatim into B's contract. This is a
state-bar-level compliance blocker, not just a quality issue.

Written 2026-04-16 after researching how Harvey, Spellbook, Ironclad, CoCounsel,
LexisNexis and others handle this in production. Working doc — review before
onboarding the first paying customer.

---

## The core insight from industry

Contrary to what I'd assumed, **production legal AI vendors do NOT rely on
anonymization as the primary defense**. The consistent pattern is:

1. **Hard tenant isolation at the retrieval layer** — Client B's retriever
   never sees Client A's shard. Harvey calls this "logical separation in
   infrastructure so that every user is associated with unique workspaces,
   and workspaces can't communicate with each other."
2. **Zero-retention contracts** with any third-party inference — ZDR with
   OpenAI / Anthropic / AWS Bedrock; or self-hosted with no retention.
3. **Customer-controlled storage** — Harvey Enterprise puts embeddings in
   the customer's own cloud bucket so they "never leave customer-controlled
   environments."
4. **Audit logs** that can answer "did Matter X data surface in a Matter Y
   response, for any user, on any date."

Anonymization (NER + synthetic substitution) is a **defense-in-depth layer
on top**, not the main protection. Vendors that *do* use anonymization heavily
(Spellbook) push it as the attorney's responsibility ("anonymize before you
prompt") rather than automating it, because doing it automatically with high
reliability is hard and partial anonymization can be worse than none (false
sense of safety).

### Two architectural archetypes

| Archetype | Example | How they avoid cross-client leakage |
|---|---|---|
| **Own-corpus RAG** | CoCounsel, Lexis+ | They retrieve from their own content (Westlaw, Lexis corpus) — not customer data. Sidesteps the problem entirely. |
| **Customer-corpus RAG** | Harvey, Spellbook, Ironclad, Draftwise | Hard per-tenant (sometimes per-matter) isolation at the retriever. Anonymization secondary. |

We are archetype 2. This doc focuses on that path.

---

## Where we are today (as of commit `eb5cc50`)

What we have:
- Per-document `source` metadata (USER / EDGAR / DRAFT_ASYNC)
- Per-chunk `matter_id` metadata (just added — commit `ecc0824`)
- Retrieval filter: `(same matter) OR (same contract type)`; untagged chunks excluded
- Entity denylist QA check (small, hardcoded — `MEMORIZED_ENTITY_DENYLIST`)
- Output sanitizer that truncates at training-format markers + RAG headers
- Mandatory `documentType` on user uploads (400 on missing)

What we **don't** have:
- ❌ Hard tenant (`firm_id`) isolation — there IS no multi-firm concept yet
- ❌ Zero-retention agreements with inference provider (we self-host vLLM, so technically fine, but not documented)
- ❌ Audit log of retrieval events
- ❌ NER-based anonymization of uploaded precedents
- ❌ Output-side entity verification against "seen in precedent but not in user brief"
- ❌ SOC 2 Type I or Type II
- ❌ Data residency options

---

## Phased plan — what each phase unlocks

Starting state: current repo. Target: safely onboard a paying law firm.

### Phase 0 — Pre-any-customer essentials (1 week)

Non-negotiable before even an informal pilot.

- [ ] **Tenant concept in DB** — add `firm_id` on DocumentMetadata, Matter,
  User. Every entity gets scoped to a firm. Even if the first customer is a
  solo practitioner, putting the concept in now avoids a brutal migration
  later.
- [ ] **Hard filter at retrieval** — `DraftContextRetriever.filterByMetadata`
  adds a mandatory `firm_id` equality check. Unit test that fails if the
  filter is missing (regression test for the entire category).
- [ ] **Retrieval audit log** — new table `rag_audit_log` with columns
  `{id, firm_id, user_id, matter_id, query_text_hash, retrieved_doc_ids,
  timestamp, clause_key, model_version}`. Log every call, retain ≥12 months.
- [ ] **Self-host assertion** — document in ToS + customer-facing FAQ that
  inference runs on our controlled vLLM instance; no data sent to third
  parties; no retention beyond the request.
- [ ] **Remove EDGAR-and-similar training-bleed precedents** from production
  corpus entirely (we did this for RAG already; confirm none remain in
  training samples for v4).

**Unlocks**: informal pilot with one friendly solo practitioner uploading
only their own contracts.
**Does NOT unlock**: anything with >1 firm, >1 client being served by the
same lawyer with overlapping precedents.

### Phase 1 — Solo / very small firm safe (2-3 weeks)

- [ ] **Matter scoping as default** — frontend defaults to "this matter's
  precedents only"; explicit opt-in to firm-wide precedents with a clear
  confirmation dialog. Firm-wide pool is still available for style learning,
  but user is aware.
- [ ] **Legal NER pipeline** — Presidio + custom legal recognizers for:
  - Party names (PERSON / ORG / law-firm-specific patterns)
  - Monetary amounts with currency
  - Specific dates (not date references like "within 30 days")
  - Physical addresses
  - Jurisdictions (governing law clauses)
  - Case / matter numbers
  - Email + phone
  Target F1 ≥ 0.92 on PERSON/ORG/MONEY/DATE. Validated on ~200 hand-labeled
  contracts.
- [ ] **Output-side entity verifier** — after the LLM drafts, run NER over
  the draft. For each entity detected, check: did it appear in (the user's
  deal brief) OR (the current matter's context) OR (a firm-approved token
  list)? If no → flag as potential cross-client leak. Fail the clause
  generation (retry with stricter prompt) rather than ship to the user.
- [ ] **Canary test suite** — insert 10 unique synthetic party names across
  test precedents. Automated test: draft contracts for unrelated matters,
  assert none of the canaries appear in output. Run on every CI build.
- [ ] **"Do not copy" guardrail prompt reinforcement** — tighten the RAG
  mandate to be even more explicit: "Any PERSON, ORG, MONEY, DATE, or
  JURISDICTION in the precedent that is NOT ALSO in the user's deal brief
  must be treated as a placeholder and filled from the deal brief, not
  copied verbatim."
- [ ] **Data Processing Addendum (DPA) template** — lightweight, matches
  the ABA Opinion 512 informed-consent requirement. Customer can hand to
  their clients for disclosure.

**Unlocks**: single-lawyer firm, or small firm (≤5 lawyers) where all
lawyers see all matters (no ethical walls). Self-hosted or dedicated single-
tenant deployment only.
**Does NOT unlock**: firms with client conflicts / Chinese walls; regulated
industries; multi-office firms; EU customers (GDPR).

### Phase 2 — Mid-market (4-8 weeks)

- [ ] **Synthetic substitution at ingest (Anthropic pattern)** — on upload,
  generate an anonymized version of each precedent where PERSON / ORG /
  MONEY / DATE / ADDRESS are replaced with type-consistent surrogates
  (e.g., "Acme Corp" → "Helix Industries", "$1,200,000" → scaled 0.8-1.25×
  → "$1,050,000"). Store BOTH: raw (shown to originating matter's lawyers
  only) and anonymized (used for embeddings + firm-wide retrieval).
  - **Important:** synthetic substitution preserves embedding quality and
    drafting fluency. `[PARTY_A]` placeholder tokens degrade embeddings
    noticeably.
  - Encryption-at-rest of the raw↔synthetic mapping; decrypt only for the
    originating matter.
- [ ] **Dual-index retrieval** — `raw_by_matter` index (matter-scoped) +
  `anonymized_firmwide` index (firm-scoped). Drafts pull from both; raw
  ranks higher when same-matter, anonymized ranks higher for style.
- [ ] **Ethical wall enforcement** — per-user access matrix on matters;
  retrieval filter respects it. User X can't retrieve from matters they're
  not a member of.
- [ ] **SOC 2 Type I** — start the 3-month auditor engagement. Needed for
  mid-market procurement checklists.
- [ ] **Customer-facing audit export** — firm admin exports `{user, query,
  retrieved_docs, draft_entities}` for any user / date range. Answers the
  "can you prove no leakage" question during security review.
- [ ] **Membership-inference canary** — add a quarterly red-team test:
  probe the deployed model with known-in-training tokens, confirm no
  detectable leakage.

**Unlocks**: 5-50 lawyer firms, sophisticated solos, boutique firms,
mid-market B2B contract teams.
**Does NOT unlock**: AmLaw 100 / BigLaw; multi-jurisdiction with EU; regulated
(healthcare/finance) in-house legal.

### Phase 3 — BigLaw / enterprise (6-12 months)

- [ ] **Per-customer VPC option** — vector DB + embeddings + file storage
  in customer-controlled cloud (the Harvey Enterprise pattern). Our control
  plane orchestrates; customer data stays in their cloud.
- [ ] **BYOK (bring your own key)** encryption for embeddings + stored
  precedents. Customer can rotate / revoke.
- [ ] **SOC 2 Type II + ISO 27001** (the combo procurement will demand).
- [ ] **Data residency** — EU, UK, AU deployment regions.
- [ ] **Certified anonymization** per GDPR Art. 29 WP standard — expert
  determination + statistical risk analysis. Typically contracts a
  consultancy. Realistic cost $50K-$150K.
- [ ] **Penetration testing** — contracted third party runs prompt-injection
  + membership-inference attacks quarterly; reports go to customers.
- [ ] **Optional: per-firm fine-tuned adapter with DP-SGD** — only if a
  BigLaw customer specifically demands a dedicated model trained on their
  corpus with differential privacy guarantees.

**Unlocks**: AmLaw 100 pilots, regulated in-house legal, EU firms.
**Does NOT unlock**: federated cross-customer learning — that's its own
research program.

---

## What's overkill for us (skip)

- **Fully homomorphic encryption for RAG**: 100-1000× latency penalty. Research-
  grade. Not needed.
- **`[PARTY_A]` placeholder masking**: genuinely degrades drafting quality vs
  synthetic substitution. Skip in favour of synthetic.
- **Differential privacy fine-tuning universally**: 2 orders of magnitude
  slower training + quality drop. Only use if BigLaw specifically demands.
- **k-anonymity proofs**: too brittle for freeform contract text. Use as an
  audit metric, not a runtime guarantee.

---

## Regulatory framework — what applies to us

Not legal advice — reference only. Engage actual ethics counsel before
customer launch.

- **ABA Formal Opinion 512 (July 2024)** — lodestar. Rule 1.1 (competence)
  requires lawyers to understand the tool and its limitations. Rule 1.6
  (confidentiality) requires informed client consent before inputting
  confidential data into "self-learning GAI tools." Our obligation: disclose
  enough that the lawyer can disclose to their client.
- **California State Bar (Nov 2023)** — "must not input any confidential
  information … into any generative AI solution that lacks adequate
  confidentiality and security protections; must anonymize client
  information and avoid entering identifying details."
- **Florida Bar 24-1 (Jan 2024)** — warns specifically that self-learning
  AI "may reveal a client's information in response to future prompts by
  third parties" — literally our risk model.
- **GDPR (for EU customers)** — anonymized data is outside GDPR; bar for
  true anonymization is "extremely high." Pseudonymization stays in-scope
  and triggers full obligations. A cross-client leak = 72-hour breach notice.

---

## Architectural revision to our plan

Earlier in `V4_RUNTIME_IMPROVEMENTS.md` I proposed matter-scoping as the
primary safety mechanism. The research shows that's too restrictive for
real legal-AI use: firms *want* firm-wide pattern learning. The revised
architecture:

- **Hard tenant (firm) isolation** — always on, non-negotiable (Phase 0)
- **Matter scoping as default** — user opts in to firm-wide (Phase 1)
- **Ingest-time synthetic substitution** for firm-wide retrievable corpus
  (Phase 2) — so style learning happens on anonymized content
- **Output-side entity verifier** — catches anything that slips through
  (Phase 1)

The `retrievable scope` and `anonymization` layers are independent. We need
both. Relying on only one is fragile.

---

## Sources

- [Harvey — How Harvey Manages Customer Data](https://www.harvey.ai/blog/how-harvey-manages-customer-data)
- [Harvey Enterprise RAG Systems (ZenML)](https://www.zenml.io/llmops-database/enterprise-grade-rag-systems-for-legal-ai-platform)
- [Spellbook — AI Data Privacy for Law Firms](https://www.spellbook.legal/learn/ai-data-privacy-law-firms)
- [Ironclad AI Assist FAQs](https://support.ironcladapp.com/hc/en-us/articles/14396778628503-Ironclad-AI-Assist-FAQs)
- [Thomson Reuters CoCounsel security (VentureBeat)](https://venturebeat.com/ai/how-thomson-reuters-and-anthropic-built-an-ai-that-tax-professionals-actually-trust)
- [LexisNexis AI Security Commitment](https://www.lexisnexis.com/community/insights/legal/b/thought-leadership/posts/ai-privacy-security-the-lexisnexis-commitment)
- [ABA Formal Opinion 512](https://library.law.unc.edu/2025/02/aba-formal-opinion-512-the-paradigm-for-generative-ai-in-legal-practice/)
- [California State Bar Generative AI Guidance (PDF)](https://www.calbar.ca.gov/Portals/0/documents/ethics/Generative-AI-Practical-Guidance.pdf)
- [Florida Bar Ethics Opinion 24-1](https://www.floridabar.org/etopinions/opinion-24-1/)
- [Microsoft Presidio](https://github.com/microsoft/presidio)
- [Private AI](https://www.private-ai.com/en)
- [Contracts-BERT NER benchmark (Springer)](https://link.springer.com/article/10.1007/s00521-024-09869-7)
- [Privacy Issues in RAG (arXiv 2402.16893)](https://arxiv.org/abs/2402.16893)
- [Privacy-Aware RAG (arXiv 2503.15548)](https://arxiv.org/html/2503.15548v1)
- [Membership Inference on Fine-tuned LLMs (arXiv 2311.06062)](https://arxiv.org/abs/2311.06062)
- [Anonymous-by-Construction LLM (arXiv 2603.17217)](https://arxiv.org/html/2603.17217v1)
- [K-Anonymity Guide (Immuta)](https://www.immuta.com/blog/k-anonymity-everything-you-need-to-know-2021-guide/)
- [GDPR Anonymization vs Pseudonymization (IAPP)](https://iapp.org/news/a/looking-to-comply-with-gdpr-heres-a-primer-on-anonymization-and-pseudonymization)
- [Re-identification of Law School Data](https://techscience.org/a/2018111301/)
