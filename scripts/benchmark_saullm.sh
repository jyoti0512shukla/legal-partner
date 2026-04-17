#!/usr/bin/env bash
# Fully automated SaulLM-54B benchmark — zero user input required.
#
# 1. Creates a RunPod A100 pod
# 2. Installs deps + downloads SaulLM-54B-Instruct (4-bit quantized)
# 3. Runs 10 contract-drafting prompts
# 4. SCPs results to local machine
# 5. Deletes the pod
#
# Cost: ~$2-3 for ~1 hour of A100 time.
# Output: data/raw/saullm_benchmark_results.json
#
# Usage:
#   export HF_TOKEN=hf_xxx
#   ./scripts/benchmark_saullm.sh

set -euo pipefail

: "${HF_TOKEN:?HF_TOKEN is required}"
command -v runpodctl >/dev/null || { echo "runpodctl missing"; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
SSH_KEY="${SSH_KEY:-$HOME/.runpod/ssh/RunPod-Key-Go}"
GPU_TYPE="${GPU_TYPE:-NVIDIA A100 80GB PCIe}"
OUTPUT_LOCAL="$PROJECT_DIR/data/raw/saullm_benchmark_results.json"

echo "============================================================"
echo " SaulLM-54B Benchmark — fully automated"
echo "============================================================"

# ── Step 1: Create pod ──
echo ">> [1/6] Creating A100 pod..."
POD_ID=$(runpodctl pod create \
    --name "saullm-benchmark" \
    --gpu-id "$GPU_TYPE" --gpu-count 1 \
    --template-id "runpod-torch-v240" \
    --container-disk-in-gb 100 \
    --volume-in-gb 150 \
    --ports "22/tcp" \
    -o json | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "   Pod: $POD_ID"

# ── Step 2: Wait for SSH ──
echo ">> [2/6] Waiting for SSH..."
for i in {1..60}; do
    if runpodctl pod get "$POD_ID" -o json | python3 -c "import sys,json; d=json.load(sys.stdin); exit(0 if d.get('ssh',{}).get('port') else 1)" 2>/dev/null; then break; fi
    sleep 5
done
SSH_IP=$(runpodctl pod get "$POD_ID" -o json | python3 -c "import sys,json; print(json.load(sys.stdin)['ssh']['ip'])")
SSH_PORT=$(runpodctl pod get "$POD_ID" -o json | python3 -c "import sys,json; print(json.load(sys.stdin)['ssh']['port'])")
RSH="ssh -i $SSH_KEY -o StrictHostKeyChecking=no -o ConnectTimeout=30 root@$SSH_IP -p $SSH_PORT"
echo "   SSH: root@$SSH_IP:$SSH_PORT"

# ── Step 3: Upload benchmark script ──
echo ">> [3/6] Uploading benchmark script..."
scp -i "$SSH_KEY" -P "$SSH_PORT" -o StrictHostKeyChecking=no \
    "$SCRIPT_DIR/benchmark_saullm.py" root@"$SSH_IP":/workspace/benchmark_saullm.py

# ── Step 4: Install deps ──
echo ">> [4/6] Installing dependencies (~3 min)..."
$RSH bash -s <<'REMOTE'
set -e
pip install -q --upgrade pip
# Don't reinstall torch — use the image's CUDA-matched version.
# Pin transformers to avoid set_submodule incompatibility with image torch.
pip install -q 'transformers>=4.44,<4.50' accelerate bitsandbytes tiktoken sentencepiece protobuf
python3 -c "import transformers, bitsandbytes, tiktoken, torch; print(f'deps OK: transformers={transformers.__version__}, torch={torch.__version__}, bnb={bitsandbytes.__version__}')"
REMOTE

# ── Step 5: Run benchmark ──
echo ">> [5/6] Running 10 prompts (model download + inference, ~20-30 min)..."
$RSH "HF_TOKEN=$HF_TOKEN python3 /workspace/benchmark_saullm.py 2>&1 | tee /workspace/benchmark.log" || {
    echo "Benchmark script failed. Fetching log..."
    $RSH "tail -50 /workspace/benchmark.log" 2>/dev/null || true
}

# ── Step 6: Fetch results + cleanup ──
echo ">> [6/6] Fetching results + deleting pod..."
mkdir -p "$(dirname "$OUTPUT_LOCAL")"
scp -i "$SSH_KEY" -P "$SSH_PORT" -o StrictHostKeyChecking=no \
    root@"$SSH_IP":/workspace/saullm_benchmark_results.json "$OUTPUT_LOCAL" 2>/dev/null || {
    echo "Warning: couldn't fetch results file. Trying log instead..."
    scp -i "$SSH_KEY" -P "$SSH_PORT" -o StrictHostKeyChecking=no \
        root@"$SSH_IP":/workspace/benchmark.log "$PROJECT_DIR/data/raw/saullm_benchmark.log" 2>/dev/null || true
}

runpodctl pod delete "$POD_ID" 2>/dev/null && echo "Pod $POD_ID deleted."

echo ""
echo "============================================================"
if [ -f "$OUTPUT_LOCAL" ]; then
    PROMPT_COUNT=$(python3 -c "import json; print(len(json.load(open('$OUTPUT_LOCAL'))))" 2>/dev/null || echo "?")
    echo " Done! $PROMPT_COUNT prompts benchmarked."
    echo " Results: $OUTPUT_LOCAL"
    echo ""
    echo " Quick preview:"
    python3 -c "
import json
results = json.load(open('$OUTPUT_LOCAL'))
for r in results:
    print(f\"  {r['id']:25s} {r['tokens_generated']:4d} tok  {r['time_seconds']:6.1f}s  {r['tokens_per_second']:5.1f} tok/s\")
    print(f\"    {r['output'][:120]}...\")
    print()
" 2>/dev/null || true
else
    echo " Warning: results file not found. Check data/raw/saullm_benchmark.log"
fi
echo "============================================================"
