# SaulLM Colab Setup

## Models

| Model | Description | Use |
|-------|-------------|-----|
| `jyoti0512shuklaorg/saul-legal-v3` | **Fine-tuned (recommended)** — Saul-7B + QLoRA trained on 1,827 examples across 5 tasks: drafting, risk assessment, extraction, checklist, redline. Outputs clean JSON for structured tasks. | Production |
| `Equall/Saul-Instruct-v1` | Base Saul-7B — general legal knowledge, no task-specific training. Needs guided_json for structured output. | Fallback / comparison |

**GPU required:** A100 40GB (Colab Pro) or T4 16GB (free tier with `--max-model-len 4096`).

---

## Step-by-Step Colab Cells

Copy-paste each cell into a new Colab notebook in order.

---

### Cell 1 — Check GPU

```python
!nvidia-smi
```

You need A100 (40GB) or T4 (16GB). If T4, you'll use `--max-model-len 4096` later.

---

### Cell 2 — Install dependencies

```python
!pip install vllm pyngrok outlines -q
```

**After this cell finishes → Runtime → Restart runtime (mandatory).**

---

### Cell 3 — Verify installs (run after restart)

```python
import vllm, outlines
print("vllm:", vllm.__version__)
print("outlines: OK")
```

---

### Cell 4 — HuggingFace token

```python
import os
os.environ["HUGGING_FACE_HUB_TOKEN"] = "hf_YOUR_TOKEN_HERE"  # Required — v3 model is private
```

Get your token from https://huggingface.co/settings/tokens

---

### Cell 5 — Start ngrok tunnel

```python
from pyngrok import ngrok, conf

conf.get_default().auth_token = "YOUR_NGROK_TOKEN_HERE"
tunnel = ngrok.connect(8000, "http")
VLLM_URL = tunnel.public_url
print("Public URL:", VLLM_URL)
```

**Copy the printed URL** — you need it for the backend `.env` file.

---

### Cell 6 — Write Mistral chat template (CRITICAL)

Saul is based on Mistral and needs this chat template. Without it you get garbage output or "roles must alternate" errors.

```python
%%writefile /tmp/mistral.jinja
{{ bos_token }}{% for message in messages %}{% if message['role'] == 'user' %}[INST] {{ message['content'] }} [/INST]{% elif message['role'] == 'assistant' %}{{ message['content'] }}{{ eos_token }}{% endif %}{% endfor %}
```

---

### Cell 7 — Start vLLM server

#### Option A: Fine-tuned v3 model (recommended)

**For A100 (40GB):**

```python
!python -m vllm.entrypoints.openai.api_server \
    --model jyoti0512shuklaorg/saul-legal-v3 \
    --host 0.0.0.0 \
    --port 8000 \
    --max-model-len 8192 \
    --dtype half \
    --gpu-memory-utilization 0.95 \
    --trust-remote-code \
    --chat-template /tmp/mistral.jinja
```

**For T4 (16GB):**

```python
!python -m vllm.entrypoints.openai.api_server \
    --model jyoti0512shuklaorg/saul-legal-v3 \
    --host 0.0.0.0 \
    --port 8000 \
    --max-model-len 4096 \
    --dtype half \
    --gpu-memory-utilization 0.95 \
    --trust-remote-code \
    --chat-template /tmp/mistral.jinja
```

#### Option B: Base Saul model (for comparison)

```python
!python -m vllm.entrypoints.openai.api_server \
    --model Equall/Saul-Instruct-v1 \
    --host 0.0.0.0 \
    --port 8000 \
    --max-model-len 8192 \
    --dtype half \
    --gpu-memory-utilization 0.95 \
    --trust-remote-code \
    --chat-template /tmp/mistral.jinja
```

Wait until you see:
```
INFO:     Uvicorn running on http://0.0.0.0:8000
```

First run downloads ~14GB model weights (~5 min).

---

### Cell 8 — Smoke test

```python
import requests

MODEL = "jyoti0512shuklaorg/saul-legal-v3"  # match Cell 7

resp = requests.post(
    VLLM_URL + "/v1/chat/completions",
    headers={"Authorization": "Bearer no-op"},
    json={
        "model": MODEL,
        "messages": [
            {"role": "user", "content": "Draft a Termination clause for a Master Services Agreement."}
        ],
        "max_tokens": 512
    }
)
print(resp.json()["choices"][0]["message"]["content"])
```

---

### Cell 9 — Test all 5 tasks (v3 model)

The fine-tuned model outputs clean JSON without needing `guided_json`.

```python
import json

MODEL = "jyoti0512shuklaorg/saul-legal-v3"

tests = [
    ("Drafting", "Draft a Confidentiality clause for a Non-Disclosure Agreement."),
    ("Risk", "Assess the risk level of this LIABILITY clause:\n\nThe Provider shall not be liable for any damages."),
    ("Extraction", "Extract key terms from this contract:\n\nThis Agreement is between Acme Corp and Beta Inc, effective March 1, 2024, governed by California law, valued at $250,000."),
    ("Checklist", "Check this contract for 12 standard clauses:\n\nThis MSA between PartyA and PartyB includes payment terms, confidentiality, and termination for convenience."),
    ("Redline", "This PAYMENT clause needs improvement. Suggest better language:\n\nPayment shall be made when convenient for the Client."),
]

for task, prompt in tests:
    resp = requests.post(
        VLLM_URL + "/v1/chat/completions",
        headers={"Authorization": "Bearer no-op"},
        json={"model": MODEL, "messages": [{"role": "user", "content": prompt}], "max_tokens": 1024}
    )
    output = resp.json()["choices"][0]["message"]["content"]

    # Check if structured tasks produce valid JSON
    is_json_task = task in ("Risk", "Extraction", "Checklist", "Redline")
    if is_json_task:
        try:
            json.loads(output)
            status = "PASS (valid JSON)"
        except:
            status = "FAIL (invalid JSON)"
    else:
        status = "PASS" if len(output.split()) > 50 else "FAIL (too short)"

    print(f"[{status}] {task}")
    print(f"  {output[:150]}...")
    print()
```

---

### Cell 10 — Keep-alive (optional)

Prevents Colab from timing out the session.

```python
import time

while True:
    time.sleep(30)
    try:
        requests.get("http://localhost:8000/health", timeout=5)
    except:
        print("vLLM down")
```

---

## Backend Configuration

Set these in your `.env` file on the GCP VM:

```env
LEGALPARTNER_CHAT_API_URL=https://YOUR-NGROK-URL-HERE/v1
LEGALPARTNER_CHAT_API_MODEL=jyoti0512shuklaorg/saul-legal-v3
```

**The model name must exactly match** what you passed to `--model` in Cell 7.

Then restart:
```bash
docker compose down && docker compose up -d
```

---

## Troubleshooting

| Error | Fix |
|-------|-----|
| `The model ... does not exist` | Model name in `.env` doesn't match `--model` in Cell 7. They must be identical. For v3 model, ensure HF token is set (Cell 4) since the repo is private. |
| `Conversation roles must alternate` | Cell 6 (chat template) didn't run, or `--chat-template /tmp/mistral.jinja` missing from Cell 7. |
| `LLM endpoint returned an error page (HTTP 404)` | ngrok tunnel died. Re-run Cell 5, update `.env` with new URL, restart backend. |
| `guided_json returned no categories` | Outlines not installed. Re-run Cell 2, restart runtime, verify Cell 3. |
| `CUDA out of memory` | Use `--max-model-len 4096` instead of `8192` (T4 GPU). |

---

## Model Comparison

| Model | Drafting | Risk (JSON) | Extraction (JSON) | Checklist (JSON) | Redline (JSON) |
|-------|----------|-------------|-------------------|------------------|----------------|
| **saul-legal-v3** | 8/8 pass | Clean JSON, no guided_json needed | Clean JSON | Clean JSON | Clean JSON |
| Saul-Instruct-v1 (base) | OK | Needs guided_json, unreliable | Needs guided_json | Needs guided_json | Needs guided_json |
| AALAP-Mistral-7B | OK | JSON output broken | JSON output broken | JSON output broken | JSON output broken |

**Recommendation:** Use `jyoti0512shuklaorg/saul-legal-v3` for production. The base Saul model requires `guided_json` for every structured task and still fails often. AALAP cannot produce reliable JSON.

---

## Switching to AALAP (Indian Legal — not recommended)

AALAP has Indian legal knowledge but cannot reliably output structured JSON. Only use for comparison/testing.

**Cell 7:**
```python
!python -m vllm.entrypoints.openai.api_server \
    --model opennyaiorg/Aalap-Mistral-7B-v0.1-bf16 \
    --host 0.0.0.0 \
    --port 8000 \
    --max-model-len 8192 \
    --dtype half \
    --gpu-memory-utilization 0.95 \
    --chat-template /tmp/mistral.jinja
```

**`.env`:**
```env
LEGALPARTNER_CHAT_API_URL=https://YOUR-NGROK-URL-HERE/v1
LEGALPARTNER_CHAT_API_MODEL=opennyaiorg/Aalap-Mistral-7B-v0.1-bf16
```
