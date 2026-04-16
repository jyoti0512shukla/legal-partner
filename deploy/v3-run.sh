#!/usr/bin/env bash
# v3-run.sh — ONE-SHOT: spin up GPU pod, merge adapter into base, serve via vLLM,
# expose via ngrok. Handles 5–8 concurrent users with continuous batching.
#
# ─────────────────────────────────────────────────────────────────────────────
# What this script does (top to bottom):
#
#   1. Reuse or create a RunPod H100/A100 80GB pod (150 GB volume).
#   2. Install vLLM + Unsloth + PEFT on the pod.
#   3. MERGE the LoRA adapter (jyoti0512shuklaorg/gemma4-legal-v3) into the
#      base model (google/gemma-4-26B-A4B-it) — only if the merged repo
#      (jyoti0512shuklaorg/gemma4-legal-v3-merged) doesn't already exist on HF.
#      Merging happens ONCE and is cached on HF forever.
#   4. Download the merged model to /workspace/merged (fast from HF CDN).
#   5. Start vLLM with OpenAI-compatible API on :8000. Key flags:
#        --max-num-seqs 8     → up to 8 concurrent users (continuous batching)
#        --max-model-len 16384→ 16K context window
#        --gpu-memory-utilization 0.90 → let vLLM use 90% of VRAM for KV cache
#   6. Expose port 8000 via ngrok (public HTTPS URL).
#   7. Print the URL + a test curl + stop/delete commands.
#
# Why vLLM (not FastAPI+transformers):
#   - vLLM does continuous batching: 8 concurrent users stay within ~1.3× the
#     single-user latency, vs ~6× slowdown on the FastAPI wrapper.
#   - vLLM uses PagedAttention: 2–4× more users fit in the same VRAM.
#   - Native OpenAI-compatible API — drop-in for LEGALPARTNER_CHAT_API_URL.
#
# ─────────────────────────────────────────────────────────────────────────────
# Usage:
#
#   export HF_TOKEN=hf_xxx                  # required — write access needed for merge+push
#   export NGROK_TOKEN=xxx                  # required — free tier ok
#   ./deploy/v3-run.sh                      # creates new pod and runs
#   ./deploy/v3-run.sh <existing-pod-id>    # reuses an existing (started or stopped) pod
#
# Stop costs:  runpodctl pod stop   <POD_ID>   (keeps volume, ~$0.10/hr)
# Full cleanup: runpodctl pod delete <POD_ID>  (zero ongoing cost)
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

# ---------- config (override with env) ----------
ADAPTER_REPO="${ADAPTER_REPO:-jyoti0512shuklaorg/gemma4-legal-v3}"
MERGED_REPO="${MERGED_REPO:-jyoti0512shuklaorg/gemma4-legal-v3-merged}"
BASE_MODEL="${BASE_MODEL:-google/gemma-4-26B-A4B-it}"
GPU_TYPE="${GPU_TYPE:-NVIDIA H100 80GB HBM3}"        # H100 preferred — more reliable than A100 SXM
CONTAINER_DISK_GB="${CONTAINER_DISK_GB:-100}"
VOLUME_GB="${VOLUME_GB:-200}"                        # 200 GB: base + adapter + merged ≈ 130 GB + headroom
POD_NAME="${POD_NAME:-gemma4-v3-vllm}"
TEMPLATE_ID="${TEMPLATE_ID:-runpod-torch-v240}"
SSH_KEY="${SSH_KEY:-$HOME/.runpod/ssh/RunPod-Key-Go}"
MAX_MODEL_LEN="${MAX_MODEL_LEN:-16384}"              # context window
MAX_NUM_SEQS="${MAX_NUM_SEQS:-8}"                    # concurrent users
GPU_MEM_UTIL="${GPU_MEM_UTIL:-0.90}"
# N-gram speculative decoding — free 2-3x latency on repetitive output (legal
# contracts have lots of repetition). No separate draft model needed; vLLM
# matches n-grams from the prompt itself. Set to empty to disable.
SPECULATIVE_CONFIG="${SPECULATIVE_CONFIG:-{\"method\":\"ngram\",\"prompt_lookup_max\":5,\"prompt_lookup_min\":3,\"num_speculative_tokens\":5}}"

: "${HF_TOKEN:?HF_TOKEN is required (needs write access for the merge push)}"
: "${NGROK_TOKEN:?NGROK_TOKEN is required — see reference_ngrok_token.md}"
command -v runpodctl >/dev/null || { echo "runpodctl missing: brew install runpod/runpodctl/runpodctl"; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------- STEP 1: get or create pod ----------
POD_ID="${1:-}"
if [[ -z "$POD_ID" ]]; then
  echo ">> [1/7] Creating pod ($GPU_TYPE, 150GB volume)…"
  POD_ID=$(runpodctl pod create \
    --name "$POD_NAME" \
    --gpu-id "$GPU_TYPE" --gpu-count 1 \
    --template-id "$TEMPLATE_ID" \
    --container-disk-in-gb "$CONTAINER_DISK_GB" \
    --volume-in-gb "$VOLUME_GB" \
    --ports "8000/http,22/tcp" \
    -o json | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
  echo "   pod created: $POD_ID"
else
  echo ">> [1/7] Reusing pod $POD_ID — starting if needed…"
  STATUS=$(runpodctl pod get "$POD_ID" -o json | python3 -c "import sys,json; print(json.load(sys.stdin).get('desiredStatus',''))")
  if [[ "$STATUS" == "EXITED" ]]; then
    runpodctl pod start "$POD_ID" >/dev/null
  fi
fi

# ---------- STEP 2: wait for SSH ----------
echo ">> [2/7] Waiting for SSH endpoint…"
for i in {1..60}; do
  if runpodctl pod get "$POD_ID" -o json | python3 -c "import sys,json; d=json.load(sys.stdin); exit(0 if d.get('ssh',{}).get('port') else 1)" 2>/dev/null; then break; fi
  sleep 5
done
SSH_IP=$(runpodctl pod get "$POD_ID" -o json | python3 -c "import sys,json; print(json.load(sys.stdin)['ssh']['ip'])")
SSH_PORT=$(runpodctl pod get "$POD_ID" -o json | python3 -c "import sys,json; print(json.load(sys.stdin)['ssh']['port'])")
RSH="ssh -i $SSH_KEY -o StrictHostKeyChecking=no -o ConnectTimeout=20 root@$SSH_IP -p $SSH_PORT"
echo "   SSH: root@$SSH_IP:$SSH_PORT"

# ---------- STEP 3: upload merge helper ----------
# v3_merge.py merges adapter into base and pushes to HF. Only runs once — if the
# merged repo already exists, we skip straight to serving.
echo ">> [3/7] Uploading v3_merge.py…"
scp -i "$SSH_KEY" -P "$SSH_PORT" -o StrictHostKeyChecking=no \
  "$SCRIPT_DIR/v3_merge.py" root@"$SSH_IP":/workspace/v3_merge.py

# ---------- STEP 4: install deps (idempotent, fast if already installed) ----------
echo ">> [4/7] Installing vLLM + Unsloth on the pod (~3-5 min first time)…"
$RSH bash -s <<'REMOTE'
set -e
pip install -q --upgrade pip
# Unsloth: needed for the merge step (Gemma 4's ClippableLinear requires it).
# vLLM: the actual inference server.
# huggingface_hub[cli]: push merged model to HF.
pip install -q unsloth huggingface_hub[cli] pyngrok
pip install -q vllm
# CRITICAL: the runpod-torch-v240 image ships transformers==4.57.6, which does
# NOT recognize model_type='gemma4'. Both vLLM (via pydantic ModelConfig) and
# the merge step fail with "ValueError: checkpoint ... model type `gemma4` but
# Transformers does not recognize this architecture" without this upgrade.
pip install -q --upgrade 'transformers>=4.58'
python3 -c "
import vllm, unsloth, transformers
from transformers.models.auto.configuration_auto import CONFIG_MAPPING_NAMES
assert 'gemma4' in CONFIG_MAPPING_NAMES, 'gemma4 not recognised — upgrade transformers'
print(f'vllm {vllm.__version__} / unsloth {unsloth.__version__} / transformers {transformers.__version__} (gemma4 OK)')
"
REMOTE

# ---------- STEP 5: merge + push if not already on HF ----------
# Check HF for the merged repo. If missing, run the merge (loads base+adapter,
# merges, pushes ~52GB to HF). If present, skip straight to serving.
echo ">> [5/7] Checking if $MERGED_REPO exists on HF…"
MERGED_EXISTS=$($RSH "HF_TOKEN=$HF_TOKEN python3 -c \"
from huggingface_hub import HfApi
try:
    HfApi().model_info('$MERGED_REPO', token='$HF_TOKEN')
    print('yes')
except Exception:
    print('no')
\"")

if [[ "$MERGED_EXISTS" == "no" ]]; then
  echo "   merged repo missing — running merge + push (one-time, ~20 min)…"
  $RSH "HF_TOKEN=$HF_TOKEN HF_HOME=/workspace/hf_cache \
        BASE_MODEL=$BASE_MODEL \
        ADAPTER_REPO=$ADAPTER_REPO \
        MERGED_REPO=$MERGED_REPO \
        python3 /workspace/v3_merge.py 2>&1 | tee /workspace/merge.log"
else
  echo "   merged repo already on HF — skipping merge."
fi

# ---------- STEP 6: start vLLM server ----------
# Kills any previous vLLM and starts fresh. Continuous batching via --max-num-seqs.
# vLLM auto-downloads the merged model from HF to HF_HOME.
echo ">> [6/7] Starting vLLM server (model load ~5 min first time, ~1 min if cached)…"
SPEC_FLAG=""
if [[ -n "$SPECULATIVE_CONFIG" ]]; then
  SPEC_FLAG="--speculative-config '$SPECULATIVE_CONFIG'"
  echo "   speculative decoding: $SPECULATIVE_CONFIG"
fi

$RSH bash -s <<REMOTE
pkill -f "vllm serve" 2>/dev/null || true
sleep 1
export HF_TOKEN=$HF_TOKEN
export HF_HOME=/workspace/hf_cache
nohup vllm serve $MERGED_REPO \
  --host 0.0.0.0 --port 8000 \
  --max-model-len $MAX_MODEL_LEN \
  --max-num-seqs $MAX_NUM_SEQS \
  --gpu-memory-utilization $GPU_MEM_UTIL \
  --served-model-name v3 \
  $SPEC_FLAG \
  > /workspace/vllm.log 2>&1 &
echo "vLLM PID: \$!"
REMOTE

# Wait for vLLM to come up (it logs "Uvicorn running on" when ready)
echo "   waiting for vLLM to initialize…"
for i in {1..60}; do
  if $RSH "curl -s -m 3 http://localhost:8000/v1/models" 2>/dev/null | grep -q '"id"'; then
    echo "   vLLM is up."
    break
  fi
  sleep 15
  if (( i % 4 == 0 )); then
    echo "   still loading ($((i*15))s)… last log line:"
    $RSH "tail -1 /workspace/vllm.log 2>/dev/null || true"
  fi
done

# ---------- STEP 7: ngrok public tunnel ----------
echo ">> [7/7] Starting ngrok tunnel on port 8000…"
NGROK_URL=$($RSH "pkill -f ngrok 2>/dev/null; sleep 1; python3 -c \"
from pyngrok import ngrok, conf
conf.get_default().auth_token = '$NGROK_TOKEN'
print(ngrok.connect(8000, bind_tls=True).public_url)
\" 2>&1 | tail -1")

# ---------- done ----------
cat <<BANNER

============================================================
 v3 vLLM server ready
============================================================
 Pod:         $POD_ID
 SSH:         ssh -i $SSH_KEY root@$SSH_IP -p $SSH_PORT
 Public URL:  $NGROK_URL
 API:         $NGROK_URL/v1/chat/completions
 Model name:  v3   (served-model-name)
 Concurrency: up to $MAX_NUM_SEQS users, $MAX_MODEL_LEN token context
============================================================

 Quick test:
   curl $NGROK_URL/v1/chat/completions \\
     -H "Content-Type: application/json" \\
     -d '{"model":"v3","messages":[{"role":"user","content":"Draft a 3-line termination clause."}],"max_tokens":200}'

 Plug into legal-partner backend (deploy/customers/*/env):
   LEGALPARTNER_CHAT_PROVIDER=vllm
   LEGALPARTNER_CHAT_API_URL=$NGROK_URL/v1
   LEGALPARTNER_CHAT_API_MODEL=v3

 Manage the pod:
   runpodctl pod stop   $POD_ID    # halt GPU billing, keep volume
   runpodctl pod start  $POD_ID    # restart it
   runpodctl pod delete $POD_ID    # zero-cost cleanup
BANNER
