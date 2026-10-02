# Evaluation

Two harnesses:

| Harness | What it measures |
|---|---|
| `eval/drafting/run_drafting_eval.py` | **Drafting quality + round trip** — drafts every brief in `eval/drafting/briefs.yml` through the real API, runs deterministic checks, then reviews each draft with our own review. Optional LLM judge. CI-gatable via `--baseline`. |
| `eval/run_eval.py` | Extraction and risk accuracy on uploaded contracts with expected results (below). |

## Drafting eval (round trip)

```bash
pip install pyyaml requests
python3 eval/drafting/run_drafting_eval.py --self-test                 # no API needed
LP_EVAL_PASSWORD=... python3 eval/drafting/run_drafting_eval.py \
    --api-url http://localhost:8080 --run-id baseline                  # record a baseline
LP_EVAL_PASSWORD=... python3 eval/drafting/run_drafting_eval.py \
    --baseline eval/results/drafting/baseline/summary.json             # exit 1 on regression
```

Key metrics (`summary.json` → `aggregate`): `term_coverage`, `placeholders_per_draft`,
`invented_amounts_per_draft`, `numbering_ok_rate`, `xref_ok_rate`, and the round trip —
`mean_risk_score`, `overall_risk_distribution`, `failing_review_questions_per_draft`,
`top_failing_questions` (what to fix next). Add `--judge-url/--judge-model/--judge-key`
for a per-article LLM judge (nine dimensions, max-severity rule).

Model bake-off without a GPU: point the backend at a hosted OpenAI-compatible provider
(`LEGALPARTNER_CHAT_API_URL`, `LEGALPARTNER_CHAT_API_MODEL`, `LEGALPARTNER_CHAT_API_KEY`),
run the eval per model with a different `--run-id`, compare `summary.json` files.
Use the fictitious briefs only.

Golden clauses vs their own review questions (LLM, opt-in):

```bash
LP_EVAL_LLM_URL=https://openrouter.ai/api/v1 LP_EVAL_LLM_MODEL=qwen/qwen3.8-27b LP_EVAL_LLM_KEY=... \
  ./backend/gradlew -p backend test --tests '*GoldenClauseReviewLlmTest'   # → build/reports/golden-review.md
```

---

# ContractIQ Evaluation Harness

## Purpose

Automated quality testing of the AI pipeline against known contracts with expected results.
Run after any change to risk assessment, extraction, or RAG pipeline to catch regressions.

## How to use

### 1. Download CUAD contracts

```bash
# Download CUAD dataset (510 annotated contracts)
# https://www.atticusprojectai.org/cuad
# Or use the subset below from our test data
```

### 2. Add contracts to eval/contracts/

Place PDF/DOCX/HTML contracts in `eval/contracts/` with descriptive names:
```
eval/contracts/
  nda-mutual-tech.pdf
  msa-consulting.docx
  saas-subscription.pdf
  employment-california.pdf
  software-license-enterprise.pdf
```

### 3. Create expected results in eval/expected/

For each contract, create a YAML file with expected extraction + risk results:
```yaml
# eval/expected/nda-mutual-tech.yml
contract_type: NDA
expected_extractions:
  party_a: "TechCorp Inc."
  party_b: "InnovateLab LLC"
  effective_date: "2024-01-15"
  confidentiality_term: "3 years"
  governing_law: "California"
expected_clauses_present:
  - CONFIDENTIALITY
  - TERMINATION
  - GOVERNING_LAW
expected_clauses_missing:
  - PAYMENT
  - SLA
  - WARRANTIES
expected_risk_ratings:
  CONFIDENTIALITY: LOW    # should be well-drafted
  TERMINATION: LOW
  GOVERNING_LAW: LOW
```

### 4. Run evaluation

```bash
# Upload contracts + run pipeline + compare vs expected
python3 eval/run_eval.py --api-url https://legal.cognita-ai.com --token <JWT>

# Or run specific contract
python3 eval/run_eval.py --contract nda-mutual-tech.pdf
```

### 5. Review results

```
eval/results/
  nda-mutual-tech_results.json     # full AI output
  nda-mutual-tech_comparison.json  # expected vs actual diff
  summary.json                     # aggregate accuracy scores
```

## Metrics

| Metric | What it measures |
|--------|-----------------|
| Extraction recall | % of expected fields that were correctly extracted |
| Extraction precision | % of extracted fields that match expected values |
| Clause detection accuracy | % of clauses correctly identified as present/missing |
| Risk rating accuracy | % of risk ratings that match expected (within 1 level) |
| False negative rate | % of present clauses rated as missing |
| False positive rate | % of missing clauses rated as present |

## Baseline

Run once to establish baseline scores. All future changes must maintain or improve these scores.
