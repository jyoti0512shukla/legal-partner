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
LEGALPARTNER_CHAT_API_URL=https://YOUR-COLAB-NGROK-URL.ngrok-free.app/v1
LEGALPARTNER_CHAT_API_MODEL=mistralai/Mistral-7B-Instruct-v0.2
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

## Step 6: Start/Stop On Demand

```bash
# Stop (when done — saves ~$100/mo)
gcloud compute instances stop legal-partner-vm --zone=us-central1-a

# Start (when needed)
gcloud compute instances start legal-partner-vm --zone=us-central1-a
```

> **After start:** Wait ~30 seconds before SSH — the VM needs time to boot. IP may change after each stop/start.

Get current IP after start:
```bash
gcloud compute instances describe legal-partner-vm --zone=us-central1-a \
  --format='get(networkInterfaces[0].accessConfigs[0].natIP)'
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
| **VM IP** | Dynamic — changes after stop/start; run `describe` to get current |
| **Backend URL** | http://\<current-ip\>:8080 |
| **Login** | admin / admin123 |

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
