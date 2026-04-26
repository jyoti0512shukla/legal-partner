# ContractIQ — Complete Deployment Guide

> Step-by-step guide for deploying ContractIQ for a new law firm customer.
> Covers VM creation, GPU setup, HTTPS, DocuSign, and secret management.

---

## Table of Contents

1. [Overview](#overview)
2. [Infrastructure Setup](#1-infrastructure-setup)
3. [Customer Configuration](#2-customer-configuration)
4. [Secret Encryption](#3-secret-encryption)
5. [Provisioning the VM](#4-provisioning-the-vm)
6. [DNS & HTTPS](#5-dns--https)
7. [First Deploy](#6-first-deploy)
8. [GPU Setup (SaulLM-54B)](#7-gpu-setup-saullm-54b)
9. [DocuSign Integration](#8-docusign-integration)
10. [Google Drive Integration](#9-google-drive-integration)
11. [Post-Deploy Verification](#10-post-deploy-verification)
12. [Ongoing Operations](#11-ongoing-operations)
13. [Updating & Rollback](#12-updating--rollback)
14. [Backup & Recovery](#13-backup--recovery)
15. [Multi-Customer Management](#14-multi-customer-management)
16. [Troubleshooting](#15-troubleshooting)
17. [Cost Reference](#16-cost-reference)

---

## Overview

### System Architecture

```
                    ┌──────────────────────────────────────┐
                    │         Customer VM (GCP)             │
                    │                                      │
Internet ──HTTPS──▶ │  Caddy (:443)                        │
                    │    ├── /api/* → Backend (:8080)       │
                    │    └── /*     → Frontend (:3000)      │
                    │                                      │
                    │  Backend (Spring Boot + JVM)          │
                    │    ├── PostgreSQL + pgvector           │
                    │    ├── Ollama (embeddings)             │
                    │    └── OnlyOffice (optional)           │
                    └──────────┬───────────────────────────┘
                               │
                               │ HTTPS (ngrok tunnel)
                               ▼
                    ┌──────────────────────┐
                    │  GPU Pod (RunPod)     │
                    │  SaulLM-54B AWQ       │
                    │  vLLM on A6000 48GB   │
                    └──────────────────────┘
```

### What runs where

| Component | Location | Purpose |
|-----------|----------|---------|
| Frontend (React + nginx) | Customer VM | Web UI |
| Backend (Spring Boot) | Customer VM | API, business logic, RAG |
| PostgreSQL + pgvector | Customer VM | Data + vector embeddings |
| Ollama (all-minilm) | Customer VM | Local embedding generation |
| Caddy | Customer VM | HTTPS reverse proxy, auto-cert |
| SaulLM-54B AWQ | RunPod GPU pod | Contract drafting, risk analysis, Q&A |
| OnlyOffice | Customer VM (optional) | In-browser document editor |

### Tools you need on your laptop

| Tool | Install | Purpose |
|------|---------|---------|
| `gcloud` | `brew install google-cloud-sdk` | GCP VM management |
| `sops` | `brew install sops` | Encrypt/decrypt customer secrets |
| `runpodctl` | `brew install runpod/runpodctl/runpodctl` | GPU pod management |
| `gh` | `brew install gh` | GitHub CLI (check CI status) |

---

## 1. Infrastructure Setup

### Create a GCP VM

```bash
# Choose machine type based on firm size:
#   10 users:  e2-standard-2 (2 vCPU, 8 GB RAM)  — $49/mo
#   30 users:  e2-standard-4 (4 vCPU, 16 GB RAM)  — $97/mo
#   50+ users: e2-standard-8 (8 vCPU, 32 GB RAM)  — $194/mo

gcloud compute instances create acme-legal-vm \
  --zone=us-central1-a \
  --machine-type=e2-standard-2 \
  --boot-disk-size=100GB \
  --boot-disk-type=pd-ssd \
  --image-family=ubuntu-2204-lts \
  --image-project=ubuntu-os-cloud \
  --tags=http-server,https-server
```

### Open firewall ports

```bash
# HTTP (Caddy ACME challenge) — may already exist
gcloud compute firewall-rules create allow-http \
  --allow=tcp:80 --source-ranges=0.0.0.0/0 2>/dev/null || true

# HTTPS (Caddy serves the app)
gcloud compute firewall-rules create allow-https \
  --allow=tcp:443 --source-ranges=0.0.0.0/0 2>/dev/null || true

# Backend direct access (optional, for debugging)
gcloud compute firewall-rules create allow-8080 \
  --allow=tcp:8080 --source-ranges=0.0.0.0/0 2>/dev/null || true
```

### Get the VM's external IP

```bash
gcloud compute instances describe acme-legal-vm \
  --zone=us-central1-a \
  --format='get(networkInterfaces[0].accessConfigs[0].natIP)'
# Example output: 34.9.252.242
```

Save this IP — you'll need it for DNS and config.

---

## 2. Customer Configuration

### Initialize customer directory

```bash
cd legal-partner
bash deploy/lp init acme-legal
```

This prompts for:
- **SSH host**: the VM's external IP (e.g., `34.9.252.242`)
- **SSH user**: `jyoti0512shukla` (your GCP username)
- **Domain**: `acme.cognita-ai.com`

It creates:
```
deploy/customers/acme-legal/
  .env            # Config template (edit next)
  metadata.sh     # SSH connection details
```

### For GCP VMs, update metadata.sh

```bash
cat >> deploy/customers/acme-legal/metadata.sh << 'EOF'
GCP_VM="acme-legal-vm"
GCP_ZONE="us-central1-a"
EOF
```

### Edit the customer config

```bash
vi deploy/customers/acme-legal/.env
```

**Mandatory fields:**

```bash
# ── URLs ─────────────────────────────────────────────────────────
LEGALPARTNER_APP_URL=https://acme.cognita-ai.com
LEGALPARTNER_CLOUD_FRONTEND_URL=https://acme.cognita-ai.com
LEGALPARTNER_CLOUD_BACKEND_URL=https://acme.cognita-ai.com

# ── Organization ──────────────────────────────────────────────────
LEGALPARTNER_ORGANIZATION_NAME=Acme Legal LLP

# ── LLM ──────────────────────────────────────────────────────────
LEGALPARTNER_CHAT_PROVIDER=vllm
LEGALPARTNER_CHAT_API_URL=https://xxx.ngrok-free.dev/v1    # GPU endpoint (set after GPU launch)
LEGALPARTNER_CHAT_API_MODEL=saullm-54b
LEGALPARTNER_GEMINI_API_KEY=AIza...                         # fallback + embedding support
LEGALPARTNER_GEMINI_MODEL=gemini-2.5-flash
```

**Auto-generated (already filled):** `DB_PASSWORD`, `JWT_SECRET`, `ENCRYPTION_KEY`

**Optional — enable as needed:**

```bash
# Email notifications
LEGALPARTNER_MAIL_ENABLED=true
SMTP_HOST=smtp.resend.com
SMTP_PORT=465
SMTP_USERNAME=resend
SMTP_PASSWORD=re_xxx

# Document editor
COMPOSE_PROFILES=onlyoffice
LEGALPARTNER_ONLYOFFICE_URL=http://<VM-IP>:8443
```

### Create the Caddyfile

```bash
cat > deploy/customers/acme-legal/Caddyfile << 'EOF'
acme.cognita-ai.com {
    reverse_proxy /api/* localhost:8080
    reverse_proxy /* localhost:3000
}
EOF
```

---

## 3. Secret Encryption

Customer configs contain secrets (DB passwords, API keys). We encrypt them with SOPS + age so they're safe to commit to git.

### First time: encrypt the config

```bash
# Encrypt .env → .env.sops
bash deploy/lp encrypt acme-legal
```

This requires the customer's age public key in `deploy/.sops.yaml`. If provisioning hasn't happened yet, do step 4 first, then come back and encrypt.

### Commit to git

```bash
git add deploy/customers/acme-legal/.env.sops \
        deploy/customers/acme-legal/metadata.sh \
        deploy/customers/acme-legal/Caddyfile \
        deploy/.sops.yaml
git commit -m "feat: add acme-legal customer config"
git push
```

### Later: editing secrets

```bash
bash deploy/lp decrypt acme-legal       # .env.sops → .env (plaintext, local)
vi deploy/customers/acme-legal/.env     # edit
bash deploy/lp encrypt acme-legal       # .env → .env.sops (re-encrypt)
git add -A && git commit -m "update acme config" && git push
```

---

## 4. Provisioning the VM

```bash
bash deploy/lp provision acme-legal
```

This SSHs into the VM and installs:
- **Docker** + Docker Compose
- **age** (encryption) + **sops** (secret management)
- **Caddy** (HTTPS reverse proxy)
- Generates an **age key pair** on the VM (private key never leaves)
- Logs into **ghcr.io** for pulling private Docker images

**Important output:**

```
  Age public key for acme-legal:
  age1ql3z7hjy54pw3hyww5ayfxrsh...

  Add this key to deploy/.sops.yaml under the acme-legal section.
```

Copy the public key and add it to `deploy/.sops.yaml`:

```yaml
creation_rules:
  - path_regex: customers/acme-legal/\.env\.sops$
    age: >-
      age1ql3z7hjy54pw3hyww5ayfxrsh...
```

Now go back to step 3 and encrypt the config.

---

## 5. DNS & HTTPS

### Add DNS A record

In your domain registrar (wherever you bought `cognita-ai.com`):

| Type | Host | Value | TTL |
|------|------|-------|-----|
| A | acme | `<VM-EXTERNAL-IP>` | 300 |

### Verify DNS propagation

```bash
dig +short acme.cognita-ai.com
# Should return: <VM-EXTERNAL-IP>
```

DNS typically propagates within 2-5 minutes. Caddy will auto-obtain a Let's Encrypt certificate on the first request after DNS resolves.

---

## 6. First Deploy

```bash
bash deploy/lp deploy acme-legal
```

This will:
1. Show config diff (first deploy = all new)
2. Push `docker-compose.yml` + encrypted `.env.sops` to VM
3. Decrypt `.env.sops` → `.env` using the VM's age key
4. Push Caddyfile → reload Caddy
5. Pull Docker images from ghcr.io
6. Start all services
7. Wait for backend health check
8. Print status

**Expected output:**

```
Deploying acme-legal (jyoti0512shukla@34.9.252.242)...
  Config changes:
    (first deploy — no previous snapshot)
  Pushing compose + config...
  Pushing encrypted config (.env.sops)...
  Decrypted on VM.
  Pushing Caddyfile...
  Pulling images...
  Starting services...
  Waiting for backend health... ✓
  Deployed acme-legal successfully!

  Backend: healthy
  Frontend: healthy (port 3000)
```

### First-time startup

The first deploy takes longer (~3-5 minutes) because:
- PostgreSQL initializes the database
- Flyway runs all migrations (V1 through V27)
- Ollama downloads the `all-minilm` embedding model (~25 MB)
- Backend indexes any seeded data

Subsequent deploys are faster (~1-2 minutes).

---

## 7. GPU Setup (SaulLM-54B)

The LLM runs on a separate RunPod GPU pod. Required for contract drafting, risk assessment, and Q&A.

### Launch the GPU

```bash
export HF_TOKEN=hf_xxx          # your HuggingFace token (write access)
export NGROK_TOKEN=xxx           # your ngrok auth token
./deploy/saullm-vllm-serve.sh
```

**Default GPU: A6000 48 GB** ($0.76/hr). Override with:
```bash
GPU_TYPE="NVIDIA A100-SXM4-80GB" ./deploy/saullm-vllm-serve.sh
```

### What it does

1. Creates a RunPod pod with the specified GPU
2. Installs vLLM
3. Downloads SaulLM-54B-AWQ from HuggingFace (~23 GB)
4. Starts vLLM server with OpenAI-compatible API on port 8000
5. Creates ngrok tunnel for public HTTPS access
6. Prints the public URL

### After launch

Copy the ngrok URL and update the customer's config:

```bash
LEGALPARTNER_CHAT_API_URL=https://circulable-chere-lucidly.ngrok-free.dev/v1
```

Then re-encrypt and redeploy:
```bash
bash deploy/lp encrypt acme-legal
bash deploy/lp deploy acme-legal
```

### GPU management

```bash
runpodctl get pod                          # list all pods
runpodctl pod stop <POD_ID>                # pause (keeps data, ~$0.10/hr)
runpodctl pod delete <POD_ID>              # full cleanup ($0)
```

### vLLM configuration

Key flags in `saullm-vllm-serve.sh`:

| Flag | Value | Purpose |
|------|-------|---------|
| `--quantization awq` | AWQ W4A16 | 4-bit weights, 16-bit activations |
| `--dtype float16` | fp16 | Required for AWQ (not bfloat16) |
| `--max-model-len 8192` | 8K context | Balanced for VRAM headroom |
| `--gpu-memory-utilization 0.92` | 92% | Leaves 4 GB for KV cache overflow |
| `--served-model-name saullm-54b` | Model alias | Referenced by backend config |

---

## 8. DocuSign Integration

### Create DocuSign developer app

1. Go to **developers.docusign.com** → sign up (free)
2. **Settings → Apps and Keys → Add App**
3. Note the **Integration Key** (client ID)
4. Click **Add Secret Key** (copy immediately — shown once)
5. Under **Redirect URIs**, add:
   ```
   https://acme.cognita-ai.com/api/v1/integrations/callback
   ```

### Add to customer config

```bash
LEGALPARTNER_DOCUSIGN_ENABLED=true
LEGALPARTNER_DOCUSIGN_CLIENT_ID=8b41e397-xxxx-xxxx-xxxx-xxxxxxxxxxxx
LEGALPARTNER_DOCUSIGN_CLIENT_SECRET=62b49634-xxxx-xxxx-xxxx-xxxxxxxxxxxx
LEGALPARTNER_DOCUSIGN_ENV=demo        # demo for testing, production for live
```

Re-encrypt and deploy:
```bash
bash deploy/lp encrypt acme-legal && bash deploy/lp deploy acme-legal
```

### Connect in the app

1. Login as admin at `https://acme.cognita-ai.com`
2. **Settings → Integrations → DocuSign → Connect**
3. Authorize with DocuSign credentials
4. Connection is org-scoped — all firm users can now send for signature

### How signing works

1. Any user opens a contract → clicks **Send for Signature**
2. Adds signers (Party A, Party B) + CC recipients with routing order
3. DocuSign sends email to each recipient in sequence
4. When all sign → webhook notifies ContractIQ → signed PDF stored
5. Document status updated to "Signed"

### DocuSign webhook (optional, for status tracking)

Configure DocuSign Connect in the developer dashboard:
- **URL**: `https://acme.cognita-ai.com/api/v1/integrations/docusign/webhook`
- **Events**: Envelope Sent, Delivered, Completed, Declined, Voided

---

## 9. Google Drive Integration

### Create Google OAuth app

1. Go to **console.cloud.google.com** → APIs & Services → Credentials
2. Create OAuth 2.0 Client ID (Web application)
3. Add redirect URI: `https://acme.cognita-ai.com/api/v1/cloud-storage/callback`
4. Enable the Google Drive API

### Add to config

```bash
LEGALPARTNER_GOOGLE_DRIVE_ENABLED=true
LEGALPARTNER_GOOGLE_DRIVE_CLIENT_ID=722150835225-xxx.apps.googleusercontent.com
LEGALPARTNER_GOOGLE_DRIVE_CLIENT_SECRET=GOCSPX-xxx
```

---

## 10. Post-Deploy Verification

### Checklist

```bash
# 1. HTTPS working?
curl -sI https://acme.cognita-ai.com | head -3
# HTTP/2 200

# 2. Backend healthy?
curl -s https://acme.cognita-ai.com/api/v1/actuator/health
# {"status":"UP"}

# 3. Login works?
curl -s https://acme.cognita-ai.com/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@legalpartner.local","password":"Admin123!"}' | python3 -c 'import json,sys; print("OK" if json.load(sys.stdin).get("token") else "FAIL")'

# 4. GPU connected? (only if GPU is running)
curl -s https://acme.cognita-ai.com/api/v1/ai/drafts | head -1
# Should return JSON, not a connection error

# 5. Caddy cert valid?
echo | openssl s_client -connect acme.cognita-ai.com:443 -servername acme.cognita-ai.com 2>/dev/null | openssl x509 -noout -dates
```

### Default admin credentials

| Field | Value |
|-------|-------|
| Email | `admin@legalpartner.local` |
| Password | `Admin123!` |

**Change this immediately** after first login via Settings → Account.

---

## 11. Ongoing Operations

### Daily commands

```bash
lp status acme-legal             # health check
lp logs acme-legal backend       # tail backend logs
lp logs acme-legal postgres      # database logs
```

### Routine maintenance

| Task | Frequency | Command |
|------|-----------|---------|
| Check status | Daily (or use UptimeRobot) | `lp status acme-legal` |
| Database backup | Weekly | `lp backup acme-legal` |
| Review logs for errors | Weekly | `lp logs acme-legal backend` |
| Update to latest | As needed | `lp deploy acme-legal` |
| Rotate JWT secret | Annually | Edit `.env`, re-encrypt, deploy |

### Setting up uptime monitoring

1. Sign up at [UptimeRobot](https://uptimerobot.com) (free — 50 monitors)
2. Add HTTP monitor: `https://acme.cognita-ai.com/api/v1/actuator/health`
3. Check interval: 5 minutes
4. Alert contacts: email + Slack

---

## 12. Updating & Rollback

### Deploy latest code

Push to `main` → GitHub Actions builds images → deploy:

```bash
git push origin main
# Wait 3-4 min for CI
lp deploy acme-legal
```

### Check what changed before deploying

```bash
lp diff acme-legal              # no VM needed
```

### Pin a specific version

```bash
lp deploy acme-legal abc1234    # deploy specific git SHA
```

### Rollback

```bash
# Find previous version
gh run list --limit 10

# Rollback
lp rollback acme-legal v2026.04.25-3
```

### Roll forward

```bash
lp deploy acme-legal latest
```

---

## 13. Backup & Recovery

### Automated disk snapshots (recommended)

```bash
# Create daily snapshot schedule — runs at 2 AM, keeps 7 days
gcloud compute resource-policies create snapshot-schedule lp-daily-backup \
  --region=us-central1 \
  --max-retention-days=7 \
  --daily-schedule \
  --start-time=02:00

# Attach to the VM's disk
gcloud compute disks add-resource-policies acme-legal-vm \
  --resource-policies=lp-daily-backup \
  --zone=us-central1-a
```

Captures everything: database, documents, Docker volumes, config. Incremental — only changes stored.

### Manual database backup

```bash
lp backup acme-legal
# Saves: deploy/customers/acme-legal/legalpartner-acme-legal-YYYYMMDD-HHMMSS.sql.gz
```

### Restore from snapshot (disaster recovery)

If the VM dies completely:

```bash
# 1. Find latest snapshot
gcloud compute snapshots list --filter="sourceDisk~acme-legal"

# 2. Create new disk from snapshot
gcloud compute disks create acme-restored \
  --source-snapshot=<SNAPSHOT-NAME> \
  --zone=us-central1-a

# 3. Create new VM with the restored disk
gcloud compute instances create acme-legal-vm-restored \
  --disk=name=acme-restored,boot=yes \
  --zone=us-central1-a \
  --machine-type=e2-standard-2

# 4. Docker starts automatically with all data
# 5. Update DNS A record to new VM IP
# 6. Caddy auto-obtains new cert
```

**Recovery time: ~30 minutes. Data loss: up to 24 hours (last snapshot).**

### Restore from database backup

```bash
lp ssh acme-legal
cd /opt/legalpartner
gunzip -c /path/to/backup.sql.gz | docker-compose exec -T postgres psql -U legalpartner legalpartner
```

---

## 14. Multi-Customer Management

### Deploy to all customers at once

```bash
lp deploy-all
```

### Pin all customers to a specific version (e.g., before a release)

```bash
lp deploy-all v2026.04.25-3
```

### List all customers

```bash
lp list
# test-vm    →  jyoti0512shukla@34.9.252.242  (legal.cognita-ai.com)
# acme-legal →  deploy@35.1.2.3               (acme.cognita-ai.com)
```

### Status of all customers

```bash
lp status-all
```

### Shared GPU across customers

Multiple customers can share one GPU pod. The ngrok URL is the same for all — just set the same `LEGALPARTNER_CHAT_API_URL` in each customer's config.

---

## 15. Troubleshooting

### Common issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| Blank page after deploy | Backend still starting (30-60s) | Wait and refresh |
| 502 Bad Gateway | Backend not ready or crashed | `lp logs acme-legal backend` |
| Images not pulling | ghcr.io auth expired | `lp provision acme-legal` |
| SOPS decrypt fails on deploy | `.age-key` missing on VM | `lp ssh acme-legal` → check `/opt/legalpartner/.age-key` |
| Caddy cert error | DNS not resolving or port 80/443 blocked | `dig acme.cognita-ai.com` + check firewall |
| DocuSign "redirect URI not registered" | Exact URL mismatch | Copy from browser → paste in DocuSign dashboard |
| DocuSign 400 on callback | Pipe char in state (old bug) | Redeploy latest code |
| LLM timeout / connection refused | GPU pod not running or ngrok down | Check `runpodctl get pod` |
| Risk assessment returns empty | Document not indexed yet | Wait for INDEXED status |
| Draft generation hangs | GPU memory full / vLLM crashed | `lp ssh` into pod, check `/workspace/vllm.log` |
| Database migration fails | Flyway error | `lp logs acme-legal backend` → fix migration |
| VM out of memory | Too many concurrent ops | Upgrade to bigger machine type |
| Disk full | Too many Docker images | `docker image prune -af` on VM |

### Useful debug commands

```bash
# Backend logs (last 100 lines)
lp logs acme-legal backend 2>&1 | tail -100

# Check all container health
lp ssh acme-legal
docker ps --format "table {{.Names}}\t{{.Status}}"

# Check disk usage
df -h

# Check memory
free -h

# Check Caddy status
sudo systemctl status caddy
sudo journalctl -u caddy --since "10 min ago"

# Test GPU connection from backend
curl -s http://localhost:8080/api/v1/ai/drafts
```

---

## 16. Cost Reference

### Per-customer (private deployment, 24/7 GPU)

| Component | Spec | Monthly |
|-----------|------|---------|
| App VM | e2-standard-2 (8 GB, 100 GB SSD) | $55 |
| GPU | RunPod A6000 48 GB, 24/7 | $547 |
| Domain | cognita-ai.com subdomain | Included |
| HTTPS | Let's Encrypt via Caddy | Free |
| **Total** | | **$602** |

### Multi-tenant (shared infra, 5 firms / 50 users)

| Component | Spec | Monthly |
|-----------|------|---------|
| App VM | e2-standard-8 (32 GB, 500 GB SSD) | $224 |
| GPU | RunPod A6000 48 GB (shared) | $547 |
| **Total** | | **$771 ($154/firm)** |

### GPU cost optimization

| Strategy | Monthly GPU cost | Savings |
|----------|-----------------|---------|
| Always on (24/7) | $547 | Baseline |
| Business hours (12hr × 30 days) | $274 | 50% |
| Weekdays only (10hr × 22 days) | $167 | 70% |
| RunPod Serverless (pay per request) | ~$50-100 | 80-90% |

---

## Appendix: Complete lp CLI Reference

```
lp — ContractIQ deployment CLI

Usage: lp <command> [args]

Commands:
  init <customer>           Create customer config from template
  provision <customer>      Install Docker + age key + Caddy on VM
  deploy <customer> [tag]   Push config, decrypt on VM, start services
  deploy-all [tag]          Deploy to all customers
  encrypt <customer>        Encrypt .env → .env.sops (for git)
  decrypt <customer>        Decrypt .env.sops → .env (local editing)
  diff <customer>           Show config changes since last deploy
  rollback <customer> <tag> Pin image tag and redeploy
  status <customer>         Show service status and health
  status-all                Status of all customers
  logs <customer> [service] Tail logs (e.g., lp logs acme backend)
  backup <customer>         Dump database to local file
  ssh <customer>            SSH into customer VM
  list                      List all customers
```
