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
  --zone=asia-south1-a \
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
  --zone=asia-south1-a \
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

Create `.env`:
```bash
cat > .env << 'EOF'
DB_PASSWORD=change-this-secure-password
ENCRYPTION_KEY=change-this-encryption-key-in-prod
LEGALPARTNER_CHAT_API_URL=https://YOUR-COLAB-NGROK-URL.ngrok-free.dev
EOF
```

Start services:
```bash
docker compose -f docker-compose.oci.yml --env-file .env up -d --build
```

*(Use `docker-compose.oci.yml` — same as OCI: Postgres + Ollama + backend. Chat runs in Colab.)*

---

## Step 6: Start/Stop On Demand

```bash
# Stop (when done — saves ~$100/mo)
gcloud compute instances stop legal-partner-vm --zone=asia-south1-a

# Start (when needed)
gcloud compute instances start legal-partner-vm --zone=asia-south1-a
```

---

## Full One-Liner (Create VM)

```bash
gcloud compute instances create legal-partner-vm \
  --machine-type=e2-standard-4 \
  --zone=asia-south1-a \
  --image-family=ubuntu-2204-lts \
  --image-project=ubuntu-os-cloud \
  --boot-disk-size=50GB
```

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| `Permission denied` | Run `gcloud auth login` |
| `API not enabled` | `gcloud services enable compute.googleapis.com` |
| `Quota exceeded` | Check quotas in Console → IAM → Quotas |
| Can't reach port 80/8080 | Ensure firewall rules created (Step 2) |
