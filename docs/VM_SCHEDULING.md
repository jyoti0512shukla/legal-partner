# VM Scheduling — Run Only When Needed

Save costs by **stopping** VMs when not in use. When stopped, you pay only for disk (~$4–5/mo), not compute.

---

## Strategy: e2-standard-4 On-Demand

Use **e2-standard-4** (4 vCPU, 16 GB) but **start only when needed**, stop when done.

| State | Cost | When |
|-------|------|------|
| **Running** | ~$100/mo (~$0.14/hr) | During use |
| **Stopped** | ~$4–5/mo (disk only) | Idle |

**Example:** 2 hours/day × 20 days = 40 hrs/mo → ~$6 compute + $5 disk = **~$11/mo** vs $100/mo 24/7.

### Manual Start/Stop (gcloud)

```bash
# Switch to your Legal Partner GCP project
gcloud config set project YOUR_PROJECT_ID

# Start VM (when you need it)
gcloud compute instances start legal-partner-vm --zone=asia-south1-a

# Stop VM (when done)
gcloud compute instances stop legal-partner-vm --zone=asia-south1-a

# Check status
gcloud compute instances describe legal-partner-vm --zone=asia-south1-a --format="get(status)"
```

### Quick Alias (optional)

Add to `~/.zshrc` or `~/.bashrc`:

```bash
alias lp-start='gcloud compute instances start legal-partner-vm --zone=asia-south1-a'
alias lp-stop='gcloud compute instances stop legal-partner-vm --zone=asia-south1-a'
```

Then: `lp-start` when you need it, `lp-stop` when done.

**Boot time:** VM ~1–2 min; Docker containers (Postgres, Ollama, backend) ~2–3 min. Allow ~5 min total before the app is ready.

**With $295 credit:** At 2 hr/day, ~$7/mo → credit lasts ~40+ months. At 8 hr/day, ~$28/mo → ~10 months.

---

## GCP (Google Cloud) — Automated Schedules

### Option 1: Instance Schedules (Recommended)

1. **Console** → **Compute Engine** → **VM instances**
2. Click **Instance schedules** tab
3. **Create schedule**
4. Configure:
   - **Name:** `legal-partner-hours`
   - **Region:** Same as your VM (e.g. asia-south1)
   - **Start time:** e.g. `08:00` (8 AM)
   - **Stop time:** e.g. `20:00` (8 PM)
   - **Frequency:** Daily (or select weekdays only)

5. **Create**
6. Attach to VM: VM instances → select instance → **Edit** → **Scheduling** → **Instance schedule** → select `legal-partner-hours`

### Option 2: gcloud CLI

```bash
# Create schedule: start 8 AM, stop 8 PM, daily (UTC - adjust for your timezone)
gcloud compute resource-policies create instance-schedule legal-partner-hours \
  --region=asia-south1 \
  --vm-start-schedule="0 8 * * *" \
  --vm-stop-schedule="0 20 * * *" \
  --timezone=Asia/Kolkata

# Attach to VM
gcloud compute instances add-resource-policies LEGAL-PARTNER-VM \
  --resource-policies=legal-partner-hours \
  --zone=asia-south1-a
```

### India Timezone Example (IST = UTC+5:30)

| Desired IST | UTC (for schedule) |
|-------------|---------------------|
| Start 9 AM IST | 03:30 UTC |
| Stop 7 PM IST  | 13:30 UTC |

---

## Oracle Cloud (OCI)

### Manual Start/Stop (OCI CLI)

```bash
# Start (when needed)
oci compute instance action --instance-id <INSTANCE_OCID> --action START

# Stop (when done)
oci compute instance action --instance-id <INSTANCE_OCID> --action STOP

# Get instance OCID
oci compute instance list --compartment-id <COMPARTMENT_OCID> --query "data[?\"display-name\"=='legal-partner-vm'].id" --raw-output
```

### Option 1: Resource Scheduler (Console)

1. **Governance** → **Resource Scheduler** → **Schedules**
2. **Create schedule**

**Stop schedule (e.g. 8 PM IST = 14:30 UTC):**
- **Action:** Stop
- **Resource selection:** Static → select your `legal-partner-vm`
- **Schedule:** Cron expression
  - `0 14 * * 0-6` = Daily at 14:30 UTC (8 PM IST)
  - Or Form: Daily, Time 14:30

**Start schedule (e.g. 9 AM IST = 03:30 UTC):**
- **Action:** Start
- **Resource selection:** Static → select your `legal-partner-vm`
- **Schedule:** Cron expression
  - `30 3 * * 1-6` = Weekdays at 03:30 UTC (9 AM IST)

3. **Create schedule** (create two schedules: one Start, one Stop)

### Option 2: OCI CLI + Cron (on your laptop or a small always-on VM)

```bash
# Install OCI CLI, configure ~/.oci/config

# Stop instance (run at 8 PM IST)
oci compute instance action --instance-id <INSTANCE_OCID> --action STOP

# Start instance (run at 9 AM IST)
oci compute instance action --instance-id <INSTANCE_OCID> --action START
```

Add to crontab (adjust for your timezone):
```
# Stop at 8 PM IST (14:30 UTC)
30 14 * * 0-6 /path/to/oci compute instance action --instance-id ocid1... --action STOP

# Start at 9 AM IST (03:30 UTC) - weekdays only
30 3 * * 1-5 /path/to/oci compute instance action --instance-id ocid1... --action START
```

---

## Example Schedules

| Use Case | Start | Stop | Notes |
|----------|-------|------|-------|
| **Business hours (IST)** | 9 AM | 7 PM | Mon–Fri |
| **Extended hours** | 8 AM | 10 PM | Daily |
| **Weekends off** | 9 AM Mon | 7 PM Fri | VM off Sat–Sun |
| **Dev only** | 10 AM | 6 PM | Mon–Fri |

---

## Cost Impact

| Scenario | Hours/day | Hours/month | ~Cost (e2-standard-2 @ $50/mo) |
|----------|-----------|-------------|--------------------------------|
| 24/7 | 24 | 720 | $50 |
| 12 hr/day | 12 | 360 | ~$25 |
| 10 hr/day, weekdays | 50 | 200 | ~$14 |

*Stopped VMs: no compute charge; small disk charge remains.*

---

## Caveats

- **GCP:** Schedule may take up to 15 min to run; keep start/stop at least 15 min apart
- **OCI:** Uses UTC; convert IST → UTC (IST = UTC+5:30)
- **Legal Partner:** After VM starts, Docker containers auto-start (if `restart: unless-stopped`). Allow 2–3 min for backend to be ready
- **Colab:** Chat LLM runs separately; start Colab when you need it
