# Legal Partner — Dev/Test Setup

**Purpose:** Develop and test Legal Partner with AALAP before production. Chat (LLM) runs in **Google Colab** (GPU); backend, DB, and embeddings can run locally or in the cloud.

> **Production:** See `docs/DEPLOYMENT_PLAN.md` for E2E Networks (InLegalBERT + AALAP).

---

## Overview: Pick Your Setup

| Option | Where things run | Laptop needs | Cost | Best when |
|--------|------------------|--------------|------|-----------|
| **A** | Colab (chat) + Laptop (DB, embeddings, backend) | Docker, 4GB+ RAM | ~$10 Colab | Laptop can run Docker |
| **B** | Colab (chat+embeddings) + Neon (DB) + Laptop (backend) | Java, Node only | ~$10 Colab | Laptop can't run Postgres/Ollama |
| **C** | Colab (chat) + Cloud (DB, embeddings, backend) | Browser only | ~$10 Colab + free tiers | Laptop runs nothing |
| **D** | Colab (chat) + Cheap VPS (DB, embeddings, backend) | Browser only | ~$0–₹500/mo | Oracle free / Hetzner / Contabo |
| **E** | Colab (chat) + GCP VM (DB, embeddings, backend) | Browser only | $300 credit (~$50/mo, stop when idle) | You have GCP credits |

**Shared across all options:** Colab runs LLM for chat, exposed via ngrok.

---

## Colab Setup (All Options — Do This First)

### Option 1: Flask + Transformers (Recommended — works without vLLM issues)

1. [colab.research.google.com](https://colab.research.google.com) → New notebook → **Runtime → GPU** (T4 or A100)
2. Colab Pro (~$10/mo) or pay-as-you-go

**Cell 1 — Install dependencies**
```python
!pip install -q transformers torch accelerate flask pyngrok
```

**Cell 2 — HuggingFace login (required for gated models)**
```python
from huggingface_hub import login
login(token="YOUR_HF_TOKEN")  # huggingface.co/settings/tokens
```

**Cell 3 — Load model + start Flask server**
```python
from flask import Flask, request, jsonify
from transformers import AutoModelForCausalLM, AutoTokenizer
import torch, threading

app = Flask(__name__)
model_name = "mistralai/Mistral-7B-Instruct-v0.2"
print("Loading model (first run ~5-10 min)...")
tokenizer = AutoTokenizer.from_pretrained(model_name)
model = AutoModelForCausalLM.from_pretrained(model_name, torch_dtype=torch.float16, device_map="auto")
print("✓ Model loaded")

def build_prompt(messages):
    prompt = ""
    for m in messages:
        role, content = m.get("role", "user"), m.get("content", "")
        if role == "system":
            prompt += f"<s>[INST] {content} [/INST] "
        elif role == "user":
            prompt += f"[INST] {content} [/INST] "
        elif role == "assistant":
            prompt += f"{content} "
    return prompt

@app.route("/v1/chat/completions", methods=["POST"])
def chat():
    data = request.json
    prompt = build_prompt(data.get("messages", []))
    inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
    out = model.generate(**inputs, max_new_tokens=512, do_sample=True,
                         temperature=0.3, pad_token_id=tokenizer.eos_token_id)
    reply = tokenizer.decode(out[0][inputs.input_ids.shape[1]:], skip_special_tokens=True)
    return jsonify({"choices": [{"message": {"content": reply.strip(), "role": "assistant"}}]})

@app.route("/v1/models")
def models():
    return jsonify({"data": [{"id": model_name}]})

threading.Thread(target=lambda: app.run(host="0.0.0.0", port=8000, debug=False, use_reloader=False)).start()
print("✓ Flask server running on port 8000")
```

**Cell 4 — Expose via ngrok**
```python
!pip install -q pyngrok
from pyngrok import ngrok

ngrok.set_auth_token("YOUR_NGROK_TOKEN")  # dashboard.ngrok.com
public_url = ngrok.connect(8000)
print("Set this in Legal Partner backend:")
print(public_url.public_url + "/v1")
```
> **Important:** `!pip install -q pyngrok` must be in the **same cell** as the import, or in a cell that runs before it. The import will fail with `ModuleNotFoundError` otherwise.

Set in backend env:
```
LEGALPARTNER_CHAT_API_URL=https://xxxx.ngrok-free.app/v1
LEGALPARTNER_CHAT_API_MODEL=mistralai/Mistral-7B-Instruct-v0.2
```

---

### Option 2: vLLM + AALAP (recommended for proper test setup)

**Requirements:** Colab Pro with **A100 GPU** (40GB VRAM). T4 is too small for AALAP bf16.

1. [colab.research.google.com](https://colab.research.google.com) → New notebook
2. **Runtime → Change runtime type → A100 GPU → Save**

**Cell 1 — Install vLLM**
```python
!pip install -q vllm pyngrok
import os
os.environ["VLLM_USE_V1"] = "0"
```

**Cell 2 — HuggingFace login (required — AALAP is a gated model)**
```python
from huggingface_hub import login
login(token="YOUR_HF_TOKEN")  # huggingface.co/settings/tokens
```
> Request access at huggingface.co/opennyaiorg/Aalap-Mistral-7B-v0.1-bf16 first if not already approved.

**Cell 3 — ngrok (run before vLLM so URL is printed immediately)**
```python
from pyngrok import ngrok
ngrok.set_auth_token("YOUR_NGROK_TOKEN")  # dashboard.ngrok.com
url = ngrok.connect(8000)
print("LEGALPARTNER_CHAT_API_URL:", url.public_url + "/v1")
```
Copy the printed URL — you'll need it for the VM `.env`.

**Cell 4 — Save Mistral chat template (AALAP requires explicit template)**
```python
template = """{{ bos_token }}{% for message in messages %}{% if message['role'] == 'user' %}[INST] {{ message['content'] }} [/INST]{% elif message['role'] == 'assistant' %}{{ message['content'] }}{{ eos_token }}{% elif message['role'] == 'system' %}{{ message['content'] }}{% endif %}{% endfor %}"""
with open("/tmp/mistral.jinja", "w") as f:
    f.write(template)
print("Template saved")
```

**Cell 5 — Start vLLM (blocks while running — first run ~15 min to download 14GB)**
```python
!vllm serve opennyaiorg/Aalap-Mistral-7B-v0.1-bf16 \
  --port 8000 --host 0.0.0.0 \
  --max-model-len 4096 --gpu-memory-utilization 0.9 \
  --chat-template /tmp/mistral.jinja
```
Wait for `Application startup complete` before testing.

**Set in VM `.env`:**
```
LEGALPARTNER_CHAT_API_URL=https://xxxx.ngrok-free.app/v1
LEGALPARTNER_CHAT_API_MODEL=opennyaiorg/Aalap-Mistral-7B-v0.1-bf16
```

Then restart backend on VM:
```bash
cd ~/legal-partner && docker-compose restart backend
```

> **Session expiry:** Colab sessions expire after ~90 min idle. Keep the tab open. On restart, re-run all cells and update `LEGALPARTNER_CHAT_API_URL` in `.env` with the new ngrok URL.

**Cell 4 — (Options B/C/D/E) Embedding server (same Colab)**
```python
!pip install -q sentence-transformers flask
from sentence_transformers import SentenceTransformer
from flask import Flask, request, jsonify
app = Flask(__name__)
model = SentenceTransformer('all-MiniLM-L6-v2')

@app.route('/v1/embeddings', methods=['POST'])
def embed():
    data = request.json
    inp = data.get('input') or []
    if isinstance(inp, str): inp = [inp]
    embs = model.encode(inp).tolist()
    return jsonify({"data": [{"embedding": e} for e in embs]})

import threading
threading.Thread(target=lambda: app.run(host='0.0.0.0', port=8001), daemon=True).start()
embed_url = ngrok.connect(8001)
print("LEGALPARTNER_EMBEDDING_API_URL:", embed_url.public_url)
```

---

## Option A: Standard (Laptop Runs Everything Except Chat)

**Laptop:** Docker (Postgres + Ollama), Backend, Frontend  
**Colab:** Chat only

```
Colab (vLLM) ──ngrok──► Laptop ←── Postgres, Ollama (embeddings), Backend
```

### Steps

```bash
export LEGALPARTNER_CHAT_API_URL=https://<colab-ngrok-url>
docker compose up -d postgres ollama backend
cd frontend && npm run dev
```

Open http://localhost:5173

---

## Option B: Low-Resource Laptop

**Laptop:** Backend + Frontend only (no Docker for Postgres/Ollama)  
**Neon:** Postgres + pgvector  
**Colab:** Chat + Embeddings (run Cell 4 above)

### Steps

1. **Neon:** [neon.tech](https://neon.tech) → Create project → SQL Editor: `CREATE EXTENSION IF NOT EXISTS vector;` → Copy connection string
2. **Colab:** Run all 4 cells (vLLM + ngrok + embedding server)
3. **Laptop:**
```bash
export SPRING_DATASOURCE_URL="postgresql://user:pass@ep-xxx.neon.tech/neondb?sslmode=require"
export SPRING_DATASOURCE_USERNAME="user"
export SPRING_DATASOURCE_PASSWORD="pass"
export LEGALPARTNER_CHAT_API_URL=https://<chat-ngrok-url>
export LEGALPARTNER_EMBEDDING_API_URL=https://<embed-ngrok-url>

cd backend && ./gradlew bootRun
# Another terminal:
cd frontend && npm run dev
```

---

## Option C: Full Cloud (Laptop = Browser Only)

**Neon:** Postgres + pgvector  
**HuggingFace:** Embeddings (free Inference API) — *or* Colab embedding server  
**Railway / Render:** Backend  
**Colab:** Chat  

Backend and frontend deployed to cloud; you only open the app URL in a browser.

### Steps

1. **Neon** + **Colab** (chat, optional: embeddings) — same as Option B
2. **Deploy backend** to Railway/Render:
   - Connect GitHub repo
   - Env: `SPRING_DATASOURCE_URL`, `LEGALPARTNER_CHAT_API_URL`, `LEGALPARTNER_EMBEDDING_API_URL` (if using Colab embeddings)
   - Build: `./gradlew bootJar` or Dockerfile
3. **Frontend:** Build (`npm run build`) → deploy to same app or Vercel/Netlify; set API base URL to backend

*When Colab session expires: re-run cells, update `LEGALPARTNER_CHAT_API_URL` in Railway/Render, redeploy/restart.*

---

## Option D: Colab + Cheap CPU VPS

**VPS:** Postgres + Embedding service + Spring Boot (all on one machine)  
**Colab:** Chat only  
**Laptop:** SSH + Browser

### VPS Options

| Provider | Specs | Cost |
|----------|-------|------|
| **Oracle Cloud Free** | 4 ARM cores, 24GB RAM | ₹0 |
| **Hetzner** | 2 vCPU, 4GB RAM | ~₹450/mo |
| **Contabo** | 4 vCPU, 8GB RAM | ~₹550/mo |

### Steps (e.g. Hetzner)

1. Create VM (Ubuntu 22.04, 4GB RAM)
2. SSH in:
```bash
sudo apt update && sudo apt install -y docker.io docker-compose git
sudo usermod -aG docker $USER && exit
# Re-SSH
git clone https://github.com/your-org/legal-partner.git
cd legal-partner
```
3. Use `docker compose` with postgres + backend. Run embedding service separately (Python Flask on port 8001) or add to compose.
4. Env vars:
```bash
export LEGALPARTNER_CHAT_API_URL=https://<colab-ngrok>
export LEGALPARTNER_EMBEDDING_API_URL=http://embedding:8001  # or http://localhost:8001
export SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/legalpartner
```
5. Open `http://<vps-ip>:8080` (ensure firewall allows 80/443/8080)

---

## Option E: Colab + GCP VM ($300 Credit)

**GCP Compute Engine:** Postgres + Embedding service + Spring Boot  
**Colab:** Chat only  
**Laptop:** Browser (and `gcloud` to stop/start)

### Why GCP

- Use $300 credit
- **Stop VM when not testing** → no charge for CPU/RAM; only small disk cost
- e2-medium (2 vCPU, 4GB): ~$50/mo when running → ~6 months at 2 hr/day usage

### Steps

1. **Create VM**
   - [console.cloud.google.com](https://console.cloud.google.com) → Compute Engine → Create VM
   - **e2-medium** (2 vCPU, 4 GB RAM)
   - **Region:** asia-south1 (Mumbai)
   - Ubuntu 22.04, 20 GB disk

2. **SSH → Install**
```bash
sudo apt update && sudo apt install -y docker.io docker-compose git
sudo usermod -aG docker $USER
git clone https://github.com/your-org/legal-partner.git
cd legal-partner
# Use docker compose: postgres + backend. Add embedding service (Flask/sentence-transformers) or use Colab embeddings.
```

3. **Env vars on VM**
```bash
export LEGALPARTNER_CHAT_API_URL=https://<colab-ngrok-url>
export LEGALPARTNER_EMBEDDING_API_URL=http://localhost:8001  # if embedding runs on same VM
# Or use Colab embeddings: export LEGALPARTNER_EMBEDDING_API_URL=https://<embed-ngrok>
```

4. **Stop when done**
```bash
gcloud compute instances stop INSTANCE_NAME --zone=asia-south1-a
```

5. **Start when testing again**
```bash
gcloud compute instances start INSTANCE_NAME --zone=asia-south1-a
```

*Note: External IP may change after stop/start; use a static IP if needed.*

---

## Session Handling (Colab)

- Colab sessions expire (~90 min idle on free; longer on Pro)
- **Each new session:** Re-run Colab cells → copy new ngrok URL(s) → update `LEGALPARTNER_CHAT_API_URL` (and `LEGALPARTNER_EMBEDDING_API_URL` if used) → restart backend / update cloud env

---

## Quick Reference

| Option | Colab | DB | Embeddings | Backend | Cost |
|--------|-------|-----|------------|---------|------|
| A | Chat | Local Docker | Local Ollama | Laptop | ~$10 Colab |
| B | Chat + Embed | Neon | Colab Flask | Laptop | ~$10 Colab |
| C | Chat | Neon | HF or Colab | Railway/Render | ~$10 + free tiers |
| D | Chat | VPS | VPS | VPS | ~$0–₹500/mo |
| E | Chat | GCP VM | GCP VM | GCP VM | ~$50/mo (stop when idle) |

---

## Backend Env Vars Reference

| Variable | Purpose |
|----------|---------|
| `LEGALPARTNER_CHAT_API_URL` | Colab ngrok URL for chat (required when using Colab) |
| `LEGALPARTNER_EMBEDDING_API_URL` | OpenAI-compatible embedding endpoint (Colab Flask or external) |
| `SPRING_DATASOURCE_URL` | Postgres connection string (Neon, or postgres://host:5432/db for Docker) |
| `SPRING_DATASOURCE_USERNAME` | DB user |
| `SPRING_DATASOURCE_PASSWORD` | DB password |

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| vLLM OOM on T4 | Switch to A100 runtime — T4 (16GB) is too small for AALAP bf16 (14GB) |
| AALAP gated | HF login + request access at huggingface.co/opennyaiorg/Aalap-Mistral-7B-v0.1-bf16 |
| ChatTemplateResolutionError | Add `--chat-template /tmp/mistral.jinja` (run Cell 4 first to save template) |
| Input too long / 400 BadRequest | Increase `--max-model-len` to 4096 (safe on A100) |
| 0 risk categories shown | Max token limit too low — model truncates response; use 4096 |
| ngrok URL offline (ERR_NGROK_3200) | Colab session expired — re-run cells, get new URL, update `.env`, restart backend |
| ngrok cell blocks without printing | Run ngrok cell before vLLM cell |
| Backend uses wrong model name | Ensure `LEGALPARTNER_CHAT_API_MODEL` is set in `.env` AND passed in `docker-compose.yml` |
| Backend can't reach Colab | Ensure no trailing slash on URL; use https |
| Session expired | Re-run all Colab cells; update `LEGALPARTNER_CHAT_API_URL` in `.env`; `docker-compose restart backend` |
