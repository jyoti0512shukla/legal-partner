# RunPod Gemma4 — Spin Up & Serve

How to spin up a fresh RunPod A100 80GB and serve `gemma4-legal-v1-merged` for testing.

---

## Prerequisites (already done, one-time)

- `runpodctl` installed: `brew install runpod/runpodctl/runpodctl`
- API key configured: `runpodctl config --apiKey <RUNPOD_API_KEY>` (see Claude memory)
- SSH key auto-generated at `~/.runpod/ssh/RunPod-Key-Go`
- HuggingFace token: stored in Claude memory at `project_gemma4_training.md`
- Model on HuggingFace: `jyoti0512shuklaorg/gemma4-legal-v1-merged` (~52GB)

---

## Step 1: Create the pod

```bash
runpodctl pod create \
  --name "gemma4-serve" \
  --gpu-id "NVIDIA A100-SXM4-80GB" \
  --gpu-count 1 \
  --template-id "runpod-torch-v240" \
  --container-disk-in-gb 50 \
  --volume-in-gb 200 \
  --ports "8000/http,22/tcp"
```

Cost: **$1.49/hr** (only while running)

Note the `id` from the JSON output — that's your `POD_ID`.

---

## Step 2: Wait for pod to be ready (~30-60 sec)

```bash
POD_ID=<pod-id-from-step-1>

# Poll until SSH is ready
until runpodctl ssh info $POD_ID 2>&1 | grep -q '"port"'; do sleep 5; done

# Get SSH details
runpodctl ssh info $POD_ID
```

Save the IP and port:
```bash
SSH_IP=$(runpodctl ssh info $POD_ID | python3 -c "import sys,json; print(json.load(sys.stdin)['ip'])")
SSH_PORT=$(runpodctl ssh info $POD_ID | python3 -c "import sys,json; print(json.load(sys.stdin)['port'])")
echo "ssh -i ~/.runpod/ssh/RunPod-Key-Go root@$SSH_IP -p $SSH_PORT"
```

---

## Step 3: Install dependencies on the pod (~5 min)

```bash
ssh -i ~/.runpod/ssh/RunPod-Key-Go -o StrictHostKeyChecking=no root@$SSH_IP -p $SSH_PORT << 'REMOTE'
# Upgrade torch to match CUDA driver
pip install torch torchvision --index-url https://download.pytorch.org/whl/cu126 --force-reinstall

# vLLM (this also installs compatible transformers)
pip install vllm

# Install transformers dev (Gemma 4 needs it)
pip install git+https://github.com/huggingface/transformers.git --force-reinstall --no-deps
pip install --upgrade huggingface_hub
REMOTE
```

---

## Step 4: Download model from HuggingFace (~5 min, one-time per pod)

```bash
ssh -i ~/.runpod/ssh/RunPod-Key-Go -o StrictHostKeyChecking=no root@$SSH_IP -p $SSH_PORT << 'REMOTE'
export HF_HOME=/workspace/hf_cache
hf auth login --token <HF_TOKEN>  # see Claude memory: project_gemma4_training.md
hf download jyoti0512shuklaorg/gemma4-legal-v1-merged --local-dir /workspace/gemma4-legal-v1-merged
REMOTE
```

---

## Step 5: Start vLLM server (~2 min to load model)

```bash
ssh -i ~/.runpod/ssh/RunPod-Key-Go -o StrictHostKeyChecking=no root@$SSH_IP -p $SSH_PORT << 'REMOTE'
nohup vllm serve /workspace/gemma4-legal-v1-merged \
  --port 8000 --host 0.0.0.0 \
  --max-model-len 4096 \
  --gpu-memory-utilization 0.85 \
  --max-num-seqs 5 \
  > /workspace/vllm.log 2>&1 &
echo "vLLM PID: $!"
REMOTE

# Wait for startup
sleep 90
ssh -i ~/.runpod/ssh/RunPod-Key-Go root@$SSH_IP -p $SSH_PORT "curl -s http://localhost:8000/v1/models"
```

---

## Step 6: Expose via ngrok (so test-vm backend can reach it)

```bash
ssh -i ~/.runpod/ssh/RunPod-Key-Go -o StrictHostKeyChecking=no root@$SSH_IP -p $SSH_PORT << 'REMOTE'
pip install pyngrok
python3 << 'PY'
from pyngrok import ngrok
ngrok.set_auth_token("YOUR_NGROK_TOKEN")  # https://dashboard.ngrok.com/get-started/your-authtoken
url = ngrok.connect(8000)
print(f"NGROK_URL={url.public_url}")
PY
REMOTE
```

Copy the printed URL and update `/Users/jyotimishra/legal-partner/deploy/customers/test-vm/.env`:
```
LEGALPARTNER_CHAT_PROVIDER=vllm
LEGALPARTNER_CHAT_API_URL=https://xxxx.ngrok-free.dev/v1
LEGALPARTNER_CHAT_API_MODEL=/workspace/gemma4-legal-v1-merged
```

Then deploy: `bash deploy/lp deploy test-vm`

---

## Stop the pod (saves money)

```bash
runpodctl pod stop $POD_ID
```

When stopped:
- **GPU billing stops** ($0/hr)
- **Volume storage continues** at ~$0.10/GB/mo (~$20/mo for 200GB) — but this contains the model so we keep it
- **Container disk wiped** — pip packages must be reinstalled on next start (Step 3)

---

## Restart a stopped pod

```bash
runpodctl pod start $POD_ID
```

Then re-run **Step 3 (install deps)** and **Step 5 (start vLLM)**. The model in `/workspace` persists, so Step 4 is skipped.

---

## Delete the pod entirely (no more billing)

```bash
runpodctl pod delete $POD_ID
```

Wipes everything including volume disk. Model is still safe on HuggingFace, so you can recreate from scratch later by following all steps.

---

## Quick test the model

```bash
ssh -i ~/.runpod/ssh/RunPod-Key-Go root@$SSH_IP -p $SSH_PORT 'curl -s http://localhost:8000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"/workspace/gemma4-legal-v1-merged\",\"messages\":[{\"role\":\"user\",\"content\":\"Draft a 3-clause termination provision for a vendor agreement.\"}],\"max_tokens\":400,\"temperature\":0.3}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)[\"choices\"][0][\"message\"][\"content\"])"'
```

---

## Cost summary

| State | Cost |
|---|---|
| Running | $1.49/hr (~$36/day) |
| Stopped (volume only) | ~$0.67/day (200GB × $0.10/GB/mo) |
| Deleted | $0 |

**Recommended workflow:** Create pod for a testing session, stop when done. ~$3-5/day for active testing.

---

## All useful commands

```bash
runpodctl pod list                              # See your pods
runpodctl pod create ...                        # Create new pod (Step 1)
runpodctl ssh info <POD_ID>                     # Get SSH details
runpodctl pod stop <POD_ID>                     # Stop (keep volume)
runpodctl pod start <POD_ID>                    # Start a stopped pod
runpodctl pod delete <POD_ID>                   # Delete forever
runpodctl gpu list                              # See available GPUs/prices
```

---

## Current Pod (2026-04-09)

- **Pod ID:** `7ik7r7bzs6op3d`
- **Name:** `gemma4-legal-finetune`
- **IP:** `154.54.102.31`
- **SSH Port:** `12603`
- **Status:** Running, vLLM serving on `:8000`
- **Model loaded:** `/workspace/gemma4-legal-v1-merged`
