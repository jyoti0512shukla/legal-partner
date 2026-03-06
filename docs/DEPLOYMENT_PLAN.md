# Legal Partner — Deployment Plan & Cost

**Target:** Mid-tier Indian law firms (10–20 employees)  
**Domain:** **legal-ai.studio**  
**Last updated:** March 2026

---

## Employee Access URL

| Setup | URL |
|-------|-----|
| **Single tenant** | `https://legal-ai.studio` |
| **Multi-tenant (per firm)** | `https://firm1.legal-ai.studio`, `https://firm2.legal-ai.studio` |

---

## Overview

| Phase | Purpose | Cost |
|-------|---------|------|
| **Dev/Test** | Colab + ngrok — see `docs/COLAB_DEV_SETUP.md` | ~$10 or pay-as-you-go |
| **Production** | E2E Networks — full stack on one instance | ~₹32K/month |

---

# Production Deployment (E2E Networks)

## 1. Target & Capacity

| Metric | Value |
|--------|-------|
| **Target** | 10–20 employee law firms |
| **Concurrent users** | 5–10 typical (not all query at once) |
| **Document volume** | 5,000–50,000 contracts |
| **Response time** | 8–15 sec per query (acceptable for legal research) |

---

## 2. GPU Instance Recommendation

### Recommended: NVIDIA L4 (24 GB VRAM)

| Spec | L4 Instance |
|------|--------------|
| **GPU** | NVIDIA L4, 24 GB VRAM |
| **vCPU** | 25 |
| **RAM** | 110 GB |
| **Why** | Fits AALAP-Mistral-7B (~4.5 GB) + InLegalBERT (~0.4 GB) + KV cache for 5–10 concurrent requests. Cost-effective for 7B models. |

### Capacity for 10–20 Users

| Workload | L4 Capacity |
|----------|--------------|
| **Concurrent AI queries** | 5–10 (typical for 10–20 person firm) |
| **Embeddings** | Batch indexing; no real-time bottleneck |
| **Backend + DB** | 110 GB RAM + 25 vCPU is ample |

### When to Upgrade

| Scenario | Upgrade to | Cost (E2E, monthly) |
|----------|------------|---------------------|
| 20+ concurrent users | 2× L4 or A100 | 2× L4: ~₹62K; A100: ~₹75K |
| Larger model (70B) | A100 40 GB | ~₹75,000 |
| Sub-3-sec response | A100 or H100 | A100: ~₹75K; H100: ~₹1.56L |

---

## 3. Architecture (Single Machine)

```
┌─────────────────────────────────────────────────────────────────┐
│  E2E Networks L4 Instance (one server)                           │
│                                                                 │
│  ┌─────────────┐     localhost      ┌─────────────────────┐   │
│  │  Postgres   │◄──────────────────│  Spring Boot         │   │
│  │  + PGVector │                    │  Backend :8080       │   │
│  │  (768-dim)  │                    └──────────┬────────────┘   │
│  └─────────────┘                                │                │
│                    ┌─────────────────────────────┼───────────────┐│
│                    │ localhost                  │               ││
│                    ▼                            ▼               ││
│  ┌─────────────────────┐           ┌─────────────────────┐   │
│  │  Embeddings          │           │  vLLM               │   │
│  │  InLegalBERT (768-dim)│           │  AALAP-Mistral-7B   │   │
│  │  ~0.4 GB VRAM         │           │  :8000 (chat)       │   │
│  └──────────┬──────────┘           └──────────┬──────────┘   │
│             │                                  │               │
│             └──────────────┬───────────────────┘               │
│                            │ GPU                                │
│                            ▼                                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  NVIDIA L4 (24 GB VRAM)                                  │   │
│  │  AALAP ~4.5 GB + InLegalBERT ~0.4 GB + KV-cache ~19 GB   │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Cost Breakdown

### E2E Networks L4 — Monthly

| Item | Cost |
|------|------|
| **L4 GPU instance** (25 vCPU, 110 GB RAM, 24 GB VRAM) | ₹30,762/month |
| **Domain** (legal-ai.studio) + SSL (Let's Encrypt) | ~₹200/month |
| **Backup storage** (100 GB) | ~₹1,000/month |
| **Total** | **~₹32,000/month** |

### Annual

| Item | Cost |
|------|------|
| **Infrastructure** | ~₹3.8 lakh/year |
| **One-time setup** (optional) | ₹0 (self-deploy) or ₹50K–1L (managed) |

### Cost per User (10–20 employees)

| Users | Monthly | Per user/month |
|-------|---------|----------------|
| 10 | ₹32K | ₹3,200 |
| 20 | ₹32K | ₹1,600 |

---

## 5. Deployment Steps

### Step 1: Provision E2E Instance

1. Sign up at [e2enetworks.com](https://www.e2enetworks.com)
2. Create GPU instance:
   - **GPU:** NVIDIA L4
   - **OS:** Ubuntu 22.04
   - **Region:** Mumbai / Delhi / Bangalore
3. SSH into instance

### Step 2: Install Docker + NVIDIA

```bash
# Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER

# NVIDIA Container Toolkit
curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey | sudo gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
curl -fsSL https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list | sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' | sudo tee /etc/apt/sources.list.d/nvidia-container-toolkit.list
sudo apt-get update && sudo apt-get install -y nvidia-container-toolkit
sudo nvidia-ctk runtime configure --runtime=docker
sudo systemctl restart docker
```

### Step 3: Clone & Configure

```bash
git clone https://github.com/jyoti0512shukla/legal-partner.git
cd legal-partner
```

Set environment variables (use strong values in production):

```bash
export DB_PASSWORD=<strong-random-password>
export ENCRYPTION_KEY=<strong-random-key>
```

### Step 4: Add vLLM + Embedding Service

- Add vLLM service serving `opennyaiorg/Aalap-Mistral-7B-v0.1-bf16` (or GGUF via Ollama)
- Add embedding service for InLegalBERT (768-dim) — sentence-transformers or compatible API
- Configure backend: `LEGALPARTNER_CHAT_API_URL=http://vllm:8000` and embedding model pointing to InLegalBERT
- PGVector dimension must be 768 (migration from 384 if upgrading from dev)

### Step 5: Start Stack

```bash
docker compose up -d --build
```

### Step 6: Domain & HTTPS (Production)

1. **DNS:** Add A record — `legal-ai.studio` (or `*.legal-ai.studio` for subdomains) → E2E instance public IP
2. **SSL:** `sudo certbot --nginx -d legal-ai.studio -d "*.legal-ai.studio"`
3. Employees access: `https://legal-ai.studio`

---

## 6. Security Checklist

| Control | Action |
|---------|--------|
| **Firewall** | Allow only 22 (SSH), 80, 443; block 5432, 8000, 11434 from public |
| **SSH** | Key-based auth, disable password login |
| **Secrets** | DB password, encryption key from env vars only |
| **HTTPS** | TLS for all external traffic |
| **Backups** | Daily `pg_dump` to encrypted storage |

---

## 7. Summary

| Item | Value |
|------|-------|
| **Domain** | legal-ai.studio |
| **Employee URL** | https://legal-ai.studio |
| **Chat model** | AALAP-Mistral-7B (vLLM) |
| **Embedding model** | InLegalBERT (768-dim) |
| **GPU** | NVIDIA L4 (24 GB) |
| **Provider** | E2E Networks (India) |
| **Cost** | ~₹32K/month (~₹3.8L/year) |
| **Capacity** | 10–20 employees, 5–10 concurrent queries |
| **Deployment** | Single instance, Docker Compose |
| **Data** | Stays in India (Mumbai/Delhi/Bangalore) |

---

# Quick Reference

| Phase | Where | Models | Cost |
|-------|-------|--------|------|
| **Dev/Test** | Colab + local | Chat: AALAP (Colab), Embeddings: all-minilm (Ollama) | ~$10 or pay-as-you-go |
| **Production** | E2E L4 | Chat: AALAP, Embeddings: InLegalBERT | ~₹32K/month |
