# SaulLM-7B Colab Setup (US Legal)

**Model:** `Equall/Saul-Instruct-v1` — 7B parameter model trained on 30B tokens of English legal text (contracts, case law, legislation). Best open-source model for US legal contract analysis.

**GPU required:** A100 40GB (Colab Pro) — full 8192 token context, fp16, no quantization needed.

---

## Prerequisites

| Item | Where to get |
|------|-------------|
| Colab Pro subscription | [colab.research.google.com](https://colab.research.google.com) — ~$10/mo |
| HuggingFace account + token | [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens) |
| ngrok account + token | [dashboard.ngrok.com](https://dashboard.ngrok.com) |
| Model access approved | Visit [huggingface.co/Equall/Saul-Instruct-v1](https://huggingface.co/Equall/Saul-Instruct-v1) → click **Agree and access repository** |

---

## Colab Notebook

### Cell 1 — Verify GPU

Run this **first** before spending time on installs — confirm you have A100, not L4 or T4.

```python
!nvidia-smi
```

Expected output:
```
| NVIDIA A100-SXM4-40GB   ...   40960MiB |
```

> If you see L4 (23GB) or T4 (16GB), request a different runtime:
> **Runtime → Change runtime type → Hardware accelerator → A100**
> If A100 is not available, use the L4 setup in `COLAB_DEV_SETUP.md` with `--max-model-len 4096`.

---

### Cell 2 — Install dependencies

```python
!pip install vllm pyngrok outlines -q
```

After this cell finishes → **Runtime → Restart runtime** (mandatory).

---

### Cell 3 — Verify installs after restart

```python
import vllm, outlines
print("vllm:", vllm.__version__)
print("outlines: OK")
```

Both must print without error. If `outlines` import fails, re-run Cell 2.

---

### Cell 4 — HuggingFace login (if model is gated)

```python
import os
os.environ["HUGGING_FACE_HUB_TOKEN"] = "hf_YOUR_TOKEN_HERE"
```

---

### Cell 5 — ngrok tunnel

Start the tunnel **before** the vLLM server so the URL is ready.

```python
from pyngrok import ngrok, conf

conf.get_default().auth_token = "YOUR_NGROK_TOKEN_HERE"
tunnel = ngrok.connect(8000, "http")
VLLM_URL = tunnel.public_url
print("Public URL:", VLLM_URL)
```

Copy the printed URL — you will paste it into the backend config.

---

### Cell 6 — Write Mistral chat template (CRITICAL)

Saul-7B is based on Mistral and does not ship a proper `tokenizer_config.json` chat template. Without this, vLLM wraps messages incorrectly → garbage output or "roles must alternate" errors.

**This cell MUST run before starting the vLLM server.**

```python
%%writefile /tmp/mistral.jinja
{{ bos_token }}{% for message in messages %}{% if message['role'] == 'user' %}[INST] {{ message['content'] }} [/INST]{% elif message['role'] == 'assistant' %}{{ message['content'] }}{{ eos_token }}{% endif %}{% endfor %}
```

---

### Cell 7 — Start vLLM server

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

Model download is ~14GB on first run (~5 min). Subsequent runs use the Colab cache.

Server is ready when you see:
```
INFO:     Application startup complete.
INFO:     Uvicorn running on http://0.0.0.0:8000
```

> **Key flags:**
> - `--chat-template /tmp/mistral.jinja` — uses the Mistral chat format from Cell 6
> - `--dtype half` — fp16 for A100/L4
> - `--gpu-memory-utilization 0.95` — maximise VRAM usage
> - For **L4 (23GB)**: change `--max-model-len 8192` to `4096`

---

### Cell 8 — Smoke test

```python
import requests

resp = requests.post(
    VLLM_URL + "/v1/chat/completions",
    headers={"Authorization": "Bearer no-op"},
    json={
        "model": "Equall/Saul-Instruct-v1",
        "messages": [
            {"role": "user", "content": "What is a limitation of liability clause? One sentence."}
        ],
        "max_tokens": 80
    }
)
print(resp.json()["choices"][0]["message"]["content"])
```

---

### Cell 9 — Verify guided_json / Outlines (structured response)

This confirms constrained decoding is active. If this returns valid JSON, all structured output features in the backend (`VllmGuidedClient` + `StructuredSchemas`) will work reliably.

```python
import json

resp = requests.post(
    VLLM_URL + "/v1/chat/completions",
    headers={"Authorization": "Bearer no-op"},
    json={
        "model": "Equall/Saul-Instruct-v1",
        "messages": [
            {"role": "user", "content": "Rate the overall contract risk."}
        ],
        "max_tokens": 20,
        "guided_json": {
            "type": "object",
            "properties": {
                "risk": {"type": "string", "enum": ["HIGH", "MEDIUM", "LOW"]}
            },
            "required": ["risk"]
        }
    }
)
result = resp.json()["choices"][0]["message"]["content"]
print(result)
# Expected: {"risk": "HIGH"} — valid JSON, constrained to enum
# If prose is returned instead: outlines is not active, re-check Cell 3
```

---

### Cell 10 — Keep-alive (optional, prevents Colab session timeout)

```python
import time, requests

while True:
    time.sleep(30)
    try:
        requests.get("http://localhost:8000/health", timeout=5)
    except Exception as e:
        print("vLLM unreachable:", e)
```

---

## Quick Copy-Paste (all cells in order)

For fast setup, run these cells in order:

```
Cell 1: !nvidia-smi
Cell 2: !pip install vllm pyngrok outlines -q  → then RESTART RUNTIME
Cell 3: import vllm, outlines; print("OK")
Cell 4: os.environ["HUGGING_FACE_HUB_TOKEN"] = "hf_..."
Cell 5: ngrok tunnel → get VLLM_URL
Cell 6: %%writefile /tmp/mistral.jinja  (Mistral chat template)
Cell 7: vllm serve ... --chat-template /tmp/mistral.jinja
Cell 8: Smoke test (plain text)
Cell 9: guided_json test (structured JSON)
```

---

## Backend Configuration

Update the following two values wherever your backend config is defined.

**Environment variables (GCP VM):**
```bash
export LEGALPARTNER_CHAT_API_URL=https://YOUR-NGROK-URL-HERE/v1
export LEGALPARTNER_CHAT_API_MODEL=Equall/Saul-Instruct-v1
```

**`application.yml` defaults:**
```yaml
legalpartner:
  chat-api-url: ${LEGALPARTNER_CHAT_API_URL:}
  chat-api-model: ${LEGALPARTNER_CHAT_API_MODEL:Equall/Saul-Instruct-v1}
```

**Docker Compose environment:**
```yaml
environment:
  - LEGALPARTNER_CHAT_API_URL=https://YOUR-NGROK-URL-HERE
  - LEGALPARTNER_CHAT_API_MODEL=Equall/Saul-Instruct-v1
```

Restart the backend after updating. No code changes needed.

---

## What to Look for in Backend Logs

### Success — guided_json working end-to-end:
```
[prompt=v10-semantic-discipline] guided_json parsed: overall=HIGH, categories=7
[prompt=v10-semantic-discipline] guided_json checklist parsed: 12 clauses
```

### Fallback active — Outlines not installed or vLLM too old:
```
[prompt=v10-semantic-discipline] guided_json returned no categories — falling back to CSV completions
```
Fix: re-run Cell 2 and Cell 3, confirm outlines imports correctly.

### LLM unreachable:
```
LLM endpoint returned an error page (HTTP 404) — ngrok tunnel may be offline
```
Fix: re-run Cell 5 (ngrok), update backend config with new URL.

### Chat template issue (garbage output):
```
Conversation roles must alternate user/assistant
```
Fix: make sure Cell 6 ran and `--chat-template /tmp/mistral.jinja` is in the serve command.

---

## GPU Memory Reference

| Configuration | Weights | KV Cache | Total | Fits on L4 23GB? | Fits on A100 40GB? |
|---------------|---------|----------|-------|-----------------|-------------------|
| SaulLM-7B fp16, ctx 4096 | ~14GB | ~2GB | ~16GB | ✅ | ✅ |
| SaulLM-7B fp16, ctx 8192 | ~14GB | ~4GB | ~18GB | ⚠️ tight | ✅ |
| SaulLM-54B fp16 | ~108GB | — | >108GB | ❌ | ❌ |
| SaulLM-54B AWQ 4-bit | ~27GB | ~5GB | ~32GB | ❌ | ✅ |

For **L4 (23GB)**: use `--max-model-len 4096` instead of 8192.

---

## Switching Back to AALAP (Indian Legal)

Change the two config values and restart:
```bash
export LEGALPARTNER_CHAT_API_URL=https://YOUR-NGROK-URL-HERE/v1
export LEGALPARTNER_CHAT_API_MODEL=opennyaiorg/Aalap-Mistral-7B-v0.1-bf16
```

vLLM command for AALAP (also uses the Mistral chat template):
```python
!HF_TOKEN="hf_YOUR_TOKEN" python -m vllm.entrypoints.openai.api_server \
    --model opennyaiorg/Aalap-Mistral-7B-v0.1-bf16 \
    --host 0.0.0.0 \
    --port 8000 \
    --max-model-len 8192 \
    --dtype half \
    --gpu-memory-utilization 0.95 \
    --chat-template /tmp/mistral.jinja
```
