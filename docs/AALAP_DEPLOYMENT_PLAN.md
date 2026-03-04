# AALAP Deployment Plan — Colab Pro + Legal Partner

**Goal:** Use Colab Pro for 1 month to develop, test, and validate AALAP with Legal Partner. No GGUF conversion.

**Architecture:**
```
┌─────────────────────────────────────────────────────────────────┐
│  Google Colab Pro (AALAP via vLLM)                               │
│  • vLLM serves opennyaiorg/Aalap-Mistral-7B-v0.1-bf16            │
│  • ngrok exposes public URL → https://xxxx.ngrok.io               │
└───────────────────────────────┬─────────────────────────────────┘
                                │ OpenAI-compatible API
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│  Your Laptop (Legal Partner)                                     │
│  • Docker: Postgres, Ollama (embeddings only), Backend            │
│  • Frontend: npm run dev                                          │
│  • Chat requests → Colab vLLM | Embeddings → local Ollama         │
└─────────────────────────────────────────────────────────────────┘
```

---

## Phase 1: Setup (Day 1)

### Step 1.1: Subscribe to Colab Pro
- [ ] Go to [colab.research.google.com](https://colab.research.google.com)
- [ ] Sign up for Colab Pro (~$10/month)
- [ ] Turn off auto-renew if you plan to cancel after 1 month

### Step 1.2: Prepare Your Laptop
- [ ] Ensure Docker + Docker Compose installed
- [ ] Ensure Node.js 18+ installed
- [ ] Clone/confirm `legal-partner` repo is ready

---

## Phase 2: Colab — Run vLLM with AALAP (Day 1–2)

### Step 2.1: Create Colab Notebook
1. [ ] Go to [colab.research.google.com](https://colab.research.google.com)
2. [ ] New notebook
3. [ ] **Runtime → Change runtime type → GPU** (T4 or A100 on Pro)

### Step 2.2: Install and Run vLLM
Add these cells and run in order:

**Cell 1 — Install vLLM**
```python
!pip install -q vllm
```

**Cell 2 — Start vLLM server**
```python
# AALAP from HuggingFace — no conversion
# For T4 (16GB): use memory-saving params
!vllm serve opennyaiorg/Aalap-Mistral-7B-v0.1-bf16 \
  --port 8000 \
  --host 0.0.0.0 \
  --max-model-len 2048 \
  --gpu-memory-utilization 0.9
```

First run downloads ~14 GB — wait 5–15 min.

### Step 2.3: Expose with ngrok
**Cell 3 — Install ngrok and get public URL**
```python
!pip install -q pyngrok
from pyngrok import ngrok
public_url = ngrok.connect(8000)
print("Use this URL in Legal Partner config:")
print(public_url.public_url)
```

Copy the URL (e.g. `https://abc123.ngrok-free.app`). This changes each Colab session.

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

Note: vLLM may use `http` internally; use the URL that works (with or without `https`).

---

## Phase 3: Legal Partner — Point Chat to vLLM (Day 2–3)

### Step 3.1: Add OpenAI-Compatible Support
Legal Partner must use vLLM (OpenAI-compatible) for **chat** and keep Ollama for **embeddings**.

- [ ] Add `langchain4j-open-ai` (or OpenAI-compatible) to `backend/build.gradle.kts`
- [ ] Add config for chat model pointing to Colab ngrok URL
- [ ] Keep Ollama for embeddings only

### Step 3.2: Configuration (implemented)
Set environment variable when running:

```bash
export LEGALPARTNER_CHAT_API_URL=https://circulable-chere-lucidly.ngrok-free.dev
```

Or for Docker: pass when starting:
```bash
LEGALPARTNER_CHAT_API_URL=https://your-ngrok-url.ngrok-free.dev docker compose up -d
```

Base URL format: `https://xxx.ngrok-free.dev` (no trailing /v1 — backend adds it).

### Step 3.3: Implementation Status
- [x] ChatModelConfig: uses OpenAI-compatible when `LEGALPARTNER_CHAT_API_URL` set
- [x] Embeddings remain from Ollama (all-minilm)
- [x] docker-compose passes `LEGALPARTNER_CHAT_API_URL`

---

## Phase 4: Run Legal Partner Locally (Day 3)

### Step 4.1: Start Colab First
- [ ] Open your Colab notebook
- [ ] Run all cells (vLLM + ngrok)
- [ ] Copy the ngrok URL

### Step 4.2: Start Legal Partner
```bash
cd legal-partner

# Set the Colab chat API URL (use your current ngrok URL)
export LEGALPARTNER_CHAT_API_URL=https://circulable-chere-lucidly.ngrok-free.dev

# Start stack (Ollama for embeddings only; chat uses Colab/Mistral)
docker compose up -d postgres ollama
# Wait for ollama-init to pull all-minilm
docker compose up -d backend

# Start frontend
cd frontend && npm run dev
```

### Step 4.3: Optional — Disable tinyllama Pull
If chat comes from vLLM, you can skip pulling tinyllama. Edit `docker-compose.yml` in `ollama-init` command to only `ollama pull all-minilm`.

---

## Phase 5: Test End-to-End (Day 4–5)

### Step 5.1: Smoke Test
- [ ] Open http://localhost:5173
- [ ] Login (admin / admin123)
- [ ] Upload a test contract (PDF/DOCX)
- [ ] Wait for indexing (INDEXED status)
- [ ] Ask a question — verify response uses AALAP (Indian legal context)

### Step 5.2: Feature Checklist
- [ ] **Ask** — semantic search + RAG answers
- [ ] **Compare** — two documents
- [ ] **Risk Assessment** — upload, get risk report

### Step 5.3: Session Handling
- Colab sessions time out (~90 min idle on free; longer on Pro)
- When session ends: vLLM stops, ngrok URL becomes invalid
- **Each new session:** Re-run Colab cells → get new ngrok URL → update `LEGALPARTNER_CHAT_API_URL` → restart backend

---

## Phase 6: After Testing — Options (Day 30+)

### Option A: Unsubscribe from Colab Pro
- [ ] Cancel before next billing
- [ ] Move to cloud GPU (RunPod, E2E) for production deployment

### Option B: Move to Cloud GPU
- [ ] Rent RunPod L4 or E2E L4
- [ ] Run vLLM there (same `vllm serve` command)
- [ ] Deploy Legal Partner on same instance or separate VM
- [ ] Point backend at cloud vLLM URL (stable, no ngrok)
- [ ] Cancel Colab Pro

---

## Quick Reference: Daily Workflow

| Step | Action |
|-----|--------|
| 1 | Open Colab notebook → Run all cells → Copy ngrok URL |
| 2 | `export LEGALPARTNER_CHAT_API_URL=https://<ngrok_url>` (e.g. https://xxx.ngrok-free.dev) |
| 3 | `docker compose up -d` (or restart backend) |
| 4 | `cd frontend && npm run dev` |
| 5 | Test at http://localhost:5173 |

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| Colab OOM on T4 | Reduce `--max-model-len` to 1024; or use Colab Pro A100 |
| ngrok URL blocked | Some networks block ngrok; try `localhost.run` or similar |
| Backend can't reach Colab | Ensure URL has no trailing slash; use `https` if vLLM/ngrok supports it |
| Slow first run | Model download ~14 GB; wait 10–15 min |
| Session expired | Re-run Colab cells; update URL; restart backend |

---

## Summary Checklist

- [ ] Colab Pro subscribed
- [ ] Colab notebook: vLLM + AALAP + ngrok
- [ ] Legal Partner: chat → vLLM, embeddings → Ollama
- [ ] End-to-end test passing
- [ ] Plan for Colab cancellation / move to cloud GPU
