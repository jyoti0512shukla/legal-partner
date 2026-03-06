# Legal Partner — Dev/Test Setup (Google Colab)

**Purpose:** Develop and test Legal Partner with AALAP before production deployment. Not for production — Colab sessions expire.

> **Production:** See `docs/DEPLOYMENT_PLAN.md` for E2E Networks setup with InLegalBERT + AALAP.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Google Colab (Chat only)                                        │
│  • vLLM serves AALAP-Mistral-7B (or Transformers+Flask fallback)│
│  • ngrok exposes public URL → https://xxxx.ngrok.io              │
└───────────────────────────────┬─────────────────────────────────┘
                                │ OpenAI-compatible API
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│  Your Laptop (Legal Partner)                                    │
│  • Docker: Postgres, Ollama (embeddings only), Backend           │
│  • Frontend: npm run dev                                         │
│  • Chat → Colab | Embeddings → local Ollama (all-minilm)         │
└─────────────────────────────────────────────────────────────────┘
```

**Models in dev:**
| Purpose | Model | Where |
|---------|-------|-------|
| Chat | AALAP-Mistral-7B (or Mistral-7B-Instruct fallback) | Colab |
| Embeddings | all-minilm (384-dim) | Local Ollama |

---

## Phase 1: Setup (Day 1)

### Step 1.1: Colab Access
- Go to [colab.research.google.com](https://colab.research.google.com)
- Colab Pro (~$10/month) or pay-as-you-go ($9.99 for 100 units) for T4/A100
- Turn off auto-renew if testing for 1 month only

### Step 1.2: Prepare Your Laptop
- Docker + Docker Compose installed
- Node.js 18+ installed
- Clone `legal-partner` repo

---

## Phase 2: Colab — Run vLLM with AALAP (Day 1–2)

### Step 2.1: Create Colab Notebook
1. New notebook
2. **Runtime → Change runtime type → GPU** (T4 or A100 on Pro)

### Step 2.2: Install and Run vLLM
**Cell 1 — Install vLLM**
```python
!pip install -q vllm
```

**Cell 2 — Start vLLM server**
```python
# AALAP from HuggingFace — requires HF login + access approval
# For T4 (16GB): use memory-saving params
!vllm serve opennyaiorg/Aalap-Mistral-7B-v0.1-bf16 \
  --port 8000 \
  --host 0.0.0.0 \
  --max-model-len 2048 \
  --gpu-memory-utilization 0.9
```

First run downloads ~14 GB — wait 5–15 min.

**If vLLM fails on T4** (engine init errors): Use Transformers + Flask fallback instead. See Troubleshooting.

### Step 2.3: Expose with ngrok
**Cell 3 — Install ngrok and get public URL**
```python
!pip install -q pyngrok
from pyngrok import ngrok
public_url = ngrok.connect(8000)
print("Use this URL in Legal Partner config:")
print(public_url.public_url)
```

Copy the URL (e.g. `https://abc123.ngrok-free.app`). Changes each Colab session.

### Step 2.4: Test vLLM
**Cell 4 — Quick test**
```python
import requests
r = requests.post(
    f"{public_url.public_url.replace('https', 'http')}/v1/chat/completions",
    json={
        "model": "opennyaiorg/Aalap-Mistral-7B-v0.1-bf16",
        "messages": [{"role": "user", "content": "What is Section 73 of ICA?"}]
    }
)
print(r.json()["choices"][0]["message"]["content"])
```

---

## Phase 3: Legal Partner — Point Chat to Colab (Day 2–3)

### Step 3.1: Configuration
When `LEGALPARTNER_CHAT_API_URL` is set, backend uses it for chat. Embeddings stay on local Ollama.

```bash
export LEGALPARTNER_CHAT_API_URL=https://your-ngrok-url.ngrok-free.dev
```

Or for Docker:
```bash
LEGALPARTNER_CHAT_API_URL=https://your-ngrok-url.ngrok-free.dev docker compose up -d
```

### Step 3.2: Implementation Status
- [x] ChatModelConfig: uses OpenAI-compatible when URL set
- [x] Embeddings remain from Ollama (all-minilm)
- [x] docker-compose passes `LEGALPARTNER_CHAT_API_URL`

---

## Phase 4: Run Legal Partner Locally (Day 3)

### Step 4.1: Start Colab First
- Open Colab notebook
- Run all cells (vLLM + ngrok)
- Copy ngrok URL

### Step 4.2: Start Legal Partner
```bash
cd legal-partner
export LEGALPARTNER_CHAT_API_URL=https://your-ngrok-url.ngrok-free.dev

docker compose up -d postgres ollama
# Wait for ollama-init to pull all-minilm
docker compose up -d backend

cd frontend && npm run dev
```

### Step 4.3: Optional — Skip tinyllama
If chat comes from Colab, edit `docker-compose.yml` ollama-init to only `ollama pull all-minilm`.

---

## Phase 5: Test End-to-End (Day 4–5)

- [ ] Open http://localhost:5173
- [ ] Login (admin / admin123)
- [ ] Upload test contract (PDF/DOCX)
- [ ] Wait for INDEXED status
- [ ] Ask question — verify RAG response
- [ ] Compare two documents
- [ ] Risk assessment

---

## Session Handling

- Colab sessions time out (~90 min idle on free; longer on Pro)
- When session ends: vLLM stops, ngrok URL invalid
- **Each new session:** Re-run Colab cells → copy new ngrok URL → `export LEGALPARTNER_CHAT_API_URL=...` → restart backend

---

## Quick Reference: Daily Workflow

| Step | Action |
|-----|--------|
| 1 | Open Colab → Run all cells → Copy ngrok URL |
| 2 | `export LEGALPARTNER_CHAT_API_URL=https://<ngrok_url>` |
| 3 | `docker compose up -d` (or restart backend) |
| 4 | `cd frontend && npm run dev` |
| 5 | Test at http://localhost:5173 |

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| vLLM OOM / engine init on T4 | Use Transformers + Flask instead; run Mistral-7B-Instruct via HuggingFace + FastAPI, expose via ngrok |
| AALAP gated on HuggingFace | HF login + request access at opennyaiorg; fallback: Mistral-7B-Instruct-v0.2 |
| ngrok URL blocked | Try localtunnel, cloudflared |
| Colab OOM on T4 | Reduce `--max-model-len` to 1024; or use Colab Pro A100 |
| Slow first run | Model download ~14 GB; wait 10–15 min |
| Session expired | Re-run Colab cells; update URL; restart backend |

---

## Summary Checklist

- [ ] Colab with GPU (Pro or pay-as-you-go)
- [ ] Colab: vLLM + AALAP + ngrok (or Transformers+Flask fallback)
- [ ] Legal Partner: chat → Colab, embeddings → local Ollama (all-minilm)
- [ ] End-to-end test passing
- [ ] Plan move to production (E2E) when ready
