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
