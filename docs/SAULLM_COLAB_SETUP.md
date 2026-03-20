# SaulLM Colab Setup

**Model:** `Equall/Saul-Instruct-v1` — 7B parameter legal LLM trained on 30B tokens of English legal text.

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
os.environ["HUGGING_FACE_HUB_TOKEN"] = "hf_YOUR_TOKEN_HERE"
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

**For A100 (40GB):**

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

**For T4 (16GB):**

```python
!python -m vllm.entrypoints.openai.api_server \
    --model Equall/Saul-Instruct-v1 \
    --host 0.0.0.0 \
    --port 8000 \
    --max-model-len 4096 \
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

### Cell 9 — Test structured JSON (guided_json)

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
print(resp.json()["choices"][0]["message"]["content"])
# Expected: {"risk": "HIGH"} or similar valid JSON
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
LEGALPARTNER_CHAT_API_MODEL=Equall/Saul-Instruct-v1
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
| `The model Equall/Saul-Instruct-v1 does not exist` | Model name in `.env` doesn't match `--model` in Cell 7. They must be identical. |
| `Conversation roles must alternate` | Cell 6 (chat template) didn't run, or `--chat-template /tmp/mistral.jinja` missing from Cell 7. |
| `LLM endpoint returned an error page (HTTP 404)` | ngrok tunnel died. Re-run Cell 5, update `.env` with new URL, restart backend. |
| `guided_json returned no categories` | Outlines not installed. Re-run Cell 2, restart runtime, verify Cell 3. |
| `CUDA out of memory` | Use `--max-model-len 4096` instead of `8192` (T4 GPU). |

---

## Switching to AALAP (Indian Legal)

Same setup, different model. Change Cell 7 and `.env`:

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
