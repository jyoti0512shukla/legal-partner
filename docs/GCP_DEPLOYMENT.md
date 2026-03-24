# Legal Partner — Deploy on GCP via CLI

Create and deploy Legal Partner on **e2-standard-4** (4 vCPU, 16 GB) using gcloud. Start/stop on demand to save costs.

---

## Record Session Output

**Option 1: `script` (records entire session)**

```bash
script deploy-log-$(date +%Y%m%d-%H%M).txt
# All commands and output are logged until you type 'exit'
```

**Option 2: `tee` (per-command, append to log)**

```bash
LOG=deploy-log.txt
gcloud compute instances create legal-partner-vm ... 2>&1 | tee -a $LOG
gcloud compute ssh legal-partner-vm ... 2>&1 | tee -a $LOG
```

**Option 3: Cloud Shell** — Download the log from Cloud Shell (⋮ menu → Download file) after your session.

---

## Prerequisites

- gcloud CLI installed and authenticated
- GCP project with billing enabled ($295 credit)
- SSH key (or let gcloud create one)

---

## Step 1: Set Project & Enable APIs

```bash
# Set your project
gcloud config set project YOUR_PROJECT_ID

# Enable Compute Engine API
gcloud services enable compute.googleapis.com
```

---

## Step 2: Create Firewall Rules (HTTP, 8080)

```bash
# Allow HTTP (for nginx/frontend)
gcloud compute firewall-rules create allow-http \
  --allow tcp:80 \
  --source-ranges 0.0.0.0/0 \
  --description "Allow HTTP"

# Allow backend API
gcloud compute firewall-rules create allow-8080 \
  --allow tcp:8080 \
  --source-ranges 0.0.0.0/0 \
  --description "Allow Legal Partner API"

# SSH is allowed by default (default-allow-ssh)
```

---

## Step 3: Create VM

```bash
gcloud compute instances create legal-partner-vm \
  --machine-type=e2-standard-4 \
  --zone=us-central1-a \
  --image-family=ubuntu-2204-lts \
  --image-project=ubuntu-os-cloud \
  --boot-disk-size=50GB
```

**Options:**
- `--zone`: Change to your preferred zone (e.g. `us-central1-a`)
- `--boot-disk-size`: 50 GB default; increase if needed

---

## Step 4: Get External IP & SSH

```bash
# Get external IP
gcloud compute instances describe legal-partner-vm \
  --zone=us-central1-a \
  --format='get(networkInterfaces[0].accessConfigs[0].natIP)'

# SSH (gcloud adds your local SSH key automatically)
gcloud compute ssh legal-partner-vm --zone=asia-south1-a
```

---

## Step 5: Install Docker & Deploy (on VM)

Once SSH'd in:

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y docker.io docker-compose git curl
sudo usermod -aG docker $USER
exit
# Re-SSH to apply docker group
gcloud compute ssh legal-partner-vm --zone=asia-south1-a
```

```bash
git clone https://github.com/jyoti0512shukla/legal-partner.git
cd legal-partner
```

Create `.env` (use nano — heredoc `EOF` fails if lines have leading spaces):
```bash
nano .env
```
Paste the following content, then press `Ctrl+X` → `Y` → Enter to save:
```
DB_PASSWORD=your-secure-password
ENCRYPTION_KEY=your-encryption-key

# LLM — fine-tuned model via Colab + ngrok
LEGALPARTNER_CHAT_API_URL=https://YOUR-COLAB-NGROK-URL.ngrok-free.app/v1
LEGALPARTNER_CHAT_API_MODEL=jyoti0512shuklaorg/saul-legal-v3

# URLs — use static IP (34.9.252.242)
LEGALPARTNER_CLOUD_FRONTEND_URL=http://34.9.252.242.nip.io:3000
LEGALPARTNER_CLOUD_BACKEND_URL=http://34.9.252.242.nip.io:8080

# Google Drive (optional — set up at console.cloud.google.com/apis/credentials)
LEGALPARTNER_GOOGLE_DRIVE_ENABLED=false
LEGALPARTNER_GOOGLE_DRIVE_CLIENT_ID=
LEGALPARTNER_GOOGLE_DRIVE_CLIENT_SECRET=

# DocuSign (optional — set up at developers.docusign.com)
LEGALPARTNER_DOCUSIGN_ENABLED=false
LEGALPARTNER_DOCUSIGN_CLIENT_ID=
LEGALPARTNER_DOCUSIGN_CLIENT_SECRET=
LEGALPARTNER_DOCUSIGN_ENV=demo

# Slack (optional)
LEGALPARTNER_SLACK_ENABLED=false
```

> **Note:** Include `/v1` at the end of the ngrok URL. No trailing slash after `/v1`.

Start services (use `docker-compose` with hyphen on Ubuntu):
```bash
docker-compose -f docker-compose.oci.yml --env-file .env up -d --build
```

*(Use `docker-compose.oci.yml` — same as OCI: Postgres + Ollama + backend. Chat runs in Colab.)*

### Update ngrok URL (each new Colab session)

Each Colab session gives a new ngrok URL. To update without full rebuild:
```bash
nano .env   # update LEGALPARTNER_CHAT_API_URL
docker-compose -f docker-compose.oci.yml --env-file .env up -d
```

---

## Step 5b: Reserve Static IP (one-time)

Prevents IP from changing on stop/start. Already done — IP is `34.9.252.242`.

```bash
# Reserve a static IP
gcloud compute addresses create legal-partner-ip --region=us-central1

# Get the reserved IP
gcloud compute addresses describe legal-partner-ip --region=us-central1 --format='value(address)'

# Remove current ephemeral IP and assign static
gcloud compute instances delete-access-config legal-partner-vm --zone=us-central1-a --access-config-name="external-nat"
gcloud compute instances add-access-config legal-partner-vm --zone=us-central1-a --address=34.9.252.242
```

> **Cost:** ~$3/month when VM is stopped (free while running).

---

## Step 6: Start/Stop On Demand

```bash
# Stop (when done — saves ~$100/mo)
gcloud compute instances stop legal-partner-vm --zone=us-central1-a

# Start (when needed)
gcloud compute instances start legal-partner-vm --zone=us-central1-a
```

> **After start:** Wait ~30 seconds before SSH. IP is static (`34.9.252.242`) — no longer changes.

```bash
gcloud compute ssh legal-partner-vm --zone=us-central1-a
```

---

## Full One-Liner (Create VM)

```bash
gcloud compute instances create legal-partner-vm \
  --machine-type=e2-standard-4 \
  --zone=us-central1-a \
  --image-family=ubuntu-2204-lts \
  --image-project=ubuntu-os-cloud \
  --boot-disk-size=50GB
```

---

---

## Quick Reference (Current Deployment)

| Item | Value |
|------|-------|
| **Project** | legal-partner-489422 |
| **VM Name** | legal-partner-vm |
| **Zone** | us-central1-a |
| **Static IP** | `34.9.252.242` (reserved, does not change on stop/start) |
| **Frontend URL** | http://34.9.252.242:3000 |
| **Backend URL** | http://34.9.252.242:8080 |
| **OAuth-friendly URL** | http://34.9.252.242.nip.io:3000 (use for Google/DocuSign OAuth) |
| **Login** | admin@legalpartner.local / Admin123! |
| **SSH** | `gcloud compute ssh legal-partner-vm --zone=us-central1-a` |
| **Fine-tuned model** | `jyoti0512shuklaorg/saul-legal-v3` |

---

## Frontend (Run Locally)

```bash
cd legal-partner/frontend
npm install
npm run dev
```

Ensure `vite.config.js` proxy target = `http://34.121.77.216:8080`. Open http://localhost:5173.

---

## Docker Commands (on VM)

```bash
# Use docker-compose (hyphen), not "docker compose"
docker-compose -f docker-compose.oci.yml ps
docker-compose -f docker-compose.oci.yml logs -f backend
docker-compose -f docker-compose.oci.yml down
docker-compose -f docker-compose.oci.yml up -d
```

---

## Database Access

```bash
# Interactive psql
docker exec -it legal-partner_postgres_1 psql -U legalpartner -d legalpartner

# One-off query (avoids pager)
docker exec legal-partner_postgres_1 psql -U legalpartner -d legalpartner -c "SELECT id, file_name, processing_status FROM document_metadata;"

# Exit pager (less): press q
```

| Field | Value |
|-------|-------|
| Host | localhost (from VM) |
| Port | 5432 |
| Database | legalpartner |
| User | legalpartner |
| Password | from .env DB_PASSWORD |

---

## Verify Encryption

```bash
docker exec legal-partner_postgres_1 psql -U legalpartner -d legalpartner -c "SELECT LEFT(content, 80) FROM embeddings LIMIT 1;"
```

Encrypted = base64-like (xY7kL2mN...). Plain = readable contract text.

---

## OAuth Integration Setup

### Google Drive

1. Go to [Google Cloud Console → Credentials](https://console.cloud.google.com/apis/credentials)
2. Create OAuth 2.0 Client ID (Web application)
3. Add redirect URI: `http://34.9.252.242.nip.io:8080/api/v1/cloud-storage/callback`
4. Add authorized JS origin: `http://34.9.252.242.nip.io:3000`
5. Update `.env` on VM:
   ```
   LEGALPARTNER_GOOGLE_DRIVE_ENABLED=true
   LEGALPARTNER_GOOGLE_DRIVE_CLIENT_ID=your-client-id.apps.googleusercontent.com
   LEGALPARTNER_GOOGLE_DRIVE_CLIENT_SECRET=your-secret
   ```
6. Restart backend: `docker-compose -f docker-compose.oci.yml --env-file .env up -d`

### DocuSign

1. Go to [DocuSign Developer](https://developers.docusign.com) → Create app
2. Add redirect URI: `http://34.9.252.242.nip.io:8080/api/v1/integrations/callback`
3. Update `.env`:
   ```
   LEGALPARTNER_DOCUSIGN_ENABLED=true
   LEGALPARTNER_DOCUSIGN_CLIENT_ID=your-integration-key
   LEGALPARTNER_DOCUSIGN_CLIENT_SECRET=your-secret
   LEGALPARTNER_DOCUSIGN_ENV=demo
   ```
4. Restart backend

### Slack

1. Create Incoming Webhook at [api.slack.com/messaging/webhooks](https://api.slack.com/messaging/webhooks)
2. Update `.env`: `LEGALPARTNER_SLACK_ENABLED=true`
3. Restart backend
4. Go to Settings → Integrations → paste webhook URL

> **nip.io note:** `34.9.252.242.nip.io` resolves to `34.9.252.242`. Google/DocuSign require a domain (not bare IP) for OAuth redirect URIs. When you have a real domain (e.g., `legal-ai.studio`), update the redirect URIs and env vars.

---

## Stop VM (Save Costs)

```bash
gcloud compute instances stop legal-partner-vm --zone=us-central1-a
gcloud compute instances start legal-partner-vm --zone=us-central1-a
```

**Get current IP** (after start; IP may change):
```bash
gcloud compute instances describe legal-partner-vm --zone=us-central1-a --format='get(networkInterfaces[0].accessConfigs[0].natIP)'
```

---

## GitHub Clone (Private Repo)

**Deploy key:** Generate on VM, add to repo Settings → Deploy keys, clone via `git@github.com:...`

**Classic token:** `git clone https://ghp_TOKEN@github.com/jyoti0512shukla/legal-partner.git`

---

## Security

- Basic Auth protects API; change default passwords
- Firewall allows 0.0.0.0/0; stop VM when not in use
- Dynamic IP makes IP whitelist impractical for home connections

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| `unknown shorthand flag: 'f'` | Use `docker-compose` (hyphen), not `docker compose` |
| `Permission denied` | Run `gcloud auth login` |
| `API not enabled` | `gcloud services enable compute.googleapis.com` |
| `Quota exceeded` | Check quotas in Console → IAM → Quotas |
| Can't reach port 80/8080 | Ensure firewall rules created (Step 2) |
| Stuck in pager | Press `q` to exit |
| Wrong zone | Use `us-central1-a` (not asia-south1-a) for this deployment |
