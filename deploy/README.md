# Legal Partner — Deployment Guide

## How It Works

1. **Push to `main`** → GitHub Actions builds Docker images → pushes to Docker Hub
2. **Operator runs `lp deploy <customer>`** → pushes config to VM, pulls images, restarts services
3. **Customer VM** has no source code — just Docker, a compose file, and an `.env`

## Quick Start

### Onboard a new customer

```bash
# 1. Create customer config (generates secrets, prompts for SSH details)
./deploy/lp init sharma-law

# 2. Edit the .env to fill in LLM keys, SMTP, domain, etc.
vi deploy/customers/sharma-law/.env

# 3. Install Docker on the VM (skip if already installed)
./deploy/lp provision sharma-law

# 4. Deploy
./deploy/lp deploy sharma-law
```

### Day-to-day operations

```bash
# Deploy latest to one customer
./deploy/lp deploy sharma-law

# Deploy latest to all customers
./deploy/lp deploy-all

# Deploy a specific version (by git SHA)
./deploy/lp deploy sharma-law abc1234

# Rollback to a previous version
./deploy/lp rollback sharma-law def5678

# Check status
./deploy/lp status sharma-law
./deploy/lp status-all

# View logs
./deploy/lp logs sharma-law            # all services
./deploy/lp logs sharma-law backend    # backend only

# Backup database
./deploy/lp backup sharma-law

# SSH into VM
./deploy/lp ssh sharma-law

# List all customers
./deploy/lp list
```

## Directory Structure

```
deploy/
  docker-compose.yml      # Universal production compose (same for all customers)
  .env.template           # Config template with all variables documented
  lp                      # CLI tool
  README.md               # This file
  customers/              # Per-customer configs (gitignored)
    sharma-law/
      .env                # Customer secrets and config
      metadata.sh         # SSH connection details
    khaitan-co/
      .env
      metadata.sh
```

## What Lives on Each Customer VM

```
/opt/legalpartner/
  docker-compose.yml      # Pushed by lp deploy
  .env                    # Pushed by lp deploy
```

Data volumes (managed by Docker):
- `pgdata` — PostgreSQL database
- `ollama_models` — LLM model weights
- `document_storage` — uploaded contract files
- `onlyoffice_data` — editor config (if enabled)

## Image Tags

Every push to `main` produces three tags:
- `:latest` — always the newest build
- `:<sha>` — 7-char git commit hash (e.g., `abc1234`) for precise rollback
- `:<date>` — timestamp (e.g., `20260329-143022`) for human reference

## Enabling OnlyOffice

Add to customer's `.env`:
```
COMPOSE_PROFILES=onlyoffice
LEGALPARTNER_ONLYOFFICE_URL=http://<VM-IP>:8443
```

## Backup & Restore

```bash
# Backup
./deploy/lp backup sharma-law
# → saves to deploy/customers/sharma-law/legalpartner-sharma-law-20260329-143022.sql.gz

# Restore (manual)
./deploy/lp ssh sharma-law
cd /opt/legalpartner
gunzip -c /path/to/backup.sql.gz | docker compose exec -T postgres psql -U legalpartner legalpartner
```
