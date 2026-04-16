# v4 Runtime Improvements — Non-Training Quality Levers

Companion to `V4_TRAINING_PLAN.md`. This doc captures quality improvements we
can make **without touching the model weights** — prompt engineering, retrieval
architecture, agentic patterns, constrained decoding, verification, caching.
Independent of training; can be shipped against the current v3 adapter.

Written 2026-04-16 after a deep research pass on production legal AI
(Harvey, CoCounsel, Anthropic, vLLM, 2024-2026 papers). Working doc — we
iterate as we ship.

---

## Current state — what's already in place

Before listing new things, what we have:

- **Multi-query retrieval** + cross-encoder reranking + metadata filter
  (contract-type + matter scoped as of commit `ecc0824`)
- **Per-clause drafting loop** with TerminologyManifest threaded through
  every call (party names, defined terms, style fingerprint, full article
  plan, prior clause outlines)
- **QA retry loop** with structural + semantic checks; best-of-N scoring
- **Output sanitizer** — truncates at training-format markers, RAG source
  headers, pipe-delimited metadata
- **Stop sequences** on the vLLM call (catches `__PROCESSED_REQUEST__` etc.
  before they ever hit the client)
- **Sub-clause numbering consistency QA** + cross-article content-bleed QA
  (commit `22bb6fa`)
- **Transient-error retry wrapper** on LLM calls + frontend axios retry
- **Bean split** — small model (2K tokens) for risk/Q&A/extract, big model
  (6K tokens) for drafting

Good runtime baseline. Residual defects after all of the above:

1. Entity memorization leaking through (Ontario, Acme, Mahindra, $5M)
2. SaaS→MSA mode blur
3. Cross-article content bleed (some cases slip past the QA check)
4. Sub-clause numbering collapse (QA catches it, retry sometimes fails too)
5. RAG contamination — source filenames copied into output
6. Model ignores format instructions when it's confident in a memorized answer

---

## Top 10 non-training improvements — ranked

Evidence strength: **Published** = peer-reviewed or widely-cited production
source; **Anecdotal** = engineering folklore or targeted fix; **Speculative**
= plausible but unvalidated.

| # | Improvement | ROI | Effort | Evidence |
|---|---|---|---|---|
| 1 | XGrammar CFG for sub-clause numbering on vLLM | High | 1 wk | Published |
| 2 | Entity memorization denylist + post-gen check | High | 1 day | Anecdotal, targeted |
| 3 | Pre-draft scratchpad (guided JSON first, then draft) | High | 1 wk | Published |
| 4 | Contextual Retrieval (Anthropic technique) | High | 1 wk | Published, 49-67% gain |
| 5 | Cross-clause consistency checker (post-gen) | High | 1 wk | Anecdotal |
| 6 | Prefix-cache stability — byte-stable system+manifest prefix | High | 2 days | Published (~10x prefill) |
| 7 | Hybrid retrieval (add BM25 to dense+rerank) | Med-High | 3 days | Published (+17-39% recall) |
| 8 | Convert negative → positive instructions + regex denylist | Med | 1 day | Published |
| 9 | Rubric-based critique→revise (with external criteria) | Med | 1 wk | Published |
| 10 | Speculative decoding in vLLM | Med | 1 wk | Published, quality-neutral |

### Each in detail

#### 1. XGrammar CFG for sub-clause numbering
vLLM's guided decoding (XGrammar backend) lets us specify a context-free
grammar that forces token emission to match a regex/CFG. Define a grammar
that mandates `^(\d+)\.(\d+)(\.(\d+))?\s+` for sub-clause headings, with the
article index pinned via grammar state. The model literally cannot emit
`4.1` when drafting Article 2. Replaces QA-retry with impossible-to-violate
decoding. Reuse the compiled grammar across requests for cache benefit.

Reference: [vLLM Structured Outputs](https://docs.vllm.ai/en/latest/features/structured_outputs/),
[XGrammar paper (arXiv 2411.15100)](https://arxiv.org/pdf/2411.15100)

#### 2. Entity memorization denylist
Maintain a known-bad list of entities the model picked up during training:
`Ontario`, `Acme`, `Acme Technologies`, `Mahindra`, `NeuroPace`, `Niagen`,
`$5,000,000`, `Contractor`, `Vendor`, `Company` (when used as party names),
etc. After generation, scan the output; if a denylisted entity appears AND
wasn't in the user's brief, flag and force retry. Trivial to implement,
directly targets the Ontario/Acme/Mahindra problem.

#### 3. Pre-draft scratchpad (guided JSON)
Before drafting a clause, run a short guided-JSON call asking the model to
emit:
```json
{
  "contract_mode": "SAAS|MSA|NDA|...",
  "article_index": 2,
  "article_title": "Services",
  "forbidden_terms": ["Statement of Work", "Deliverables", "milestone payment"],
  "required_terms": ["subscription", "uptime", "authorized user"]
}
```
Use that output as additional context for the subsequent drafting call.
Directly targets contract-type mode blur. Cheap — guided JSON is fast.

Reference: [Guided Decoding and RAG (arXiv 2509.06631)](https://arxiv.org/html/2509.06631v1)

#### 4. Contextual Retrieval (Anthropic, Sept 2024)
Before embedding each chunk, prepend 50-100 tokens of document-level context
("This chunk is Section 7.3 Limitation of Liability from a SaaS agreement
governed by California law"). Embed the contextualized chunk. Retrieval
quality goes up 49-67% (49% with contextual BM25, 67% with reranker added).
Cost: one LLM call per chunk at ingest time (~$1/M tokens with prompt caching).

Direct fix for "chunk lacks context" failure mode. Requires re-embedding the
corpus once. After that it's free.

Reference: [Anthropic: Contextual Retrieval](https://www.anthropic.com/news/contextual-retrieval)

#### 5. Cross-clause consistency checker
A dedicated post-generation pass that reads the whole draft and checks for
internal contradictions:
- Payment term in Article 3 vs survival clause in Article 7
- Liability cap number in Article 6 vs indemnity cap in Article 7
- Termination notice period in Article 7 vs survival in Article 7
- Governing law in Article 9 vs dispute-resolution seat
- Any defined term used before it's defined

Simple LLM call with the full draft + a checklist. Cheap, high signal.

#### 6. Prefix-cache stability
vLLM's Automatic Prefix Caching hashes the prompt prefix; identical prefixes
reuse the KV cache. For per-clause drafting with a shared system prompt +
TerminologyManifest, this can drop prefill cost ~10x.

Action: audit `generateClauseWithQa` prompt assembly to ensure the shared
prefix (system prompt + manifest) is byte-identical across clause calls and
appears FIRST. Variable content (clause-specific user prompt) must come at
the end. Also verify vLLM was started with `--enable-prefix-caching` (it's
default in recent vLLM).

Reference: [vLLM APC docs](https://docs.vllm.ai/en/stable/design/prefix_caching/)

#### 7. Hybrid retrieval (BM25 + dense)
Across 2025 benchmarks, hybrid BM25 + dense + cross-encoder rerank hits
Recall@5 ~0.82 vs 0.59 for dense-only (+39%). Legal texts are lexically
dense (defined terms, section numbers, party names) — BM25 often beats dense
alone. Our current retrieval is dense + rerank. Add a BM25 sparse index
alongside (Postgres `tsvector` or dedicated FTS), weight the scores, rerank.

Reference: [Superlinked VectorHub: hybrid search](https://superlinked.com/vectorhub/articles/optimizing-rag-with-hybrid-search-reranking)

#### 8. Positive instructions + regex denylist
The "pink elephant" effect: negations often backfire because the model has to
represent the forbidden concept to suppress it. Convert negative instructions
in our clause prompts from:
> Do NOT use Statement of Work, milestone payments, or Deliverables.

to:
> Use `subscription`, `authorized user`, `uptime` exclusively. The contract
> is a SaaS subscription; the language should reflect platform access, not
> project delivery.

And enforce the ban at the output stage via regex (already have sanitizer —
extend it with a per-contract-type banned-phrase list).

Reference: [The Pink Elephant Problem](https://eval.16x.engineer/blog/the-pink-elephant-negative-instructions-llms-effectiveness-analysis)

#### 9. Rubric-based critique→revise
Free-form self-critique ("is this good?") doesn't work — models can't
reliably spot their own errors. What DOES work: critique with explicit
criteria.

Add an optional revise pass:
1. Generate clause (existing code)
2. Run a short critique call: "Score this clause against these 6 criteria:
   uses defined terms, matches article number, no cross-article content, no
   training-format markers, jurisdiction consistent, no memorized entities"
3. If score < threshold, regenerate with the critique as feedback

Only worth running when the first pass has borderline QA issues. Adds
latency; gate behind a config flag.

Reference: [Self-Refine works with structured criteria (2025 review)](https://arxiv.org/abs/2512.24103)

#### 10. Speculative decoding
vLLM supports speculative decoding with a small draft model (e.g., Gemma 2B).
2-3x latency reduction with *identical* output distribution (provably
quality-neutral). Lets us afford extra test-time passes (critique, scratchpad,
etc.) above.

Reference: [vLLM speculative decoding](https://developers.redhat.com/articles/2025/11/19/speculators-standardized-production-ready-speculative-decoding)

---

## What the research agent flagged as hype

**Things that sound good but have thin evidence for our use case:**

- **Persona prompting** ("You are a senior BigLaw associate") — affects tone,
  not factuality. Cut it from our prompts.
- **Multi-agent drafter + reviewer chat** — gains come from narrow verifiable
  checks, not role-play between agents. The cross-clause consistency check
  is the useful version; a "drafter talks to reviewer" loop is theater.
- **Ever-longer context windows** — confirmed diminishing returns ("context
  rot"). More context ≠ better output.
- **"Reasoning models will fix everything"** — drafting is retrieval-and-
  style-bound, not reasoning-bound. CoT helps benchmarks more than it helps
  production drafting quality.
- **GraphRAG** — overkill for our setup; contracts have clear structure
  already (articles, clauses, schedules).
- **HyDE** — useful only when queries are too terse for dense retrieval.
  Our clause queries are already fully-formed.

---

## Implementation phases

### Phase 1 — quick wins (can ship without pod restart, pure backend code)

Items 2, 5, 6, 8 — all shippable as code-only changes.

- Entity denylist + QA check
- Cross-clause consistency checker
- Prefix-cache prompt assembly audit
- Convert negative→positive in prompts + regex denylist

### Phase 2 — vLLM feature integration

Items 1, 3, 10 — require vLLM feature flags; can code now, verify when pod is up.

- XGrammar CFG for sub-clause numbering (`guided_grammar` param)
- Pre-draft scratchpad (`guided_json` param)
- Speculative decoding (small draft model on pod)

### Phase 3 — retrieval pipeline upgrades

Items 4, 7 — require re-embedding corpus + infrastructure.

- Contextual Retrieval (re-embed with document-level context prefix)
- Hybrid retrieval (add Postgres tsvector or separate BM25 index)

### Phase 4 — optional critique loop

Item 9 — add only if phases 1-3 don't close the quality gap.

---

---

## Why v3 evals cleanly standalone but degrades through the production system

Observation worth investigating: the 35-prompt direct eval after training
showed v3 producing "solid legal output" across drafting, extraction, QA,
risk, and summary. In production, we see Ontario-in-California, Acme/Mahindra
party-name leaks, SaaS→MSA mode blur, RAG filename copying, numbering
collapse. Same model, different quality. Why?

Ranked hypotheses (highest probability first):

1. **RAG contamination is the biggest single delta.** The direct eval was
   prompt-only — no retrieved precedent chunks in the input. Production
   stuffs chunks from the firm corpus (until recently permissively, now
   scoped). When the chunk contains `Acme / Mahindra / Ontario / $5M /
   Contractor-Company`, the model copies it verbatim. Most of our visible
   defects trace directly to this.
   - **Test to confirm:** re-run the 35-prompt eval through the production
     code path with `ctx = empty DraftContext`. If drafts come out clean,
     RAG was the entire problem. If they still leak, something else.

2. **Prompt shape drift.** The eval prompt is ~500 tokens (clean template).
   The production prompt is 2-4K tokens (GUARDRAILS + manifest + RAG +
   localized system + scratchpad + user prompt). Long prompts dilute
   attention — the model is more likely to latch onto a memorable token
   (Ontario, Mahindra) when the signal-to-noise ratio drops.

3. **Multi-clause continuity drift.** Eval tested one clause in isolation.
   Production drafts 9 clauses sequentially, each seeing prior outlines
   and (until recently) the last clause's full text. Small biases compound
   across clauses.

4. **Sampling differences.** Eval may have used greedy decoding or
   low-temperature; production uses vLLM defaults. N-gram speculative
   decoding (just added) is quality-neutral but worth verifying.

5. **max_tokens disparity.** Eval used a reasonable cap matching training
   distribution. Production had maxTokens=6000 until the bean split. A
   bigger budget lets the model ramble into memorized material instead
   of stopping naturally.

### What to do

Before v4 training, run a **production-path replay** of the 35-prompt
eval set:
- `data/raw/v3_eval_results.json` already has v3 direct-eval outputs
- Run those same 35 prompts through `DraftService.generateClauseWithQa`
  with and without RAG context
- Diff the outputs
- Whatever gap remains after removing RAG is the "prompt-shape + multi-
  clause + sampling" delta

This single experiment tells us whether v4 training is even the right
lever — if turning off RAG fixes 80% of defects, the training-scrub
work is lower priority than fixing the RAG pipeline.

---

## Speculative novel research directions (not yet evaluated)

These are ideas for genuinely novel methods that *might* be worth
exploring if we want a publishable contribution. Each is generated
speculatively — novelty is NOT verified against the full literature.
Stress-test each before committing engineering effort.

### A. Mode-Token-Gated LoRA

Add `<MODE:SAAS>`, `<MODE:MSA>`, `<MODE:NDA>` etc. as special tokens to
the vocabulary during a short v4 fine-tune. At inference, force the
model to emit the mode token first (grammar-constrained). The mode
token gates which LoRA sub-adapter activates. Attacks contract-type
blur at the weight level, not the prompt level.

**Prior art risk:** LoRA MoE routing (LoRA-Switch, LoraHub) and
conditional-computation via prompt tokens both exist. The *legal-
specific framing with mode-token-gated drafting sub-adapters* may be
new, but the underlying architecture probably isn't truly novel.

**Next step if pursued:** literature search for "mode-conditioned
LoRA" + "special-token-gated adapter routing" + "contract-type
conditional generation".

### B. Contrastive Decoding for Memorization Scrubbing

At each generation step, run two inferences in parallel:
- Model A = fine-tuned (v3 adapter + base)
- Model B = base model without the adapter
For each token, if A's top candidate is in a denylist (Ontario,
Acme, Mahindra, $5M) AND A's distribution diverges from B's by
threshold τ, use B's distribution instead. Keeps adapter's learned
legal knowledge; blocks specifically memorized tokens.

**Prior art risk:** Contrastive decoding (Li et al. 2023) is an
established technique for hallucination reduction. Applying it
specifically to memorization mitigation in fine-tuned models may
or may not be published — would need a targeted search.

**Next step if pursued:** literature survey for "contrastive
decoding memorization" + "fine-tuning unlearning via decoding" +
"PII leakage mitigation inference-time".

### C. Dynamic Grammar Composition from Structure Metadata

Compose vLLM's `guided_grammar` dynamically at inference: the
TerminologyManifest + article plan + expected sub-clause counts
feed a grammar generator that outputs a CFG specific to THIS
draft. Article 2 with 3 sub-clauses gets a grammar forcing the
pattern `2\.1 ... 2\.2 ... 2\.3` in order. Cross-article content
bleed becomes structurally impossible, not just QA-flagged after
the fact.

**Prior art risk:** Grammar-constrained generation (XGrammar,
Guidance, Outlines) is well-known. Dynamic per-document grammar
synthesis from structured metadata — I don't know of a published
paper on this specifically for legal drafting, but careful
literature search is needed. Closest published work is probably
"Parsel" (Zelikman et al. 2023) which does code generation with
dynamic grammars.

**Next step if pursued:** literature survey for "dynamic grammar
constrained decoding" + "schema-driven generation" + "document-
structure-aware decoding".

### Honest assessment of these

None of these are guaranteed novel. "Generate a candidate" is cheap;
"verify it's novel and shows it works" is hard. The path from any of
A/B/C to a publishable paper:

1. Thorough literature survey against the specific framing
2. Prototype implementation (A probably needs a new fine-tune; B/C
   are inference-time and cheaper)
3. Design experiments comparing against our existing baseline +
   published alternatives
4. Run on a proper held-out eval (100+ drafting prompts)
5. Ablation study + error analysis
6. Write up with reproducibility

Weeks to months of work each. The lowest-risk research publication
path is still the *taxonomy + eval harness + systematic ablation*
approach, not inventing new methods.

---

## Sources

- [Harvey: Introducing Agents](https://www.harvey.ai/blog/introducing-harvey-agents)
- [Harvey: Building an agent for complex document drafting](https://www.harvey.ai/blog/building-an-agent-for-complex-document-drafting-and-editing)
- [ZenML: Harvey agent-based architecture](https://www.zenml.io/llmops-database/scaling-agent-based-architecture-for-legal-ai-assistant)
- [CoCounsel Legal launch](https://www.lawnext.com/2025/08/thomson-reuters-launches-cocounsel-legal-with-agentic-ai-and-deep-research-capabilities-along-with-a-new-and-final-version-of-westlaw/)
- [Anthropic: Contextual Retrieval](https://www.anthropic.com/news/contextual-retrieval)
- [Anthropic: Effective Context Engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [Anthropic: Use XML tags](https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/use-xml-tags)
- [vLLM Structured Outputs docs](https://docs.vllm.ai/en/latest/features/structured_outputs/)
- [vLLM blog: Structured Decoding intro](https://blog.vllm.ai/2025/01/14/struct-decode-intro.html)
- [vLLM APC docs](https://docs.vllm.ai/en/stable/design/prefix_caching/)
- [XGrammar (arXiv 2411.15100)](https://arxiv.org/pdf/2411.15100)
- [Guided Decoding and RAG (arXiv 2509.06631)](https://arxiv.org/html/2509.06631v1)
- [Chain of Reference helps LLMs think like a lawyer (GenLaw ICML 2024)](https://blog.genlaw.org/CameraReady/37.pdf)
- [Large Legal Fictions (Stanford JLA)](https://dho.stanford.edu/wp-content/uploads/Hallucinations_JLA.pdf)
- [The Pink Elephant Problem](https://eval.16x.engineer/blog/the-pink-elephant-negative-instructions-llms-effectiveness-analysis)
- [Superlinked VectorHub: hybrid search](https://superlinked.com/vectorhub/articles/optimizing-rag-with-hybrid-search-reranking)
- [LlamaIndex: small-to-big retrieval](https://medium.com/data-science/advanced-rag-01-small-to-big-retrieval-172181b396d4)
- [HyDE (Gao et al. 2022)](https://arxiv.org/abs/2212.10496)
- [Self-Refine has structured criteria limitations (arXiv 2512.24103)](https://arxiv.org/abs/2512.24103)
- [Scalable Best-of-N via Self-Certainty (arXiv 2502.18581)](https://arxiv.org/html/2502.18581v1)
- [Lost in the Middle (Liu et al. 2023)](https://teapot123.github.io/files/CSE_5610_Fall25/Lecture_12_Long_Context.pdf)
- [Serial Position Effects in LLMs (arXiv 2406.15981)](https://arxiv.org/html/2406.15981v1)
- [vLLM speculative decoding (Red Hat, 2025)](https://developers.redhat.com/articles/2025/11/19/speculators-standardized-production-ready-speculative-decoding)
- [FACTUM — citation verification via attention signals (arXiv 2601.05866)](https://arxiv.org/pdf/2601.05866)
