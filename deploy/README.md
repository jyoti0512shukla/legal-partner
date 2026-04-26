# ContractIQ — Deployment & Operations Guide

## Architecture

```
Developer pushes to main
        ↓
GitHub Actions builds Docker images
        ↓
Images pushed to ghcr.io/jyoti0512shukla/
  - legal-partner-backend:latest, :SHA, :DATE
  - legal-partner-frontend:latest, :SHA, :DATE
        ↓
Operator runs: lp deploy <customer>
        ↓
Encrypted config (.env.sops) pushed to VM
        ↓
VM decrypts with age key, pulls images, restarts services
        ↓
Caddy serves HTTPS with auto-renewed Let's Encrypt cert
```

**No source code on production VMs.** Each VM has only `docker-compose.yml`, `.env`, and an age decryption key at `/opt/legalpartner/`.

---

## Directory Layout

### In git (committed)

```
deploy/
  docker-compose.yml          # Universal prod compose (same for all customers)
  .env.template               # Config reference with all variables documented
  .sops.yaml                  # SOPS config — maps customers to their age public keys
  lp                          # CLI tool
  README.md                   # This file
  saullm-vllm-serve.sh        # GPU pod launcher (SaulLM-54B AWQ on RunPod)
  customers/
    test-vm/
      .env.sops               # Encrypted secrets + config (safe in git)
      metadata.sh             # SSH host, user, GCP VM details
      Caddyfile               # HTTPS reverse proxy config
    firm-a/
      .env.sops
      metadata.sh
      Caddyfile
```

### On each customer VM

```
/opt/legalpartner/
  docker-compose.yml          # Pushed by lp deploy
  .env                        # Decrypted from .env.sops during deploy
  .age-key                    # Private key (generated on VM, never leaves)
```

Docker volumes (persistent):
- `pgdata` — PostgreSQL database + pgvector embeddings
- `ollama_models` — Embedding model (all-minilm, auto-pulled)
- `document_storage` — Uploaded contracts, drafts, signed PDFs
- `onlyoffice_data` — Document editor config (if enabled)

---

## Prerequisites

1. **gcloud CLI** — `gcloud auth login`
2. **sops** — `brew install sops` (for encrypting customer configs)
3. **ghcr.io token** — `echo "github_pat_xxx" > deploy/.ghcr-token`

---

## Secret Management (SOPS + age)

Secrets are encrypted with [SOPS](https://github.com/getsops/sops) + [age](https://github.com/FiloSottile/age). Each customer VM has a unique age private key that never leaves the VM.

### How it works

```
Your machine                              Customer VM
────────────                              ────────────
.env.sops (encrypted, in git)
        │
        │ lp deploy
        │ scp .env.sops → VM
        ▼
                                    age private key at /opt/legalpartner/.age-key
                                            │
                                    sops decrypt .env.sops → .env
                                            │
                                    docker-compose reads .env
```

### Commands

| Command | What it does | When to use |
|---------|-------------|-------------|
| `lp encrypt <customer>` | `.env` → `.env.sops` | After editing config locally |
| `lp decrypt <customer>` | `.env.sops` → `.env` | To edit config locally |
| `lp deploy <customer>` | Pushes `.env.sops`, decrypts on VM | Every deploy |

### Adding a new customer's key to SOPS

After `lp provision`, the VM's public key is printed. Add it to `deploy/.sops.yaml`:

```yaml
creation_rules:
  - path_regex: customers/firm-a/\.env\.sops$
    age: >-
      age1ql3z7hjy54pw3hyww5ayf...   # from lp provision output
```

---

## New Customer Deployment

### Step 1: Create customer config

```bash
lp init acme-legal
```

Creates `deploy/customers/acme-legal/` with `.env` template, `metadata.sh`, prompts for SSH details.

### Step 2: Edit the config

```bash
vi deploy/customers/acme-legal/.env
```

**Required:**

| Variable | Example |
|----------|---------|
| `LEGALPARTNER_APP_URL` | `https://legal.cognita-ai.com` |
| `LEGALPARTNER_CLOUD_FRONTEND_URL` | `https://legal.cognita-ai.com` |
| `LEGALPARTNER_CLOUD_BACKEND_URL` | `https://legal.cognita-ai.com` |
| `LEGALPARTNER_ORGANIZATION_NAME` | `Sharma & Associates` |
| `LEGALPARTNER_CHAT_PROVIDER` | `vllm` |
| `LEGALPARTNER_CHAT_API_URL` | `https://xxx.ngrok-free.dev/v1` (GPU endpoint) |
| `LEGALPARTNER_CHAT_API_MODEL` | `saullm-54b` |
| `LEGALPARTNER_GEMINI_API_KEY` | Gemini key (fallback / embeddings) |

**Auto-generated during init:** `DB_PASSWORD`, `JWT_SECRET`, `ENCRYPTION_KEY`

### Step 3: Create Caddyfile

```bash
cat > deploy/customers/acme-legal/Caddyfile << 'EOF'
acme.cognita-ai.com {
    reverse_proxy /api/* localhost:8080
    reverse_proxy /* localhost:3000
}
EOF
```

### Step 4: Provision the VM

```bash
lp provision acme-legal
```

This will:
- Install Docker, age, sops, Caddy on the VM
- Generate age key pair (private stays on VM)
- Print the public key — **add it to `.sops.yaml`**
- Log in to ghcr.io

### Step 5: Encrypt & commit

```bash
lp encrypt acme-legal
git add deploy/customers/acme-legal/ deploy/.sops.yaml
git commit -m "feat: add acme-legal customer config"
git push
```

### Step 6: Deploy

```bash
lp deploy acme-legal
```

### Step 7: Add DNS

Point `acme.cognita-ai.com` → VM's external IP (A record). Caddy auto-obtains Let's Encrypt cert.

### Step 8: Verify

- Open `https://acme.cognita-ai.com`
- Default admin: `admin@legalpartner.local` / `Admin123!`

---

## Deploying Updates

### Deploy to one customer

```bash
lp deploy acme-legal
```

Shows config diff, pushes encrypted config, pulls latest images, restarts.

### Deploy to all customers

```bash
lp deploy-all
```

### Deploy a specific version

```bash
lp deploy acme-legal abc1234    # pin to git SHA
lp deploy acme-legal latest     # back to latest
```

### Check what changed before deploying

```bash
lp diff acme-legal              # no VM needed, compares against last deploy
```

---

## GPU Setup (SaulLM-54B)

The LLM runs on a separate RunPod GPU pod, connected to the app VM via ngrok tunnel.

### Recommended GPU: A6000 48GB ($0.76/hr)

- SaulLM-54B AWQ: ~23 GB model + ~8 GB KV cache = ~31 GB VRAM
- ~35-40 tok/s with AWQ Marlin kernels
- Handles 5-10 concurrent requests

### Launch GPU

```bash
export HF_TOKEN=hf_xxx NGROK_TOKEN=xxx
./deploy/saullm-vllm-serve.sh
```

Prints the ngrok URL — set it as `LEGALPARTNER_CHAT_API_URL` in customer config.

### Override GPU type

```bash
GPU_TYPE="NVIDIA A100-SXM4-80GB" ./deploy/saullm-vllm-serve.sh    # ~62 tok/s, $2.49/hr
```

### Stop/delete GPU

```bash
runpodctl pod stop <POD_ID>       # pause ($0.10/hr for disk)
runpodctl pod delete <POD_ID>     # full cleanup ($0)
```

---

## Integrations

### DocuSign (E-Signature)

**Setup:**
1. Create app at developers.docusign.com
2. Add redirect URI: `https://<customer-domain>/api/v1/integrations/callback`
3. Set in customer `.env`:
   ```
   LEGALPARTNER_DOCUSIGN_ENABLED=true
   LEGALPARTNER_DOCUSIGN_CLIENT_ID=<integration-key>
   LEGALPARTNER_DOCUSIGN_CLIENT_SECRET=<secret-key>
   LEGALPARTNER_DOCUSIGN_ENV=demo    # or production
   ```
4. Deploy, then connect in Settings → Integrations

**Features:**
- Organization-scoped: admin connects once, all firm users can send
- Multi-recipient: signers, reviewers, CC with routing order
- Webhook at `/api/v1/integrations/docusign/webhook` for status tracking
- Signed PDF auto-downloaded on completion

### Google Drive

```
LEGALPARTNER_GOOGLE_DRIVE_ENABLED=true
LEGALPARTNER_GOOGLE_DRIVE_CLIENT_ID=xxx
LEGALPARTNER_GOOGLE_DRIVE_CLIENT_SECRET=xxx
```

### OnlyOffice (Document Editor)

```
COMPOSE_PROFILES=onlyoffice
LEGALPARTNER_ONLYOFFICE_URL=http://<VM-IP>:8443
```

---

## Rollback

```bash
lp rollback acme-legal v2026.04.25-3
```

Pins `IMAGE_TAG` and redeploys. Roll forward with `lp deploy acme-legal latest`.

---

## Day-to-Day Operations

| Command | What |
|---------|------|
| `lp status acme-legal` | Container status + health checks |
| `lp status-all` | All customers |
| `lp logs acme-legal backend` | Tail backend logs |
| `lp backup acme-legal` | pg_dump to local file |
| `lp ssh acme-legal` | SSH into VM |
| `lp list` | List all customers |
| `lp diff acme-legal` | Config changes since last deploy |

---

## HTTPS (Caddy)

Each customer VM runs Caddy as a reverse proxy. Caddy auto-obtains and renews Let's Encrypt certificates.

```
Internet → :443 (Caddy, HTTPS) → /api/* → :8080 (backend)
                                → /*     → :3000 (frontend)
```

**Required:** DNS A record pointing the customer's subdomain to the VM IP. Port 80 + 443 open in firewall.

---

## Data Protection & Reliability

| Layer | What | Cost |
|-------|------|------|
| Container recovery | `restart: unless-stopped` | Free |
| VM reboot recovery | Docker auto-starts on boot | Free |
| Disk snapshots | GCP daily incremental, 7-day retention | Free (5GB) |
| Manual DB backup | `lp backup <customer>` | Free |
| Uptime monitoring | UptimeRobot, 5-min checks | Free |

### Setting up disk snapshots

```bash
gcloud compute resource-policies create snapshot-schedule lp-daily-backup \
  --region=us-central1 --max-retention-days=7 --daily-schedule --start-time=02:00

gcloud compute disks add-resource-policies <VM-NAME> \
  --resource-policies=lp-daily-backup --zone=us-central1-a
```

### Disaster recovery

```bash
gcloud compute snapshots list --filter="sourceDisk~<VM-NAME>"
gcloud compute disks create lp-restored --source-snapshot=<SNAPSHOT> --zone=us-central1-a
gcloud compute instances create <VM>-restored --disk=name=lp-restored,boot=yes --zone=us-central1-a
# Docker starts automatically, update DNS to new IP
```

---

## Cost Reference

### Per-customer (private deployment, 24/7)

| Component | Spec | Monthly |
|-----------|------|---------|
| App VM | e2-standard-2 (8 GB RAM, 100 GB SSD) | $55 |
| GPU | RunPod A6000 48 GB | $547 |
| **Total** | | **$602** |

### Multi-tenant (shared infra, 5 firms)

| Component | Spec | Monthly |
|-----------|------|---------|
| App VM | e2-standard-8 (32 GB RAM, 500 GB SSD) | $224 |
| GPU | RunPod A6000 48 GB (shared) | $547 |
| **Total** | | **$771 ($154/firm)** |

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Blank page after deploy | Backend takes 30-60s to start. Wait and refresh. |
| Images not pulling | `lp provision <customer>` to re-auth ghcr.io |
| SOPS decrypt fails | Check `.age-key` exists on VM: `lp ssh <customer>` then `ls /opt/legalpartner/.age-key` |
| Caddy cert fails | Ensure DNS resolves + ports 80/443 open in firewall |
| DocuSign "redirect URI not registered" | Exact match required in DocuSign dashboard: `https://<domain>/api/v1/integrations/callback` |
| GPU OOM | Reduce `--max-model-len` in saullm-vllm-serve.sh |
| DB migration fails | `lp logs <customer> backend` — Flyway errors shown there |
