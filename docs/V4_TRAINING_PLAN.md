# v4 Training Plan — Research Findings + Action Items

Working doc capturing what we know, what the literature says, and what we'll do
differently when we train v4. Not final — we use this to iterate.

Written 2026-04-16 after a deep research pass on legal-LLM training (SaulLM,
LegalBench, Harvey, production post-mortems, LoRA memorization papers). See
"Sources" at the bottom for the primary references.

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
