# Legal Partner — Deployment & Operations Guide

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
Operator runs: bash deploy/lp deploy <customer>
        ↓
Customer VM pulls images, restarts services
```

**No source code on production VMs.** Each VM has only a `docker-compose.yml` and `.env` file at `/opt/legalpartner/`.

---

## Prerequisites

Before any deployment, ensure you have:

1. **gcloud CLI** installed and authenticated (`gcloud auth login`)
2. **ghcr.io token** saved at `deploy/.ghcr-token` (one-time setup):
   - GitHub → Settings → Developer settings → Personal access tokens → Fine-grained
   - Repository: `legal-partner`, Permission: Packages → Read
   - Save: `echo "github_pat_xxx..." > deploy/.ghcr-token`
3. **SSH access** to the customer VM (via gcloud for GCP VMs, or direct SSH key)

---

## New Customer Deployment

### Step 1: Create customer config

```bash
bash deploy/lp init acme-legal
```

This will:
- Create `deploy/customers/acme-legal/` directory
- Copy `.env.template` to `.env`
- Auto-generate DB_PASSWORD, JWT_SECRET, ENCRYPTION_KEY
- Prompt for SSH host, user, and domain

### Step 2: Edit the config

```bash
vi deploy/customers/acme-legal/.env
```

**Required — fill these in:**

| Variable | What to set |
|----------|-------------|
| `LEGALPARTNER_APP_URL` | Customer's URL, e.g., `http://acme.legal-ai.studio` |
| `LEGALPARTNER_CLOUD_FRONTEND_URL` | Same as above |
| `LEGALPARTNER_CLOUD_BACKEND_URL` | Same + `/api`, e.g., `http://acme.legal-ai.studio/api` |
| `LEGALPARTNER_CHAT_PROVIDER` | `gemini` or `vllm` |
| `LEGALPARTNER_GEMINI_API_KEY` | Gemini API key (if using gemini) |

**Optional — configure if needed:**

| Variable | Purpose |
|----------|---------|
| `FRONTEND_PORT` | Default 80. Set to 3000 if 80 is taken |
| `LEGALPARTNER_MAIL_ENABLED` | Set `true` + fill SMTP vars to enable email |
| `COMPOSE_PROFILES=onlyoffice` | Enable ONLYOFFICE document editor |
| `LEGALPARTNER_ONLYOFFICE_URL` | `http://<VM-IP>:8443` (if OnlyOffice enabled) |
| Google Drive / OneDrive / Dropbox vars | Enable cloud storage integrations |

**For GCP VMs**, edit `metadata.sh` to add:
```bash
GCP_VM="your-vm-name"
GCP_ZONE="us-central1-a"
```

### Step 3: Provision the VM

```bash
bash deploy/lp provision acme-legal
```

This will SSH into the VM and:
- Install Docker (if not present)
- Create `/opt/legalpartner/` directory
- Log in to ghcr.io (so the VM can pull private images)

**One-time per VM.** You don't need to run this again unless the VM is recreated.

### Step 4: Deploy

```bash
bash deploy/lp deploy acme-legal
```

This will:
1. Push `docker-compose.yml` and `.env` to the VM
2. Pull latest Docker images from ghcr.io
3. Start all services (postgres, ollama, backend, frontend, onlyoffice)
4. Wait for backend health check
5. Print status

### Step 5: Verify

- Open `http://<domain>:<port>` in browser
- Default admin login: `admin@legalpartner.local` / `Admin123!`
- **Note:** Backend takes 30-60 seconds to start. If you see a blank page, wait and refresh.

---

## Existing Customer — Deploying Updates

After pushing code to `main`, GitHub Actions automatically builds new images. To deploy:

### Deploy to one customer

```bash
bash deploy/lp deploy acme-legal
```

### Deploy to all customers

```bash
bash deploy/lp deploy-all
```

### Deploy a specific version

```bash
# Find the SHA from git log or GitHub Actions
bash deploy/lp deploy acme-legal abc1234
```

### Update customer config (e.g., enable a new integration)

```bash
# 1. Edit the config locally
vi deploy/customers/acme-legal/.env

# 2. Push config and restart
bash deploy/lp deploy acme-legal
```

---

## Rollback

Every push to `main` creates tagged images. To roll back:

### Find the version to roll back to

```bash
# Check git log for the commit SHA before the bad change
git log --oneline -10

# Or check GitHub Actions for image tags
# Each run shows the SHA tag it pushed
```

### Roll back

```bash
bash deploy/lp rollback acme-legal abc1234
```

This pins `IMAGE_TAG=abc1234` in the customer's `.env` and redeploys. The VM will pull the specific version instead of `:latest`.

### Roll forward (back to latest)

```bash
bash deploy/lp deploy acme-legal latest
```

Or edit `.env` and set `IMAGE_TAG=latest`, then deploy.

---

## Day-to-Day Operations

### Check status

```bash
bash deploy/lp status acme-legal      # one customer
bash deploy/lp status-all             # all customers
```

Shows container status and health checks for backend + frontend.

### View logs

```bash
bash deploy/lp logs acme-legal            # all services
bash deploy/lp logs acme-legal backend    # backend only
bash deploy/lp logs acme-legal frontend   # nginx logs
bash deploy/lp logs acme-legal postgres   # database logs
```

### SSH into VM

```bash
bash deploy/lp ssh acme-legal
```

### Backup database

```bash
bash deploy/lp backup acme-legal
# Saves to: deploy/customers/acme-legal/legalpartner-acme-legal-20260329-143022.sql.gz
```

### Restore database

```bash
bash deploy/lp ssh acme-legal
cd /opt/legalpartner
gunzip -c /path/to/backup.sql.gz | docker-compose exec -T postgres psql -U legalpartner legalpartner
```

### List all customers

```bash
bash deploy/lp list
```

---

## Directory Layout

### On your laptop (operator)

```
deploy/
  docker-compose.yml      # Universal prod compose (same for all customers)
  .env.template           # Config reference with all variables documented
  .ghcr-token             # GitHub PAT for pulling private images (gitignored)
  lp                      # CLI tool
  README.md               # This file
  customers/              # Per-customer configs (gitignored, never committed)
    acme-legal/
      .env                # Secrets + config
      metadata.sh         # SSH host, user, GCP VM details
      *.sql.gz            # Database backups (from lp backup)
    sharma-law/
      .env
      metadata.sh
```

### On each customer VM

```
/opt/legalpartner/
  docker-compose.yml      # Pushed by lp deploy
  .env                    # Pushed by lp deploy
```

Docker volumes (persistent data):
- `pgdata` — PostgreSQL database
- `ollama_models` — LLM model weights (auto-pulled on first start)
- `document_storage` — uploaded contract files
- `onlyoffice_data` — editor config (if enabled)

---

## Image Tags

Every push to `main` produces three tags per image:

| Tag | Example | Use |
|-----|---------|-----|
| `:latest` | `legal-partner-backend:latest` | Normal deploys |
| `:<sha>` | `legal-partner-backend:abc1234` | Precise rollback |
| `:<date>` | `legal-partner-backend:20260329-143022` | Human reference |

Images are stored at `ghcr.io/jyoti0512shukla/legal-partner-backend` and `legal-partner-frontend`.

---

## Enabling OnlyOffice (Document Editor)

Add to customer's `.env`:

```bash
COMPOSE_PROFILES=onlyoffice
LEGALPARTNER_ONLYOFFICE_URL=http://<VM-IP>:8443
```

Then redeploy: `bash deploy/lp deploy acme-legal`

---

## Troubleshooting

### Blank page after deploy
Backend takes 30-60s to start. Frontend returns 502 on API calls until backend is healthy. Wait and refresh.

### Images not pulling
Run `bash deploy/lp provision <customer>` to re-authenticate with ghcr.io.

### Database migration fails
Backend logs will show Flyway errors. Check: `bash deploy/lp logs <customer> backend`
Production compose has `FLYWAY_CLEAN_DISABLED=true` — it will never drop your database. Fix the migration and redeploy.

### Port conflicts
If port 80 or 3000 is taken, set `FRONTEND_PORT=<other>` in `.env`.
If port 8443 is taken (OnlyOffice), stop the conflicting service first.

### VM ran out of memory
Ollama reserves 4GB. If the VM has <8GB RAM, reduce in compose or use external LLM via `LEGALPARTNER_CHAT_PROVIDER=gemini`.
