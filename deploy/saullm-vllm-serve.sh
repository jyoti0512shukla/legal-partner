#!/usr/bin/env bash
# Serve SaulLM-54B-Instruct-AWQ via vLLM on 1x A100 80GB.
#
# Prerequisites:
#   - AWQ model uploaded to HuggingFace (jyoti0512shukla/SaulLM-54B-Instruct-AWQ)
#   - OR local path at /workspace/SaulLM-54B-Instruct-AWQ
#
# Usage:
#   export HF_TOKEN=hf_xxx NGROK_TOKEN=xxx
#   ./deploy/saullm-vllm-serve.sh [existing-pod-id]
#
# Performance: ~35-50 tok/s on 1x A100 with AWQ Marlin kernels.
# Cost: ~$1.50/hr (A100 SXM4).

set -euo pipefail

: "${HF_TOKEN:?HF_TOKEN required}"
: "${NGROK_TOKEN:?NGROK_TOKEN required}"
command -v runpodctl >/dev/null || { echo "runpodctl missing"; exit 1; }

MODEL="${MODEL:-jyoti0512shukla/SaulLM-54B-Instruct-AWQ}"
GPU_TYPE="${GPU_TYPE:-NVIDIA A100-SXM4-80GB}"
SSH_KEY="${SSH_KEY:-$HOME/.runpod/ssh/RunPod-Key-Go}"

echo "============================================================"
echo " SaulLM-54B-AWQ vLLM Serve"
echo "============================================================"

# ── Step 1: Get or create pod ──
POD_ID="${1:-}"
if [[ -z "$POD_ID" ]]; then
  echo ">> [1/4] Creating pod ($GPU_TYPE)..."
  POD_ID=$(runpodctl pod create \
    --name "saullm-vllm" \
    --gpu-id "$GPU_TYPE" --gpu-count 1 \
    --image "runpod/pytorch:2.4.0-py3.11-cuda12.4.1-devel-ubuntu22.04" \
    --container-disk-in-gb 100 \
    --volume-in-gb 200 \
    --ports "8000/http,22/tcp" \
    -o json | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
  echo "   Pod: $POD_ID"
else
  echo ">> [1/4] Reusing pod $POD_ID"
fi

# ── Step 2: Wait for SSH ──
echo ">> [2/4] Waiting for SSH..."
for i in {1..60}; do
  if runpodctl pod get "$POD_ID" -o json | python3 -c "import sys,json; d=json.load(sys.stdin); exit(0 if d.get('ssh',{}).get('port') else 1)" 2>/dev/null; then break; fi
  sleep 5
done
SSH_IP=$(runpodctl pod get "$POD_ID" -o json | python3 -c "import sys,json; print(json.load(sys.stdin)['ssh']['ip'])")
SSH_PORT=$(runpodctl pod get "$POD_ID" -o json | python3 -c "import sys,json; print(json.load(sys.stdin)['ssh']['port'])")
RSH="ssh -i $SSH_KEY -o StrictHostKeyChecking=no -o ConnectTimeout=30 root@$SSH_IP -p $SSH_PORT"
echo "   SSH: root@$SSH_IP:$SSH_PORT"

# ── Step 3: Install deps + write configs ──
echo ">> [3/5] Installing vLLM + writing configs..."
$RSH 'bash -s' << 'REMOTE'
set -e
echo "Installing deps..."
pip install -q vllm pyngrok tiktoken sentencepiece protobuf 2>&1 | tail -3

# Verify
python3 -c "import vllm; print(f'vllm={vllm.__version__}')"

# Write Mistral chat template (SaulLM has no chat_template in tokenizer)
cat > /workspace/mistral_template.jinja << 'TMPL'
{{ bos_token }}{% for message in messages %}{% if message['role'] == 'user' %}[INST] {{ message['content'] }} [/INST]{% elif message['role'] == 'assistant' %} {{ message['content'] }}{{ eos_token }}{% elif message['role'] == 'system' %}[INST] {{ message['content'] }}

{% endif %}{% endfor %}
TMPL

# Write ngrok script
cat > /workspace/ngrok_serve.py << 'NGEOF'
from pyngrok import ngrok, conf
import os, time
conf.get_default().auth_token = os.environ["NGROK_TOKEN"]
url = ngrok.connect(8000, bind_tls=True).public_url
print("NGROK_URL=" + url, flush=True)
while True: time.sleep(3600)
NGEOF
echo "Deps + configs ready"
REMOTE

# ── Step 4: Start vLLM server (background, poll for ready) ──
echo ">> [4/5] Starting vLLM server (model download + load ~5-10 min)..."
$RSH "bash -c 'export HF_TOKEN=$HF_TOKEN HF_HOME=/workspace/hf_cache && \
  nohup vllm serve $MODEL \
    --served-model-name saullm-54b \
    --chat-template /workspace/mistral_template.jinja \
    --quantization awq \
    --dtype float16 \
    --max-model-len 8192 \
    --gpu-memory-utilization 0.92 \
    --port 8000 \
    --trust-remote-code \
    > /workspace/vllm.log 2>&1 &
  echo \"vLLM PID: \$!\"'"

# Poll for server ready
echo "   Waiting for model load..."
for i in $(seq 1 120); do
  if $RSH "grep -q 'Application startup complete' /workspace/vllm.log 2>/dev/null" 2>/dev/null; then
    echo "   vLLM is UP!"
    break
  fi
  if $RSH "grep -qE 'Error.*fatal|OOM|FAILED' /workspace/vllm.log 2>/dev/null" 2>/dev/null; then
    echo "   STARTUP FAILED:"
    $RSH "tail -10 /workspace/vllm.log"
    exit 1
  fi
  # Progress every 30s
  if (( i % 6 == 0 )); then
    $RSH "tail -1 /workspace/vllm.log 2>/dev/null" 2>/dev/null | tr '\r' '\n' | tail -1
  fi
  sleep 5
done

# Verify
$RSH "curl -s -m 5 http://localhost:8000/v1/models" 2>/dev/null | grep -q "saullm" && \
  echo "   Server responding on :8000" || \
  echo "   WARNING: Server may not be ready. Check /workspace/vllm.log"

# ── Step 5: Start ngrok via ssh -f (proven pattern) ──
echo ">> [5/5] Starting ngrok tunnel..."
ssh -f -i "$SSH_KEY" -o StrictHostKeyChecking=no root@"$SSH_IP" -p "$SSH_PORT" \
    "NGROK_TOKEN=$NGROK_TOKEN python3 -u /workspace/ngrok_serve.py > /workspace/ngrok.log 2>&1"
sleep 8

NGROK_URL=$($RSH "grep -oE 'https://[a-z0-9.-]+\.ngrok-free\.dev' /workspace/ngrok.log 2>/dev/null | tail -1")

cat <<BANNER

============================================================
 SaulLM-54B-AWQ vLLM server ready
============================================================
 Pod:         $POD_ID
 SSH:         ssh -i $SSH_KEY root@$SSH_IP -p $SSH_PORT
 Public URL:  ${NGROK_URL:-"(check /workspace/ngrok.log)"}
 API:         ${NGROK_URL:-"..."}/v1/chat/completions
 Model name:  saullm-54b
 Quantization: AWQ (W4A16, Marlin kernels)

 Backend .env:
   LEGALPARTNER_CHAT_PROVIDER=vllm
   LEGALPARTNER_CHAT_API_URL=${NGROK_URL:-"<URL>"}/v1
   LEGALPARTNER_CHAT_API_MODEL=saullm-54b

 Manage:
   runpodctl pod stop $POD_ID
   runpodctl pod delete $POD_ID
============================================================
BANNER
