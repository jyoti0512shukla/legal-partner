# Fine-Tuning Guide: Qwen2.5-7B on Indian Legal Corpus

## Why fine-tune

The current model (AALAP-Mistral-7B-v0.1) is trained on Indian legal data but built on
Mistral v0.1 — a 2023-era base with poor instruction following. It does not reliably output
structured formats (pipe-delimited lines, labelled output), which causes parsing failures
across risk assessment, checklist, comparison, and extraction.

**Goal**: Combine Qwen2.5-7B-Instruct (excellent instruction following) with AALAP's Indian
legal training corpus → Indian legal knowledge + reliable structured output.

---

## Hardware requirements

| GPU | VRAM | Training time | Where to get |
|-----|------|---------------|--------------|
| A100 40GB | Ideal | 8–12 hrs | RunPod, Lambda Labs (~$1.50/hr) |
| RTX 4090 | 24GB | 16–20 hrs | RunPod |
| T4 16GB | Tight | 20–30 hrs | Google Colab Free/Pro |

**Recommended**: Google Colab Pro ($10/mo) for A100 access. Mount Google Drive to save
checkpoints — free tier disconnects after 12 hrs.

---

## Datasets

### 1. Supreme Court / High Court Judgments

#### HuggingFace (zero effort — already clean)

```python
from datasets import load_dataset

# 35k Supreme Court cases — full text + accepted/rejected label
ildc = load_dataset("Exploration-Lab/ILDC_multi", "multi")

# 7.1k SC judgment + human-written summary pairs — great for summarisation
in_abs = load_dataset("law-ai/IN-Abs")

# Indian Statutory Reasoning dataset — NLI pairs over Indian law
sara = load_dataset("Exploration-Lab/SARA")
```

#### Indian Kanoon API (largest source — 30M+ documents)

Apply for API access at `https://indiankanoon.org/api/` (free tier: 1000 queries/day).

```python
import requests, time

def scrape_indiankanoon(query: str, max_docs: int = 500, token: str = "") -> list[dict]:
    docs = []
    headers = {"Authorization": f"Token {token}"}
    for page in range(max_docs // 10):
        resp = requests.post(
            "https://api.indiankanoon.org/search/",
            data={"formInput": query, "pagenum": page},
            headers=headers,
        )
        results = resp.json().get("docs", [])
        if not results:
            break
        for doc in results:
            doc_resp = requests.post(
                f"https://api.indiankanoon.org/doc/{doc['tid']}/",
                headers=headers,
            )
            docs.append({
                "title": doc.get("title"),
                "court": doc.get("docsource"),
                "date": doc.get("publishdate"),
                "text": doc_resp.json().get("doc", ""),
            })
        time.sleep(0.5)
    return docs

# Recommended queries
queries = [
    "breach of contract Indian Contract Act 1872 Supreme Court",
    "arbitration clause Arbitration Conciliation Act 1996",
    "limitation of liability indemnity commercial contract",
    "intellectual property assignment copyright ownership",
    "force majeure frustration of contract",
    "confidentiality non-disclosure agreement",
    "specific performance injunction contract enforcement",
]
```

#### Supreme Court of India website

`https://main.sci.gov.in/judgments` — PDFs back to 1950. No API; needs PDF scraping.

#### CommonLII

`http://www.commonlii.org/in/` — structured, free, India section.

---

### 2. Statutes and Acts

#### India Code (official GoI portal)

`https://www.indiacode.nic.in` — every central act in XML/PDF format.

**Priority acts for contract law training:**

```python
priority_acts = [
    # Core contract law
    "Indian Contract Act 1872",
    "Specific Relief Act 1963",
    "Transfer of Property Act 1882",

    # Dispute resolution
    "Arbitration and Conciliation Act 1996",
    "Limitation Act 1963",

    # Corporate / commercial
    "Companies Act 2013",
    "Partnership Act 1932",
    "Limited Liability Partnership Act 2008",
    "MSMED Act 2006",
    "Insolvency and Bankruptcy Code 2016",

    # Technology / data
    "Information Technology Act 2000",
    "Digital Personal Data Protection Act 2023",

    # New criminal codes (replace IPC/CrPC)
    "Bharatiya Nyaya Sanhita 2023",
    "Bharatiya Nagarik Suraksha Sanhita 2023",

    # Sector-specific
    "FEMA 1999",
    "GST Acts 2017",
    "Copyright Act 1957",
    "Patents Act 1970",
    "Trade Marks Act 1999",
    "Consumer Protection Act 2019",
    "Real Estate (Regulation and Development) Act 2016",
]
```

#### SEBI / RBI Circulars

```
SEBI circulars:  https://www.sebi.gov.in/legal/circulars.html
RBI master directions: https://www.rbi.org.in/scripts/BS_ViewMasDirections.aspx
```

---

### 3. Contracts

Contracts are private documents — these are the best free public sources:

#### SEBI / BSE Filings (best Indian source)

Listed companies must disclose material contracts on BSE/NSE.

```
BSE disclosure portal: https://www.bseindia.com/corporates/ann.html
Filter: "Material Contract", "Agreement", "MOU"

Good companies to pull from (large, well-drafted contracts):
Infosys, TCS, Wipro, HCL Technologies, Reliance, HDFC Bank,
ICICI Bank, Bajaj Finance, L&T, Mahindra, Zomato, Paytm
```

#### SEC EDGAR — Indian companies listed in US

Indian IT companies (Infosys, Wipro, WNS, iGate) file 20-F annual reports with
material contracts as exhibits. Governed by Indian law, in English.

```
https://efts.sec.gov/LATEST/search-index?q=%22Indian+law%22+%22arbitration%22&forms=20-F
```

#### Government / Public Procurement

```
GeM Portal:        https://mkp.gem.gov.in/
CPWD standard forms: https://cpwd.gov.in/Publication/StandardContractForms.pdf
NITI Aayog templates: https://niti.gov.in/
Open contracting (OCDS India — Maharashtra, Karnataka, UP): https://ocds.open-contracting.org/
```

#### HuggingFace Contract Datasets

```python
# CUAD — 500 real commercial contracts, 41 clause types annotated
# US law but excellent for contract structure / clause recognition
cuad = load_dataset("theatticusproject/cuad")

# ContractNLI — NLI over contract clauses
contract_nli = load_dataset("kiddothe2b/contract-nli")

# MAUD — merger agreement understanding
maud = load_dataset("TheAtticusProject/MAUD")
```

---

### Dataset build priority

| Priority | Source | Size | Effort | Impact |
|----------|---------|------|--------|--------|
| 1 | ILDC + IN-Abs (HuggingFace) | 42k examples | Zero | High |
| 2 | SARA (HuggingFace) | ~900 NLI pairs | Zero | High |
| 3 | India Code statutes | ~800 acts | Low — XML download | High |
| 4 | Indian Kanoon API | Unlimited | Medium — API key needed | High |
| 5 | SEBI/BSE filings | 1000+ contracts | Medium — scraping | High |
| 6 | SEC EDGAR (Indian cos) | 200+ contracts | Low — structured | Medium |
| 7 | CUAD + ContractNLI (HuggingFace) | 500+ contracts | Zero | Medium |

Start with priorities 1–3 (zero effort). That is enough for a first fine-tuning run.

---

## Task-specific training examples

These are more important than the general corpus for making the app's structured
output reliable. Add 50–100 examples per task, repeated 10x in the training mix
so the model learns the exact output format.

Tasks to cover:
- Risk assessment (8 pipe-delimited lines: `LABEL: RATING | justification | ref`)
- Clause checklist (12 lines: `CLAUSE_ID: STATUS | RISK | ref | finding | rec`)
- Document comparison (7 lines: `DIMENSION | doc1 summary | doc2 summary | FAVORABLE | reasoning`)
- Key terms extraction (9 labelled lines: `FIELD: value`)
- Contract drafting (numbered sub-clauses in HTML)

See the Colab notebook (`docs/COLAB_FINETUNING_NOTEBOOK.md`) for full examples.

---

## Fine-tuning setup

### Install

```bash
pip install "unsloth[colab-new] @ git+https://github.com/unslothai/unsloth.git"
pip install --no-deps trl peft accelerate bitsandbytes
pip install datasets huggingface_hub
```

### Load model with LoRA

```python
from unsloth import FastLanguageModel
import torch

IS_T4 = torch.cuda.get_device_properties(0).total_memory < 20e9

model, tokenizer = FastLanguageModel.from_pretrained(
    model_name    = "Qwen/Qwen2.5-7B-Instruct",
    max_seq_length = 2048 if IS_T4 else 4096,
    dtype          = None,
    load_in_4bit   = True,
)

model = FastLanguageModel.get_peft_model(
    model,
    r              = 32 if IS_T4 else 64,
    target_modules = ["q_proj", "k_proj", "v_proj", "o_proj",
                      "gate_proj", "up_proj", "down_proj"],
    lora_alpha     = 64 if IS_T4 else 128,
    lora_dropout   = 0.05,
    bias           = "none",
    use_gradient_checkpointing = "unsloth",
    random_state   = 42,
)
model.print_trainable_parameters()
# Expected: ~40M trainable out of 7.6B total (~0.5%)
```

### Format dataset

```python
from unsloth.chat_templates import get_chat_template

tokenizer = get_chat_template(tokenizer, chat_template="qwen-2.5")

def format_chat(examples):
    return {
        "text": [
            tokenizer.apply_chat_template(c, tokenize=False, add_generation_prompt=False)
            for c in examples["conversations"]
        ]
    }

train_data = final_dataset.map(format_chat, batched=True)
```

### Training config

```python
from trl import SFTTrainer
from transformers import TrainingArguments

trainer = SFTTrainer(
    model              = model,
    tokenizer          = tokenizer,
    train_dataset      = train_data,
    dataset_text_field = "text",
    max_seq_length     = 2048 if IS_T4 else 4096,
    args = TrainingArguments(
        per_device_train_batch_size  = 1 if IS_T4 else 2,
        gradient_accumulation_steps  = 16 if IS_T4 else 8,
        num_train_epochs             = 3,
        learning_rate                = 2e-4,
        fp16                         = not torch.cuda.is_bf16_supported(),
        bf16                         = torch.cuda.is_bf16_supported(),
        logging_steps                = 25,
        save_steps                   = 200,
        save_total_limit             = 2,
        optim                        = "adamw_8bit",
        lr_scheduler_type            = "cosine",
        warmup_ratio                 = 0.05,
        output_dir                   = "/content/drive/MyDrive/qwen-legal-checkpoints",
    ),
)
trainer.train()
```

**Expected loss progression:**
- Start: ~2.0–2.5
- After 10% steps: ~1.2–1.5
- Final: ~0.7–1.0

Loss stuck above 1.5 → data format is wrong.
Loss below 0.5 → overfitting, reduce epochs.

---

## Saving and deployment

### Save adapter to HuggingFace (~150MB)

```python
from huggingface_hub import login
login(token="your_hf_token")

model.push_to_hub("your-org/qwen-legal-india-adapter", private=True)
tokenizer.push_to_hub("your-org/qwen-legal-india-adapter", private=True)
```

### Export GGUF for CPU inference on GCP VM

```python
# q4_k_m = best quality/size tradeoff (~4.5GB, runs on CPU)
model.push_to_hub_gguf(
    "your-org/qwen-legal-india-gguf",
    tokenizer,
    quantization_method = "q4_k_m",
    private = True,
)
```

### Deploy on GCP VM

```bash
# Option A: vLLM (if GPU available)
docker run --gpus all vllm/vllm-openai:latest \
  --model your-org/qwen-legal-india-adapter \
  --max-model-len 8192

# Option B: llama.cpp server (CPU-only VM)
huggingface-cli download your-org/qwen-legal-india-gguf \
  --local-dir ./model

./llama-server \
  -m ./model/qwen-legal-india-q4_k_m.gguf \
  --ctx-size 8192 \
  --port 8000 \
  -np 2
```

Backend config in `application.properties` stays identical — same OpenAI-compatible endpoint.

---

## Cost estimate

| Phase | Time | Cost |
|-------|------|------|
| Dataset prep + formatting | 2–3 days | Free |
| Fine-tuning run (A100 x1) | 8–12 hrs | ~$15–20 on RunPod |
| Evaluation + iteration (2–3 runs) | 3–5 days | ~$30–50 |
| Deployment | 1 day | Free |
| **Total** | **~1.5 weeks** | **~$50–70** |

---

## Quick validation before full run

Before committing to the full dataset, validate the approach in 10 minutes:

1. Take only the task-specific examples (50–100 per task)
2. Fine-tune for 3 epochs — takes ~10 minutes on A100
3. Test risk/checklist/compare output format
4. If format compliance improves → full run is worth it

---

## References

- Unsloth docs: `https://github.com/unslothai/unsloth`
- ILDC paper: `https://arxiv.org/abs/2105.04546`
- AALAP model: `https://huggingface.co/Exploration-Lab/AALAP-Mistral-7B-v0.1`
- India Code: `https://www.indiacode.nic.in`
- Indian Kanoon API: `https://indiankanoon.org/api/`
- eCourts open data: `https://devdataportal.ecourts.gov.in`
