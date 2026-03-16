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

**Shared across all options:** Colab runs vLLM + AALAP for chat, exposed via ngrok.

---

## Colab Setup (All Options — Do This First)

### Colab Notebook: vLLM + ngrok

1. [colab.research.google.com](https://colab.research.google.com) → New notebook → **Runtime → GPU** (T4 or A100)
2. Colab Pro (~$10/mo) or pay-as-you-go

**Cell 1 — Install vLLM**
```python
!pip install -q vllm
```

**Cell 2 — Start vLLM (chat)**
```python
!vllm serve opennyaiorg/Aalap-Mistral-7B-v0.1-bf16 \
  --port 8000 --host 0.0.0.0 \
  --max-model-len 2048 --gpu-memory-utilization 0.9
```
*First run: ~14 GB download, 5–15 min. Fallback: Mistral-7B-Instruct if AALAP gated.*

**Cell 3 — ngrok (public URL)**
```python
!pip install -q pyngrok
from pyngrok import ngrok
url = ngrok.connect(8000)
print("LEGALPARTNER_CHAT_API_URL:", url.public_url)
```

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
| vLLM OOM on T4 | Reduce `--max-model-len` to 1024; or use Colab Pro A100 |
| AALAP gated | HF login + request access; fallback: Mistral-7B-Instruct-v0.2 |
| ngrok blocked | Try localtunnel, cloudflared |
| Backend can't reach Colab | Ensure no trailing slash on URL; use https |
| Session expired | Re-run Colab cells; update env vars; restart backend |
