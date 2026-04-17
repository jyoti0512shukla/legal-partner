# v4 Training Plan — Research Findings + Action Items

Working doc capturing what we know, what the literature says, and what we'll do
differently when we train v4. Not final — we use this to iterate.

Written 2026-04-16 after a deep research pass on legal-LLM training (SaulLM,
LegalBench, Harvey, production post-mortems, LoRA memorization papers). See
"Sources" at the bottom for the primary references.

---

## Base model decision: SaulLM-54B (not Gemma 4 26B)

**Decided 2026-04-17** based on head-to-head eval (scripts/eval_pipeline.py).

SaulLM-54B-Instruct **zero-shot** (no fine-tuning) scored **0.817** on our
18-prompt eval pipeline. Key comparisons vs our fine-tuned Gemma 4 v3:

| Dimension | Gemma 4 26B + v3 LoRA | SaulLM-54B zero-shot |
|---|---|---|
| Drafting score | poor (Ontario, Acme, MSA-in-SaaS) | **0.830** |
| Mode blur | Severe (full MSA in SaaS) | Mild (occasional term leak) |
| Training artifacts | `__PROCESSED_REQUEST__`, CFR/FAR | **Zero** |
| Entity memorization | Ontario, Acme, Mahindra, $5M | **Zero** |
| Summarization | Untested | **1.000** |
| Risk assessment | Untested | **0.800** |

SaulLM-54B's 540B tokens of legal pretraining give it vocabulary,
structure, and domain awareness that our 20K-sample LoRA on Gemma can't
match. The quality gap is large enough to justify the speed penalty
(~35-50 tok/s via vLLM+AWQ vs 95 tok/s Gemma).

**v4 = clean data + LoRA on SaulLM-54B.** Target: 0.92+ drafting.

Speed mitigation: serve via vLLM with AWQ quantization (~35-50 tok/s
on A100, ~50-75 on H100). A 9-clause SaaS draft ≈ 18-25 min on A100,
acceptable for legal work where users expect "it takes a few minutes."

Eval results: `data/raw/eval_Equall_SaulLM-54B-Instruct.json`
Eval script: `scripts/eval_pipeline.py`

---

## Context: what v3 is and where it falls short

- **Base:** Gemma 4 26B MoE (A4B active), LoRA adapter
- **Training:** ~20k multi-task samples across 6 tasks (drafting, risk, extraction,
  Q&A, checklist, summarization)
- **Serving:** merged adapter + base, vLLM on A100 PCIe via ngrok

### Observed failure modes (from real outputs)

| # | Symptom | Example |
|---|---|---|
| 1 | Memorization of entities | `Acme`, `Mahindra`, `$5,000,000`, `Ontario` appearing verbatim in outputs |
| 2 | Training-format leakage | `[/INST]`, `__PROCESSED_REQUEST__`, `__BEGIN_PREMIUM_INSTRUCTIONS__` tokens in output |
| 3 | Contract-type / mode blur | SaaS prompts producing MSA-style output (`Statement of Work`, `milestone payments`, `Deliverables`) |
| 4 | Structural defects | Sub-clause numbering collapses — Article 2 containing sub-clauses `4.1`, `4.2`, `4.3` |
| 5 | RAG contamination | Model copies source filenames + section headers verbatim from precedents (`Source: 4993634217.pdf`, `[Source 10: Draft Contract123.pdf \| ...]`) |
| 6 | Rambling | Model fills full `max_tokens` budget with irrelevant content rather than stopping |

---

## Root-cause findings from research

### #2 (token leakage) — confirmed: v3 had NO loss masking

Checked `~/legal-finetune/scripts/v3_train_gemma4.py`. The SFTTrainer was
instantiated with just `dataset_text_field="text"` and `packing=False` — no
`DataCollatorForCompletionOnlyLM`, no `response_template`. Default SFTTrainer
behaviour: **loss computed on the entire sequence**, including user turns,
system prompt, and chat-template markers.

The model literally trained to reproduce `<start_of_turn>user`, `[/INST]`,
`__PROCESSED_REQUEST__`, and any other tokens that appeared in the raw text.
We thought we had done this; we hadn't. Fixing this is the cheapest, highest-
impact change for v4.

- Reference: [Mask Your User Tokens (Gottesman 2024)](https://yonigottesman.github.io/2024/05/13/mask-user-tokens.html)
- Reference: [HuggingFace chat_templating docs](https://huggingface.co/docs/transformers/chat_templating)

### #1 (memorization) — LoRA is already safe; it's near-duplicates in data

Paper: [Leaner Training, Lower Leakage (arXiv 2506.20856)](https://arxiv.org/html/2506.20856v1).
LoRA plagiarism similarity stays <0.80 across all rank/alpha configs even at
10k training samples. Memorization shows up only when the source text is
**duplicated** in training.

Implication: what caused "Acme / Mahindra / $5M" is likely:
- Multiple contracts in training referenced the same fake names or figures
- Per-document dedup (what most teams do) missed per-clause near-duplicates —
  two leases with different parties but identical indemnity clauses are NOT
  dupes at document level, but ARE dupes for the indemnity-drafting sample

Fix: **per-clause MinHash LSH dedup at Jaccard ≥ 0.8**, using the `text-dedup`
library SaulLM used. Plus templating of memorizable strings (see #3 below).

- Reference: [text-dedup library (ChenghaoMou)](https://github.com/ChenghaoMou/text-dedup)
- Reference: [SaulLM-54B paper (arXiv 2407.19584)](https://arxiv.org/abs/2407.19584)

### Template-fill memorizable entities (covers #1 and jurisdiction leakage)

SaulLM's approach for production models: **don't remove** specific names / figures /
jurisdictions from training data — **template** them.

Before:
```
"This agreement between Acme Corp. and Mahindra is governed by the laws of Ontario..."
```

After:
```
"This agreement between {{PARTY_A}} and {{PARTY_B}} is governed by the laws of
 {{GOVERNING_LAW}}..."
```

And the user-turn is rewritten to tell the model what to fill in. Preserves the
learning signal (the model still sees "a company name goes here") without
creating memorizable target strings. Standard pipeline: NER → faker → template.

- Reference: [Anonymization best practices (PMC12214779)](https://pmc.ncbi.nlm.nih.gov/articles/PMC12214779/)

### #3 (SaaS→MSA mode blur) — "diversity collapse", not task interference

Paper: [Price of Format: Diversity Collapse (arXiv 2505.18949)](https://arxiv.org/html/2505.18949v1).
When structural templates repeat during IFT, the model "internalizes them as
strong generation priors" and collapses novel prompts onto the dominant
template. If MSA samples outnumber SaaS samples (or use more frequent structural
cues like `SOW` / `Deliverables`), the model falls into MSA mode on any prompt
that looks vaguely like a services contract.

Fix: explicit `<contract_type=SAAS>` task tags in training prompts, balanced
contract-type mix, and dedicated SaaS samples that explicitly AVOID MSA language.

- Reference: [Tag-LLM (arXiv 2402.05140)](https://arxiv.org/html/2402.05140v3) —
  learned per-task tag tokens, cheaper than per-task adapters

### #5 (RAG contamination) — unsolved research problem, engineering-fixable

No single paper addresses "model copies source filenames verbatim". Community
consensus on mitigation:

1. **Sanitize retrieval payload BEFORE the prompt** — strip filenames,
   section-numbering prefixes, XML tags, page breaks. Model should see clause
   text only, nothing else.
2. **Include RAG-shaped samples in IFT** — retrieved chunks in the prompt +
   clean outputs + explicit "do not copy names/figures from precedent" instruction.
   **v3 never saw a RAG-shaped prompt during training**, which is the biggest
   single reason it mis-handles them at inference. This is probably our largest
   quality lever for drafting.
3. **Role-demarcate retrieved chunks** with explicit delimiters: `<<<PRECEDENT
   START>>> ... <<<PRECEDENT END>>>` plus instruction "use only for structure/
   tone; party names, figures, and jurisdictions come from the user's brief".

- Reference: [Stanford RegLab hallucination study (Magesh 2025)](https://onlinelibrary.wiley.com/doi/full/10.1111/jels.12413) —
  Westlaw AI 17-33% hallucination rate, Lexis+ ~17%, despite heavy RAG. RAG
  grounding is necessary but not sufficient.

### #4 (numbering / structural defects) — no published mitigation

Not a well-studied failure mode. Our best lever is evaluation + QA:
- Parser-based check: `Article N` sub-clauses must have numbering `N.M` (already
  landed in commit `22bb6fa`)
- Structural samples during training: include samples where the same abstract
  clause appears under different article indexes (2.x, 3.x, 5.x) so the model
  learns the pattern is position-dependent.

### #6 (rambling) — addressed at inference, not training

Fixed at runtime with LENGTH DISCIPLINE guardrails + stop sequences. No
training-side fix is called for.

---

## What the production legal-AI teams reveal

### Harvey AI (most candid)

- **BigLaw Bench evaluation rubric** — four axes: structure, style, substance,
  hallucination penalties. Maps directly to our observed defects. ([Introducing BigLaw Bench](https://www.harvey.ai/blog/introducing-biglaw-bench))
- **Harvey's hallucination rates:** Harvey Assistant 0.2%, Claude 0.7%,
  ChatGPT 1.3%, Gemini 1.9%. Hallucinations cluster in "multiple, long documents"
  — exactly contract drafting with precedents. ([Hallucinations blog](https://www.harvey.ai/blog/biglaw-bench-hallucinations))
- **Harvey's named failure modes** match ours: "over-literalism" (returning
  `consent` + `notice` when only `consent` was asked) and "overconfidence about
  facts outside the four corners" (claiming details from referenced-but-not-
  provided documents). ([Contract Intelligence Benchmark](https://www.harvey.ai/blog/contract-intelligence-benchmark))
- **Harvey abandoned pure fine-tuning.** Per the OpenAI case study, they tried
  FT and RAG alone and found both insufficient — ended up on custom-trained
  models + RAG + self-correction agents. ([openai.com/index/harvey](https://openai.com/index/harvey/))

### Thomson Reuters CoCounsel / Westlaw

Marketing claims 20B+ legal documents, RAG "reduces hallucination to nearly
zero." Stanford RegLab measured **17-33% hallucination** on real queries.
Takeaway: **don't trust vendor hallucination claims**; build your own evals.

### SaulLM (Equall)

Most relevant academic work. Key recipe elements:
- Continued pretraining (540B legal tokens) → IFT → DPO
- ~2% general web data (Wikipedia, StackExchange) mixed back in to prevent
  catastrophic forgetting — applies to our additive v4 training too
- `text-dedup` at similarity threshold 0.5 + KenLM perplexity filter >1500
- Synthetic IFT via Mistral-Instruct teacher model (3-turn dialogues)
- **Does NOT train on contract drafting** — their eval is LegalBench-Instruct
  (classification / reasoning). Their recipe is a good prior but doesn't
  directly validate drafting-primary use cases like ours.

### Gemma 4 26B MoE as legal base

**No peer-reviewed legal paper uses Gemma as a base.** SaulLM chose Mixtral
explicitly for MoE capacity. That doesn't mean Gemma is wrong — the symptoms
we see aren't Gemma-specific, they're data/training-format issues. Don't switch
bases for v4; fix the pipeline instead.

---

## Legal evaluation benchmarks — what exists

None of these catch our specific defects (SaaS→MSA bleed, Ontario-in-California,
sub-clause numbering collapse, RAG filename copying). Useful for sanity-check
and regression detection against v3; not sufficient on their own.

### ACORD — the most relevant benchmark for our work

**[ACORD: An Expert-Annotated Retrieval Dataset for Legal Contract Drafting](https://arxiv.org/abs/2501.06582)** (ACL 2025, CC-BY-4.0, [HuggingFace](https://huggingface.co/datasets/theatticusproject/acord))

126,659 query-clause pairs from ~450 contracts (CUAD + Fortune 500 ToS).
114 queries annotated by 12 attorneys + 10 students on a 1-5 star scale.
Covers: Limitation of Liability (63 queries), Indemnification (24),
IP Ownership (4), Restrictive Covenants (10), Term & Termination (3),
Governing Law (4). NOT covered: Payment, Services, Definitions,
Data Protection (acknowledged as a limitation).

**Why it matters for us:**

1. **Retrieval eval off-the-shelf.** Run our DraftContextRetriever against
   ACORD's 114 queries, measure 4-star and 5-star precision@5 (their
   recommended metric — NDCG masks poor top-quality precision).
2. **Confirms BM25 hybrid.** BM25 alone = 52.5% NDCG@5; dense alone =
   62.1%; BM25 + GPT-4o reranker = **76.9%.** Our V24 BM25 table needs
   wiring — the fusion gives +15% over dense-only.
3. **Quality rubric.** Their 4-star criteria (responsive, concise, clear,
   covers all elements) and 3-star defects (too long, missing concepts,
   non-standard language, too one-sided) map directly to our QA checks.
   Use as the foundation for our BigLaw Bench rubric.
4. **Key finding.** "LLM-generated language creates conflicts between
   clauses and introduces language not typically found in standard
   contracts." Validates our RAG-first architecture (retrieve + revise
   > generate from scratch).
5. **Pointwise >> pairwise reranking.** 76.9% vs 58.0% NDCG@5 for GPT-4o.
   Our reranker should use pointwise scoring.

**Action items:**
- [ ] Download ACORD from HuggingFace (`theatticusproject/acord`)
- [ ] Run our retriever against the 114 queries as a baseline eval
- [ ] Use their 1-5 rubric to score our v3 draft outputs on matching
      clause types (Liability, Indemnification, IP, Termination, Gov Law)
- [ ] Wire BM25 fusion into DraftContextRetriever (confirmed +15% value)

### Academic benchmarks (open, can run locally)

| Benchmark | What it tests | Useful for us? |
|---|---|---|
| **[LegalBench](https://hazyresearch.stanford.edu/legalbench/)** ([arXiv 2308.11462](https://arxiv.org/abs/2308.11462)) | 162 tasks, 40 lawyer contributors — issue-spotting, rule application, interpretation. Mostly classification / extraction / entailment. | Sanity-check only — very little drafting evaluation. |
| **[LexGLUE](https://arxiv.org/abs/2110.00976)** | 7 legal NLU tasks (ECtHR, SCOTUS, contract NLI, CaseHOLD). Classification only. | Narrow — older, superseded by LegalBench. |
| **[LegalBench-RAG](https://arxiv.org/abs/2408.10343)** | 6,858 Q&A pairs grounded in 79 real legal documents — specifically measures retrieval groundedness. | **Yes — directly relevant to our RAG contamination problem.** |
| **[CUAD](https://www.atticusprojectai.org/cuad)** | 13,000 annotations across 510 contracts, 41 clause categories. | **Yes — gold standard for extraction accuracy.** Not drafting. |
| **[ContractEval](https://arxiv.org/abs/2508.03080)** | Clause-level risk identification (Aug 2025). Finds open-source LLMs competitive on extraction but weak on "correctness in high-stakes settings." | Yes — good prior for our risk-assessment task. |
| **[LeMAJ (NLLP 2025)](https://aclanthology.org/2025.nllp-1.23.pdf)** | Methodology paper on LLM-as-judge for legal output. | Reference for our own rubric — not a ready-to-use benchmark. |

### Industry benchmarks (rubric public, dataset private)

| Benchmark | What's published | Useful for us? |
|---|---|---|
| **[Harvey BigLaw Bench](https://www.harvey.ai/blog/introducing-biglaw-bench)** | 4-axis rubric (structure / style / substance / hallucination). Dataset private. | **Yes — best-shaped framework for drafting-quality evaluation.** Reproducible in principle; we'd build our own dataset. |
| **[Stanford RegLab Hallucination Benchmark (Magesh 2025)](https://onlinelibrary.wiley.com/doi/full/10.1111/jels.12413)** | Methodology for grading hallucinations in legal research tools. Dataset private. | Methodology reference for hallucination measurement. |

### What's missing

- **No public benchmark for contract drafting quality.** Harvey's rubric is the
  closest thing.
- **No public benchmark for "does output match the specified contract type"**
  (our SaaS→MSA blur).
- **No public benchmark for "does output match the specified jurisdiction"**
  (our Ontario-in-California problem).
- **No public benchmark for structural fidelity** (sub-clause numbering,
  article ordering).

### Our planned eval stack

Given the gaps, a pragmatic 3-layer evaluation:

1. **LegalBench subset + CUAD + ContractEval** — regression detection on
   extraction / risk tasks. Catches if v4 degrades existing capabilities while
   fixing drafting.
2. **LegalBench-RAG** — specifically tests whether v4 handles retrieved
   precedents without copying verbatim. Directly measures our biggest defect.
3. **Custom BigLaw-Bench-style rubric** — 100 hand-picked drafting prompts,
   Harvey-style 4-axis rubric, LLM judge + deterministic parser checks:
   - Sub-clause numbering monotonic within each Article (regex)
   - No `__TOKEN__` / `[INST]` / `Source: *.pdf` / pipe-metadata in output (regex)
   - Contract-type purity: a SaaS prompt must NOT produce `Statement of Work`,
     `milestone payment`, `Deliverables` (regex)
   - Jurisdiction in output matches jurisdiction in input brief (regex + LLM judge)
   - Party names in output match user-supplied party names (regex)
   - No named entities from training corpus (Acme, Mahindra, NeuroPace, etc.)
     (regex against a known-bad list)

The parser checks are cheap and catch most of our specific defects. The LLM
judge handles style / structure / substance where regex can't.

**Hold out this eval set BEFORE any v4 training.** Score v3 on it first as a
baseline. Target: every v4 iteration must beat v3 on the rubric before shipping.

---

## v4 action items — prioritized by ROI + evidence strength

### Priority 1 — fix the training pipeline itself

- [ ] **Loss-masking audit + fix.** Replace `SFTTrainer(..., dataset_text_field="text")`
  with `DataCollatorForCompletionOnlyLM` keyed on the Gemma response template
  `<start_of_turn>model\n`, or use TRL's newer `completion_only_loss=True`.
  This alone should eliminate most of the `__TOKEN__` and `[/INST]` leakage.
  **Cheapest, highest-impact fix.**
- [ ] **LoRA dropout 0.05-0.1.** One config line. Measurable memorization
  reduction with negligible task-quality cost.

### Priority 2 — clean the training data

- [ ] **Sentinel audit** — grep the v3 training corpus for `[INST]`, `<|`,
  `__[A-Z_]+__`, `BEGIN_`, any custom markers. Scrub all that appear inside
  assistant spans.
- [ ] **Per-clause dedup** via MinHash LSH at Jaccard ≥ 0.8, using
  `text-dedup`. Per-document dedup (if any) is not enough for contracts.
- [ ] **Template memorizable entities** — party names, dollar figures,
  jurisdictions, dates → `{{PARTY_A}}`, `{{AMOUNT}}`, `{{GOVERNING_LAW}}`,
  `{{DATE}}`. Rewrite user-turn to instruct fill-in from the prompt.
- [ ] **Verify EDGAR training data is gone.** (RAG is clean; training set
  was separate. Confirm.)
- [ ] **Remove non-target jurisdiction examples** that don't match the target
  jurisdiction in the user instruction (Ontario in a California sample).

### Priority 2.5 — base model switch

- [ ] **Switch base from Gemma 4 26B to SaulLM-54B-Base** (Mixtral MoE,
  MIT license, 540B legal pretraining tokens). Train LoRA on top of
  `Equall/SaulLM-54-Base` (not -Instruct, since we do our own IFT).
- [ ] **Serving setup:** vLLM + AWQ quantization (~27GB VRAM, fits A100
  80GB). Expected ~35-50 tok/s. Verify Mixtral architecture + AWQ +
  LoRA compatibility in vLLM before committing training compute.
- [ ] **Hyperparameters for SaulLM-54B:** LR 1.5e-4 (lower than Gemma's
  2e-4 — larger model), LoRA rank 16, dropout 0.1, 1-3 epochs on
  cleaned dataset, max_seq_length 8192.

### Priority 3 — structure the training

- [ ] **Explicit task + contract-type tags** in prompts. Every drafting sample
  includes `<task=draft_clause>` `<contract_type=SAAS>` `<clause=LIABILITY>`
  `<jurisdiction=CA>`. Model learns to condition on them.
- [ ] **Balance contract-type mix.** Count samples per contract type in v3;
  if SaaS is under-represented vs MSA, add more SaaS drafting samples
  (both positive — good SaaS clauses — and negative — "don't use SOW/
  milestone language" examples).
- [ ] **Include RAG-shaped samples.** Prompts with retrieved clauses
  demarcated (`<<<PRECEDENT>>> ... <<<END>>>`) + instruction "do not copy
  party names, figures, or jurisdictions from the precedent" + clean output.
  v3 had zero of these. This is probably the single biggest quality lever.

### Priority 4 — maybe split into 2 adapters (speculative)

Your task set is bimodal:
- **Structured-output tasks:** extraction, checklist, risk (short, tabular)
- **Generative tasks:** draft, summary, Q&A (long, prose)

Published evidence for per-task adapter splits in legal is thin, but the
theoretical case is reasonable — different output distributions, different
optimal max_tokens / temperature / stop behaviours. Worth a small experiment
AFTER priorities 1-3 are done.

### Priority 5 — evaluation

Loss curves are near-useless for the defects we care about. Build a
BigLaw-Bench-style rubric with:

- **Deterministic parser checks** (no judge needed):
  - Sub-clause numbering monotonic under each Article
  - No `__TOKEN__` / `[INST]` / `Source: *.pdf` in output
  - Contract-type regex checks: SaaS output contains no `Statement of Work` /
    `milestone payment` / `Deliverables`
  - Jurisdiction in output matches jurisdiction in input brief
- **LLM-as-judge rubric** for the rest (structure, style, substance).

Hold out ~100 contract-drafting prompts as the v4 eval set before training.
Score v3 on it. Target: any measurable improvement from the data+training
changes in priorities 1-3.

---

## Additional findings from LawLLM (arXiv 2407.21065)

LawLLM is a classification-oriented legal LLM (case retrieval, judgment
prediction) — different domain from contract drafting. Three findings
transfer:

1. **Balanced training constraint (25% per class).** They enforce exactly
   25% per verdict category. Our v3 almost certainly has imbalanced contract
   types (MSA/NDA over-represented, SaaS under-represented). This directly
   contributes to MSA-mode-blur: the model defaults to the dominant type.
   - **Action:** audit v3 training data by contract_type tag. If any type
     is < 15% of drafting samples, oversample or undersample to rebalance.

2. **Few-shot reference clause in training — +26% lift.** Prepending 2
   in-context examples to each training sample gave a 26% absolute accuracy
   improvement (63.6% → 79.4%). This maps directly to our Priority 3 item
   "include RAG-shaped samples in v4 IFT." The format:
   ```
   [REFERENCE_CLAUSE: {example indemnity from a similar SaaS contract}]
   [INPUT: Draft an indemnity clause for this SaaS deal...]
   [OUTPUT: {clean indemnity clause using parties/figures from the INPUT}]
   ```
   The model learns "how to use a precedent without copying it verbatim"
   — our single biggest quality lever.

3. **Dropout 0.1 on LoRA.** Confirmed by another legal-domain paper.
   Matches our Priority 1 plan.

**What did NOT transfer:** their classification framing (choose from 10),
knowledge-graph grounding, case-law-specific metrics (top-k, not-found
rate), and "7B outperforms 13B" finding (specific to their base-vs-
fine-tuned comparison, not a general size argument).

Reference: [LawLLM (arXiv 2407.21065)](https://arxiv.org/abs/2407.21065)

---

## Honest gaps / open questions

- Nobody has published training recipes for contract drafting specifically.
  SaulLM doesn't train on drafting; LegalBench barely tests it. We're doing
  genuinely unpublished engineering work.
- Harvey's internals are proprietary. Their public blog posts describe the
  eval framework but not training data, recipe, or specific failure-mode
  post-mortems.
- The additive-LoRA-on-prior-LoRA-at-low-LR approach is common industry
  practice but has limited peer-reviewed validation for this exact case.
  Have a rollback plan: keep v3 adapter as the production pin until v4 beats
  it on the held-out eval.
- Gemma 4 26B MoE + heavy LoRA IFT has no published validation specifically.
  MoE routing can be unstable; [Dynamic Data Mixing for MoE SFT](https://arxiv.org/html/2406.11256)
  suggests MoE-specific SFT recipes matter. We're using generic SFT — may be
  leaving quality on the table. Worth checking once the other issues are
  resolved.

---

## Sources

Primary references cited above:

- [SaulLM-54B/141B (arXiv 2407.19584)](https://arxiv.org/abs/2407.19584)
- [SaulLM-7B (arXiv 2403.03883)](https://arxiv.org/abs/2403.03883)
- [LegalBench (arXiv 2308.11462)](https://arxiv.org/abs/2308.11462)
- [LLMs for Law: Contract Understanding (arXiv 2508.07849)](https://arxiv.org/abs/2508.07849)
- [ContractEval (arXiv 2508.03080)](https://arxiv.org/abs/2508.03080)
- [Mixing It Up — multi-task finance (arXiv 2410.01109)](https://arxiv.org/abs/2410.01109)
- [Leaner Training, Lower Leakage — LoRA memorization (arXiv 2506.20856)](https://arxiv.org/html/2506.20856v1)
- [LoRA Dropout (arXiv 2404.09610)](https://arxiv.org/html/2404.09610v1)
- [Tag-LLM (arXiv 2402.05140)](https://arxiv.org/html/2402.05140v3)
- [Price of Format: Diversity Collapse (arXiv 2505.18949)](https://arxiv.org/html/2505.18949v1)
- [Stanford RegLab Hallucination-Free? (Magesh 2025)](https://onlinelibrary.wiley.com/doi/full/10.1111/jels.12413)
- [MoE-SFT data mixing (arXiv 2406.11256)](https://arxiv.org/html/2406.11256)
- [Mask Your User Tokens (Gottesman 2024)](https://yonigottesman.github.io/2024/05/13/mask-user-tokens.html)
- [HF Chat Templating docs](https://huggingface.co/docs/transformers/chat_templating)
- [text-dedup library](https://github.com/ChenghaoMou/text-dedup)
- [Anonymization in legal AI (PMC12214779)](https://pmc.ncbi.nlm.nih.gov/articles/PMC12214779/)
- [Harvey BigLaw Bench — intro](https://www.harvey.ai/blog/introducing-biglaw-bench)
- [Harvey BigLaw Bench — hallucinations](https://www.harvey.ai/blog/biglaw-bench-hallucinations)
- [Harvey Contract Intelligence Benchmark](https://www.harvey.ai/blog/contract-intelligence-benchmark)
- [Harvey + OpenAI case study](https://openai.com/index/harvey/)
