# Fine-Tuning Guide: Gemma 4 26B-A4B on Legal Corpus

## Why Gemma 4 26B-A4B

saul-legal-v3 (Saul-7B fine-tune) drafts decent legal prose but hallucinates during analysis —
it reports "missing clauses" that are clearly present in the document. The 7B model memorizes
output patterns but cannot comprehend documents well enough for reliable risk assessment,
checklist, or extraction tasks.

**Gemma 4 26B-A4B solves this:**
- 26B total parameters (MoE) but only **4B active per inference** — fast like a small model, smart like a large one
- 82.6% MMLU Pro vs ~50% for Saul-7B — dramatically better comprehension
- **256K context window** — can ingest entire contracts without chunking
- Native function calling and agentic support
- 140 languages including Hindi and Indian languages
- Apache 2 license — fully commercial, on-prem deployment
- Fits on A100 40GB (training) and L4 24GB (customer inference)

**Goal**: Fine-tune `google/gemma-4-26B-A4B-it` with our existing 1,827 task-specific examples
to get reliable structured output (JSON for analysis, prose for drafting) with strong document
comprehension.

---

## Hardware requirements

| Setup | GPU | VRAM | Training time | Cost |
|-------|-----|------|---------------|------|
| **Recommended** | A100 40GB | 40GB | 10–16 hrs | ~$20–25 on RunPod |
| Alternative | A100 80GB | 80GB | 8–12 hrs | ~$25–35 on RunPod |
| Budget | L4 24GB | 24GB | 20–30 hrs | ~$15–20 on RunPod |

**Colab Pro** ($10/mo) provides A100 40GB. Mount Google Drive for checkpoint persistence.

T4 (16GB) will NOT work — the 4-bit quantized model alone is ~15GB.

---

## Model details

| Property | Value |
|----------|-------|
| HuggingFace ID | `google/gemma-4-26B-A4B-it` |
| Architecture | Mixture of Experts (MoE) |
| Total parameters | 26B |
| Active parameters | 4B per forward pass |
| Context window | 256K tokens |
| Attention | Alternating local sliding-window (1024) + global full-context |
| Position encoding | Dual RoPE (standard + proportional) |
| KV cache | Shared across last N layers (reduces memory) |
| Vocab size | 256K (Gemma tokenizer) |
| License | Apache 2.0 |

---

## Training data

### Existing dataset: 1,827 examples across 5 tasks

| Task | Output format | Examples | Weight |
|------|--------------|----------|--------|
| Risk assessment | JSON (structured risk categories) | ~365 | 1x |
| Clause checklist | JSON (clause status + findings) | ~365 | 1x |
| Document comparison | JSON (dimensional analysis) | ~365 | 1x |
| Key terms extraction | JSON (labelled fields) | ~365 | 1x |
| Contract drafting | Plain English prose (numbered sub-clauses) | ~365 | 1x |

### Training data format

Use the existing 1,827 examples as-is. Gemma 4 26B is strong enough at instruction following
to distinguish task types from the system prompt alone — no task tags needed. This means adding
new use cases in the future requires only a new system prompt, zero retraining.

**Example training conversation (risk assessment):**

```json
{
  "conversations": [
    {
      "role": "system",
      "content": "You are a senior legal analyst. Analyze the contract and identify risks across all categories. Output structured risk categories."
    },
    {
      "role": "user",
      "content": "Analyze this vendor agreement for risks:\n\n[contract text]"
    },
    {
      "role": "assistant",
      "content": "LIMITATION_OF_LIABILITY: HIGH | No liability cap specified... | Article 6\nTERMINATION: MEDIUM | ..."
    }
  ]
}
```

**Example training conversation (drafting):**

```json
{
  "conversations": [
    {
      "role": "system",
      "content": "You are a senior legal draftsman. Draft contract clauses as plain English legal prose. Output ONLY numbered sub-clauses."
    },
    {
      "role": "user",
      "content": "Draft a liability clause for a vendor agreement governed by Indian law..."
    },
    {
      "role": "assistant",
      "content": "1. The aggregate liability of either Party under this Agreement shall not exceed the total fees paid in the twelve (12) months immediately preceding the claim...\n\n2. Neither Party shall be liable for indirect, consequential, special, or punitive damages..."
    }
  ]
}
```

### Dataset sources (for expanding beyond 1,827 examples)

| Priority | Source | Size | Effort |
|----------|--------|------|--------|
| 1 | ILDC + IN-Abs (HuggingFace) | 42k examples | Zero |
| 2 | SARA (HuggingFace) | ~900 NLI pairs | Zero |
| 3 | India Code statutes | ~800 acts | Low |
| 4 | Indian Kanoon API | Unlimited | Medium |
| 5 | SEBI/BSE filings | 1000+ contracts | Medium |
| 6 | SEC EDGAR (Indian cos) | 200+ contracts | Low |
| 7 | CUAD + ContractNLI (HuggingFace) | 500+ contracts | Zero |

---

## Fine-tuning setup

### Cell 1 — Check GPU and install

```python
!nvidia-smi
# Verify A100 40GB

!pip install "unsloth[colab-new] @ git+https://github.com/unslothai/unsloth.git"
!pip install --no-deps trl peft accelerate bitsandbytes
!pip install datasets huggingface_hub
```

### Cell 2 — HuggingFace login

```python
from huggingface_hub import login
login(token="YOUR_HF_TOKEN")  # huggingface.co/settings/tokens
```

### Cell 3 — Load model with LoRA

```python
from unsloth import FastLanguageModel
import torch

model, tokenizer = FastLanguageModel.from_pretrained(
    model_name     = "google/gemma-4-26B-A4B-it",
    max_seq_length = 4096,
    dtype          = None,      # auto-detect bf16
    load_in_4bit   = True,      # ~15GB VRAM for weights
)

model = FastLanguageModel.get_peft_model(
    model,
    r              = 64,        # LoRA rank — 64 is good for 26B
    target_modules = ["q_proj", "k_proj", "v_proj", "o_proj",
                      "gate_proj", "up_proj", "down_proj"],
    lora_alpha     = 128,
    lora_dropout   = 0.05,
    bias           = "none",
    use_gradient_checkpointing = "unsloth",  # critical for A100 40GB
    random_state   = 42,
)
model.print_trainable_parameters()
# Expected: ~80-120M trainable out of 26B total (<0.5%)
```

### Cell 4 — Load and format dataset

```python
from datasets import load_dataset
from unsloth.chat_templates import get_chat_template

tokenizer = get_chat_template(tokenizer, chat_template="gemma")

# Load your training data — adjust path/format to match your dataset
# Option A: from HuggingFace hub
dataset = load_dataset("jyoti0512shuklaorg/legal-training-data", split="train")

# Option B: from local JSON file (upload to Colab or mount Drive)
# dataset = load_dataset("json", data_files="/content/drive/MyDrive/legal-training-data.json", split="train")

def format_chat(examples):
    return {
        "text": [
            tokenizer.apply_chat_template(
                c, tokenize=False, add_generation_prompt=False
            )
            for c in examples["conversations"]
        ]
    }

train_data = dataset.map(format_chat, batched=True)
print(f"Training examples: {len(train_data)}")
print(f"Sample:\n{train_data[0]['text'][:500]}")
```

### Cell 5 — Training config

```python
from trl import SFTTrainer
from transformers import TrainingArguments

trainer = SFTTrainer(
    model              = model,
    tokenizer          = tokenizer,
    train_dataset      = train_data,
    dataset_text_field = "text",
    max_seq_length     = 4096,
    args = TrainingArguments(
        per_device_train_batch_size  = 1,       # A100 40GB with 4-bit: batch=1 is safe
        gradient_accumulation_steps  = 16,       # effective batch = 16
        num_train_epochs             = 3,
        learning_rate                = 1e-4,     # lower than 7B — larger models need gentler LR
        fp16                         = not torch.cuda.is_bf16_supported(),
        bf16                         = torch.cuda.is_bf16_supported(),
        logging_steps                = 25,
        save_steps                   = 200,
        save_total_limit             = 2,
        optim                        = "adamw_8bit",
        lr_scheduler_type            = "cosine",
        warmup_ratio                 = 0.05,
        weight_decay                 = 0.01,
        output_dir                   = "/content/drive/MyDrive/gemma4-legal-checkpoints",
    ),
)

trainer.train()
```

**Expected loss progression:**
- Start: ~1.5–2.0
- After 10% steps: ~0.8–1.2
- Final: ~0.5–0.8

Loss stuck above 1.5 → data format is wrong (check chat template).
Loss below 0.3 → overfitting, reduce epochs to 2.

### Cell 6 — Quick validation

```python
# Test each task type before saving
FastLanguageModel.for_inference(model)

test_prompts = {
    "draft": "<task>draft</task>\nDraft a termination clause for a vendor agreement governed by Indian law.",
    "risk": "<task>risk_assessment</task>\nAnalyze this clause for risks: 'The vendor shall not be liable for any damages whatsoever.'",
    "extraction": "<task>extraction</task>\nExtract key terms from: 'This Agreement is between Acme Pvt Ltd and Beta Corp, effective 1 Jan 2026, governed by Indian law.'",
}

for task, prompt in test_prompts.items():
    inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
    outputs = model.generate(**inputs, max_new_tokens=512, temperature=0.3)
    result = tokenizer.decode(outputs[0][inputs.input_ids.shape[1]:], skip_special_tokens=True)
    print(f"\n{'='*60}")
    print(f"TASK: {task}")
    print(f"{'='*60}")
    print(result[:500])
```

**What to check:**
- `draft` → outputs plain prose, NO JSON
- `risk` → outputs structured risk categories
- `extraction` → outputs labelled key terms

---

## Saving and deployment

### Save adapter to HuggingFace

```python
from huggingface_hub import login
login(token="YOUR_HF_TOKEN")

# Save LoRA adapter (~200-300MB)
model.push_to_hub("jyoti0512shuklaorg/gemma4-legal-v1", private=True)
tokenizer.push_to_hub("jyoti0512shuklaorg/gemma4-legal-v1", private=True)
```

### Save merged model (for direct serving without LoRA)

```python
# Merge adapter into base model and save
model.save_pretrained_merged(
    "/content/drive/MyDrive/gemma4-legal-v1-merged",
    tokenizer,
    save_method="merged_16bit",
)

# Push merged model to HF
model.push_to_hub_merged(
    "jyoti0512shuklaorg/gemma4-legal-v1-merged",
    tokenizer,
    save_method="merged_16bit",
    private=True,
)
```

### Export GGUF for llama.cpp (customer L4 VMs)

```python
# Q4_K_M = best quality/size tradeoff (~15GB, fits L4 24GB)
model.push_to_hub_gguf(
    "jyoti0512shuklaorg/gemma4-legal-v1-gguf",
    tokenizer,
    quantization_method = "q4_k_m",
    private = True,
)
```

---

## Serving the fine-tuned model

### Option A: vLLM on A100 (Colab / dev testing)

```bash
vllm serve jyoti0512shuklaorg/gemma4-legal-v1-merged \
  --port 8000 --host 0.0.0.0 \
  --max-model-len 8192 \
  --gpu-memory-utilization 0.90 \
  --max-num-seqs 5
```

### Option B: llama.cpp on customer L4 VMs (production)

```bash
# Download GGUF
huggingface-cli download jyoti0512shuklaorg/gemma4-legal-v1-gguf \
  --local-dir ./model

# Serve with 5 concurrent slots
llama-server \
  -m ./model/gemma4-legal-v1-q4_k_m.gguf \
  --ctx-size 8192 \
  --port 8000 \
  -np 5
```

### Option C: Ollama on customer VMs

```bash
# Create Modelfile
cat > Modelfile <<EOF
FROM ./model/gemma4-legal-v1-q4_k_m.gguf
PARAMETER num_ctx 8192
PARAMETER temperature 0.3
EOF

ollama create gemma4-legal -f Modelfile
ollama serve
```

### Backend .env config (same for all options)

```bash
LEGALPARTNER_CHAT_PROVIDER=vllm
LEGALPARTNER_CHAT_API_URL=http://localhost:8000/v1
LEGALPARTNER_CHAT_API_MODEL=jyoti0512shuklaorg/gemma4-legal-v1-merged
```

---

## VRAM budget: A100 40GB with 5 concurrent users

| Component | VRAM |
|-----------|------|
| Model weights (4-bit) | ~15GB |
| KV cache (5 users x 8K context) | ~7.5GB |
| CUDA overhead | ~2GB |
| **Total** | **~24.5GB** |
| **Headroom** | **~15.5GB** |

Comfortable fit. Can increase `max-model-len` to 16K or add more concurrent users.

### L4 24GB (customer VMs) with GGUF Q4_K_M

| Component | VRAM |
|-----------|------|
| Model weights (GGUF Q4_K_M) | ~14GB |
| KV cache (5 users x 4K context) | ~3.5GB |
| Overhead | ~2GB |
| **Total** | **~19.5GB** |
| **Headroom** | **~4.5GB** |

Works with `--ctx-size 4096`. For 8K context, headroom is tighter (~1GB).

---

## Cost estimate

| Phase | Time | Cost |
|-------|------|------|
| Add task tags to existing 1,827 examples | 1 day | Free |
| Fine-tuning run (A100 40GB x1) | 10–16 hrs | ~$20–25 |
| Validation + iteration (2–3 runs) | 3–5 days | ~$40–60 |
| Merge + export GGUF | 1 hr | Free |
| Deploy + test | 1 day | Free |
| **Total** | **~1.5 weeks** | **~$60–85** |

---

## Quick validation before full run

Before committing to 3 epochs on all 1,827 examples:

1. Take 50 examples per task (250 total)
2. Fine-tune for 1 epoch — ~30 min on A100
3. Test each task type with the validation prompts above
4. Check: drafts are pure prose? Risk assessment reads the actual document? Extraction is accurate?
5. If yes → full training run is worth it

---

## Comparison: before and after

| Metric | saul-legal-v3 (7B) | gemma4-legal-v1 (26B MoE) |
|--------|-------------------|--------------------------|
| Drafting quality | Good prose, JSON artifacts | Clean prose (26B comprehension + distinct system prompts) |
| Risk assessment | Hallucinates missing clauses | Actually reads and comprehends the document |
| Extraction accuracy | Decent | Strong (256K context, no chunking needed) |
| Inference speed | ~15 tok/s | ~20 tok/s (only 4B active) |
| Customer hardware | T4/L4 | L4 (GGUF Q4_K_M) |
| Context window | 4K | 8K–256K |
| Languages | English only | 140 languages (Hindi, Indian languages) |

---

## References

- Gemma 4 blog: https://huggingface.co/blog/gemma4
- Model card: https://huggingface.co/google/gemma-4-26B-A4B-it
- GGUF: https://huggingface.co/ggml-org/gemma-4-26b-a4b-it-GGUF
- Unsloth: https://github.com/unslothai/unsloth
- Previous guide (Qwen/Saul): docs/FINETUNING_GUIDE.md
