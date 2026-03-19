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

### Cell 1 — Verify A100 GPU

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
!pip install vllm==0.4.3 outlines
```

After this cell finishes → **Runtime → Restart runtime** (mandatory).

---

### Cell 3 — Verify installs after restart

```python
import vllm, outlines
print("vllm:", vllm.__version__)   # expect: 0.4.3
print("outlines: OK")
```

Both must print without error. If `outlines` import fails, re-run Cell 2.

---

### Cell 4 — Start vLLM server

```python
!HF_TOKEN="hf_YOUR_TOKEN_HERE" vllm serve Equall/Saul-Instruct-v1 \
  --port 8000 --host 0.0.0.0 \
  --max-model-len 8192 \
  --gpu-memory-utilization 0.90 \
  --dtype float16
```

Model download is ~14GB on first run (~5 min). Subsequent runs use the Colab cache.

Server is ready when you see:
```
INFO:     Application startup complete.
INFO:     Uvicorn running on http://0.0.0.0:8000
```

---

### Cell 5 — ngrok tunnel (new cell, run in parallel)

```python
from pyngrok import ngrok, conf

conf.get_default().auth_token = "YOUR_NGROK_TOKEN_HERE"
tunnel = ngrok.connect(8000, "http")
VLLM_URL = tunnel.public_url
print("Public URL:", VLLM_URL)
```

Copy the printed URL — you will paste it into the backend config.

---

### Cell 6 — Smoke test

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

### Cell 7 — Verify guided_json / Outlines

This confirms constrained decoding is active. If this returns valid JSON, all structured output features in the backend will work reliably.

```python
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
print(resp.json()["choices"][0]["message"]["content"])
# Expected: {"risk": "HIGH"} — valid JSON, constrained to enum
# If prose is returned instead: outlines is not active, re-check Cell 3
```

---

### Cell 8 — Keep-alive (optional, prevents Colab session timeout)

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

## Backend Configuration

Update the following two values wherever your backend config is defined.

**`application.properties`:**
```properties
legalpartner.chat-api-url=https://YOUR-NGROK-URL-HERE
legalpartner.chat-api-model=Equall/Saul-Instruct-v1
```

**Docker Compose environment:**
```yaml
environment:
  - LEGALPARTNER_CHAT_API_URL=https://YOUR-NGROK-URL-HERE
  - LEGALPARTNER_CHAT_API_MODEL=Equall/Saul-Instruct-v1
```

Restart the backend container after updating. No code changes needed.

---

## What to Look for in Backend Logs

### Success — guided_json working end-to-end:
```
[prompt=v9-guided-json] guided_json parsed: overall=HIGH, categories=7
[prompt=v9-guided-json] guided_json checklist parsed: 12 clauses
```

### Fallback active — Outlines not installed or vLLM too old:
```
[prompt=v9-guided-json] guided_json returned no categories — falling back to CSV completions
```
Fix: re-run Cell 2 and Cell 3, confirm outlines imports correctly.

### LLM unreachable:
```
LLM endpoint returned an error page (HTTP 404) — ngrok tunnel may be offline
```
Fix: re-run Cell 5 (ngrok), update backend config with new URL.

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
```properties
legalpartner.chat-api-url=https://YOUR-NGROK-URL-HERE
legalpartner.chat-api-model=opennyaiorg/Aalap-Mistral-7B-v0.1-bf16
```

vLLM command for AALAP:
```python
!HF_TOKEN="hf_YOUR_TOKEN" vllm serve opennyaiorg/Aalap-Mistral-7B-v0.1-bf16 \
  --port 8000 --host 0.0.0.0 \
  --max-model-len 8192 --gpu-memory-utilization 0.90 \
  --chat-template /tmp/mistral.jinja
```
