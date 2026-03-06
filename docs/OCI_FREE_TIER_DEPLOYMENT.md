# Legal Partner — Deploy on Oracle Cloud Always Free Tier

Step-by-step guide to run **PostgreSQL, Ollama (embeddings), and Spring Boot backend** on OCI Always Free. Chat (LLM) runs in **Google Colab** via ngrok.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Oracle Cloud (Always Free) ─ 4 OCPU, 24 GB RAM, 200 GB storage │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  Docker Compose                                              ││
│  │  • PostgreSQL 16 + PGVector                                  ││
│  │  • Ollama (all-minilm only, ~80 MB)                          ││
│  │  • Spring Boot backend                                       ││
│  │  • Nginx (frontend + reverse proxy)                          ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
         ▲                              ▼
    User (Browser)              LEGALPARTNER_CHAT_API_URL
         │                              │
         └──────────────────────────────┘
                    Colab (vLLM + ngrok)
```

---

## Part 1: Oracle Cloud Account & Instance

### Step 1.1 — Create OCI Account

1. Go to [cloud.oracle.com](https://cloud.oracle.com) → **Start for free**
2. Create account (email, password, region)
3. Choose **Home Region** — pick one close to you (e.g. **ap-mumbai-1** for India)
4. Add credit card for verification (no charges for Always Free resources)
5. Verify email and complete signup

### Step 1.2 — Create Virtual Cloud Network (VCN)

1. OCI Console → **Networking** → **Virtual Cloud Networks**
2. Click **Start VCN Wizard**
3. Select **Create VCN with Internet Connectivity**
4. Name: `legal-partner-vcn`
5. **Create VCN** (creates VCN, subnets, internet gateway, route tables)

### Step 1.3 — Create Compute Instance (Ampere A1)

1. **Compute** → **Instances** → **Create Instance**

2. **Name:** `legal-partner-vm`

3. **Placement:** Choose your **Home Region** and an **Availability Domain**

4. **Image:** Click **Change** → **Canonical Ubuntu** → **22.04** (minimal or standard)

5. **Shape:** Click **Change**

   - Select **Ampere** (ARM)
   - Select **VM.Standard.A1.Flex**
   - **OCPUs:** 4  
   - **Memory (GB):** 24  
   - Click **Select Shape**

6. **Networking:**
   - VCN: `legal-partner-vcn` (or your VCN)
   - Subnet: `Public Subnet`
   - **Assign a public IPv4 address:** Yes

7. **Add SSH keys:**
   - **Generate a key pair for me** (download private key)  
   - Or: **Upload public key** if you have one

8. **Boot volume:** Leave default (50 GB)

9. Click **Create**

10. Wait 2–3 minutes. Copy the **Public IP** from the instance details.

### Step 1.4 — Configure Security List (Firewall)

1. **Networking** → **Virtual Cloud Networks** → your VCN
2. **Security Lists** → click default security list
3. **Add Ingress Rules** (if not already present):

   | Source CIDR | IP Protocol | Destination Port | Description |
   |-------------|-------------|------------------|-------------|
   | 0.0.0.0/0   | TCP         | 22               | SSH         |
   | 0.0.0.0/0   | TCP         | 80               | HTTP        |
   | 0.0.0.0/0   | TCP         | 443              | HTTPS       |
   | 0.0.0.0/0   | TCP         | 8080             | Backend API (if not using nginx) |

4. **Create Ingress Rules**

---

## Part 2: Connect & Install on VM

### Step 2.1 — SSH into Instance

```bash
# If you downloaded the key pair:
chmod 600 ~/Downloads/ssh-key-*.key

ssh -i ~/Downloads/ssh-key-*.key ubuntu@<PUBLIC_IP>
# Replace <PUBLIC_IP> with your instance's public IP
```

### Step 2.2 — Update System & Install Docker

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y docker.io docker-compose git curl
sudo usermod -aG docker $USER
```

### Step 2.3 — Log Out and Back In (for docker group)

```bash
exit
# Re-SSH
ssh -i ~/Downloads/ssh-key-*.key ubuntu@<PUBLIC_IP>
```

### Step 2.4 — Verify Docker

```bash
docker --version
docker compose version
```

---

## Part 3: Deploy Legal Partner

### Step 3.1 — Clone Repository

```bash
# Use your GitHub repo or fork
git clone https://github.com/jyoti0512shukla/legal-partner.git
cd legal-partner
```

### Step 3.2 — Use OCI-Optimized Docker Compose

The repo includes `docker-compose.oci.yml` — embeddings only (all-minilm), no chat model. Uses 2 GB for Ollama to fit within 24 GB RAM.

### Step 3.3 — Create Environment File

```bash
# Create .env with your Colab chat URL (get this from Colab ngrok)
cat > .env << 'EOF'
DB_PASSWORD=change-this-secure-password
ENCRYPTION_KEY=change-this-encryption-key-in-prod
LEGALPARTNER_CHAT_API_URL=https://YOUR-COLAB-NGROK-URL.ngrok-free.dev
EOF

# Edit and set:
# 1. Strong DB_PASSWORD
# 2. Strong ENCRYPTION_KEY
# 3. Your Colab ngrok URL (from Step 4.2)
nano .env
```

### Step 3.4 — Start Services

```bash
# Build and start (first run: ~5–10 min for build + all-minilm pull)
docker compose -f docker-compose.oci.yml --env-file .env up -d --build

# Check status
docker compose -f docker-compose.oci.yml ps

# View logs
docker compose -f docker-compose.oci.yml logs -f backend
```

### Step 3.5 — Verify Backend

```bash
curl -s http://localhost:8080/actuator/health
# Should return: {"status":"UP"}
```

---

## Part 4: Colab Setup (Chat LLM)

### Step 4.1 — Create Colab Notebook

1. [colab.research.google.com](https://colab.research.google.com) → **New notebook**
2. **Runtime** → **Change runtime type** → **T4 GPU** (or A100)

### Step 4.2 — Run Cells

**Cell 1 — Install vLLM**
```python
!pip install -q vllm
```

**Cell 2 — Start vLLM (chat model)**
```python
!vllm serve opennyaiorg/Aalap-Mistral-7B-v0.1-bf16 \
  --port 8000 --host 0.0.0.0 \
  --max-model-len 2048 --gpu-memory-utilization 0.9
```
*First run: ~14 GB download, 5–15 min. Fallback: `mistralai/Mistral-7B-Instruct-v0.2` if AALAP gated.*

**Cell 3 — ngrok (public URL)**
```python
!pip install -q pyngrok
from pyngrok import ngrok
url = ngrok.connect(8000)
print("LEGALPARTNER_CHAT_API_URL:", url.public_url)
```

**Copy the ngrok URL** (e.g. `https://abc123.ngrok-free.dev`) and update `.env` on OCI:

```bash
# On OCI VM
nano .env
# Set: LEGALPARTNER_CHAT_API_URL=https://abc123.ngrok-free.dev

docker compose -f docker-compose.oci.yml --env-file .env up -d backend
```

---

## Part 5: Frontend (Optional — Serve from OCI)

### Step 5.1 — Build Frontend Locally (or on VM)

**Option A: Build on your laptop**
```bash
cd legal-partner/frontend
npm install
npm run build
# Upload dist/ to OCI VM (scp -r dist ubuntu@<PUBLIC_IP>:~/legal-partner/frontend/)
```

**Option B: Build on OCI VM**
```bash
# On OCI VM
sudo apt install -y nodejs npm
cd ~/legal-partner/frontend
npm install
npm run build
```

### Step 5.2 — Add Nginx to Serve Frontend

```bash
# On OCI VM
sudo apt install -y nginx

# Create nginx config
sudo tee /etc/nginx/sites-available/legal-partner << 'NGINX'
server {
    listen 80;
    server_name _;
    root /var/www/legal-partner;
    index index.html;
    location / {
        try_files $uri $uri/ /index.html;
    }
    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
NGINX

sudo ln -sf /etc/nginx/sites-available/legal-partner /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default

# Copy dist to nginx root
sudo mkdir -p /var/www/legal-partner
sudo cp -r ~/legal-partner/frontend/dist/* /var/www/legal-partner/

sudo nginx -t && sudo systemctl reload nginx
```

### Step 5.3 — Access UI

Open **http://<PUBLIC_IP>** in your browser.

**Login:** admin / admin123

---

## Part 6: Quick Access (Without Nginx)

If you skip Nginx, you can:

1. **Serve frontend from your laptop:**
   ```bash
   cd frontend
   npm run dev
   # Edit vite.config.js: proxy target = 'http://<OCI_PUBLIC_IP>:8080'
   ```
   Open http://localhost:5173 (Vite proxies /api to OCI backend)

2. **Or access backend directly:**
   - API: `http://<PUBLIC_IP>:8080`
   - Add port 8080 to OCI security list (Step 1.4)
   - Use Postman/curl for testing

---

## Part 7: VM Scheduling (Optional — Save Costs)

To run the VM only during business hours (e.g. 9 AM–7 PM), see **[docs/VM_SCHEDULING.md](VM_SCHEDULING.md)** for GCP and OCI setup.

---

## Part 8: Keep-Alive (Avoid Idle Reclamation)

Oracle may reclaim instances idle for 7+ days. To keep the VM active:

```bash
# Create a simple cron job (run on VM)
crontab -e
# Add: (pings health every 6 hours)
0 */6 * * * curl -s http://localhost:8080/actuator/health > /dev/null
```

Or use a free external monitoring service (e.g. UptimeRobot) to hit `http://<PUBLIC_IP>/actuator/health` every few hours.

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| **Out of host capacity** | Try different availability domain or wait; Oracle free tier can be oversubscribed |
| **Backend can't reach Colab** | No trailing slash on `LEGALPARTNER_CHAT_API_URL`; ensure Colab session is running |
| **502 Bad Gateway** | Ensure backend is up: `docker compose -f docker-compose.oci.yml ps` |
| **Ollama OOM** | OCI compose uses 2GB for Ollama; all-minilm is ~80MB only |
| **Colab session expired** | Re-run Colab cells → copy new ngrok URL → update `.env` → restart backend |

---

## Summary Checklist

- [ ] OCI account created
- [ ] VCN with internet connectivity
- [ ] Ampere A1 instance (4 OCPU, 24 GB)
- [ ] Security list: ingress 22, 80, 443, 8080
- [ ] Docker + Docker Compose installed
- [ ] Repo cloned, `docker-compose.oci.yml` created
- [ ] `.env` with DB_PASSWORD, ENCRYPTION_KEY, LEGALPARTNER_CHAT_API_URL
- [ ] `docker compose -f docker-compose.oci.yml up -d --build`
- [ ] Colab running vLLM + ngrok
- [ ] Frontend built and served (nginx or local)
- [ ] Login at http://<PUBLIC_IP>
