#!/usr/bin/env bash
# Run the eval pipeline on a RunPod pod. Handles setup, runs eval, fetches results.
# Pod must already exist (created separately).
#
# Usage:
#   export HF_TOKEN=hf_xxx
#   ./scripts/run_eval.sh <POD_ID> <MODEL_ID> [--skip-full-contract]
#
# Examples:
#   ./scripts/run_eval.sh qch244fhk0i5m3 Equall/SaulLM-54B-Instruct
#   ./scripts/run_eval.sh qch244fhk0i5m3 Equall/SaulLM-54B-Instruct --skip-full-contract

set -euo pipefail

POD_ID="${1:?Usage: run_eval.sh <POD_ID> <MODEL_ID> [--skip-full-contract]}"
MODEL_ID="${2:?Usage: run_eval.sh <POD_ID> <MODEL_ID>}"
SKIP_FULL="${3:-}"

: "${HF_TOKEN:?HF_TOKEN required}"
SSH_KEY="${SSH_KEY:-$HOME/.runpod/ssh/RunPod-Key-Go}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "============================================================"
echo " Eval Pipeline — $MODEL_ID on pod $POD_ID"
echo "============================================================"

# Get SSH details
echo ">> Getting SSH details..."
SSH_IP=$(runpodctl pod get "$POD_ID" -o json | python3 -c "import sys,json; print(json.load(sys.stdin)['ssh']['ip'])")
SSH_PORT=$(runpodctl pod get "$POD_ID" -o json | python3 -c "import sys,json; print(json.load(sys.stdin)['ssh']['port'])")
RSH="ssh -i $SSH_KEY -o StrictHostKeyChecking=no root@$SSH_IP -p $SSH_PORT"
echo "   SSH: root@$SSH_IP:$SSH_PORT"

# Upload eval script
echo ">> Uploading eval_pipeline.py..."
scp -i "$SSH_KEY" -P "$SSH_PORT" -o StrictHostKeyChecking=no \
    "$SCRIPT_DIR/eval_pipeline.py" root@"$SSH_IP":/workspace/eval_pipeline.py

# Install deps
echo ">> Installing dependencies..."
$RSH bash -s <<'REMOTE'
pip install -q 'transformers>=4.44,<4.50' accelerate bitsandbytes tiktoken sentencepiece protobuf requests 2>&1 | tail -3
python3 -c "import transformers; print('OK:', transformers.__version__)"
REMOTE

# Run eval
echo ">> Running evaluation (this takes 15-30 min)..."
EXTRA_ARGS=""
if [ "$SKIP_FULL" = "--skip-full-contract" ]; then
    EXTRA_ARGS="--skip-full-contract"
fi

$RSH "HF_TOKEN=$HF_TOKEN python3 /workspace/eval_pipeline.py \
    --hf-model '$MODEL_ID' --quantize 4bit $EXTRA_ARGS \
    2>&1 | tee /workspace/eval.log" || {
    echo "Eval failed. Fetching log..."
    $RSH "tail -30 /workspace/eval.log" 2>/dev/null || true
    exit 1
}

# Fetch results
echo ">> Fetching results..."
MODEL_SHORT=$(echo "$MODEL_ID" | tr '/' '_')
LOCAL_OUTPUT="$PROJECT_DIR/data/raw/eval_${MODEL_SHORT}.json"
scp -i "$SSH_KEY" -P "$SSH_PORT" -o StrictHostKeyChecking=no \
    "root@$SSH_IP:/workspace/eval_results_*.json" "$LOCAL_OUTPUT" 2>/dev/null || {
    echo "Warning: couldn't fetch results"
}

echo ""
echo "Results saved to: $LOCAL_OUTPUT"
echo "Pod $POD_ID is still running — delete manually when done:"
echo "  runpodctl pod delete $POD_ID"
