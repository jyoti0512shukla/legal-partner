#!/usr/bin/env bash
# ONE-SHOT: spin pod → install deps → download model → serve SaulLM-54B → ngrok
#
# Bakes in EVERY fix from docs/RUNPOD_SETUP_ISSUES.md:
#   - Correct transformers pin (>=4.44,<4.50 for bitsandbytes compat)
#   - accelerate, tiktoken, sentencepiece pre-installed
#   - FastAPI server (NOT vLLM — vLLM 0.19 has Gemma3Config import bug)
#   - All installs + serve in SAME SSH session (no nohup env inheritance bug)
#   - ssh -f for ngrok (not nohup)
#   - 4-bit bitsandbytes quantization (54B model, fits A100 80GB)
#
# Usage:
#   export HF_TOKEN=hf_xxx NGROK_TOKEN=xxx
#   ./deploy/saullm-serve.sh [existing-pod-id]
#
# Cost: ~$1.50/hr (A100 SXM4). Model download ~10-20 min (network dependent).
# Once running: server on :8000, ngrok tunnel on reserved domain.
#
# Stop:  runpodctl pod stop <POD_ID>
# Kill:  runpodctl pod delete <POD_ID>

set -euo pipefail

: "${HF_TOKEN:?HF_TOKEN required}"
: "${NGROK_TOKEN:?NGROK_TOKEN required}"
command -v runpodctl >/dev/null || { echo "runpodctl missing"; exit 1; }

GPU_TYPE="${GPU_TYPE:-NVIDIA A100-SXM4-80GB}"
SSH_KEY="${SSH_KEY:-$HOME/.runpod/ssh/RunPod-Key-Go}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "============================================================"
echo " SaulLM-54B Serve — one-shot"
echo "============================================================"

# ── Step 1: Get or create pod ──
POD_ID="${1:-}"
if [[ -z "$POD_ID" ]]; then
  echo ">> [1/5] Creating pod ($GPU_TYPE)..."
  POD_ID=$(runpodctl pod create \
    --name "saullm-serve" \
    --gpu-id "$GPU_TYPE" --gpu-count 1 \
    --template-id "runpod-torch-v240" \
    --container-disk-in-gb 100 \
    --volume-in-gb 200 \
    --ports "8000/http,22/tcp" \
    -o json | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
  echo "   Pod: $POD_ID"
else
  echo ">> [1/5] Reusing pod $POD_ID"
fi

# ── Step 2: Wait for SSH ──
echo ">> [2/5] Waiting for SSH..."
for i in {1..60}; do
  if runpodctl pod get "$POD_ID" -o json | python3 -c "import sys,json; d=json.load(sys.stdin); exit(0 if d.get('ssh',{}).get('port') else 1)" 2>/dev/null; then break; fi
  sleep 5
done
SSH_IP=$(runpodctl pod get "$POD_ID" -o json | python3 -c "import sys,json; print(json.load(sys.stdin)['ssh']['ip'])")
SSH_PORT=$(runpodctl pod get "$POD_ID" -o json | python3 -c "import sys,json; print(json.load(sys.stdin)['ssh']['port'])")
RSH="ssh -i $SSH_KEY -o StrictHostKeyChecking=no -o ConnectTimeout=30 root@$SSH_IP -p $SSH_PORT"
echo "   SSH: root@$SSH_IP:$SSH_PORT"

# ── Step 3: Upload server script ──
echo ">> [3/5] Uploading serve.py..."
cat > /tmp/saullm_serve.py << 'PYEOF'
"""OpenAI-compatible FastAPI server for SaulLM-54B with bitsandbytes 4-bit.

NOT vLLM — vLLM 0.19 has a Gemma3Config import bug on runpod-torch-v240.
This is slower (no continuous batching) but WORKS. Serves 1-2 concurrent
users fine for testing/demo.
"""
import json, time, os, torch
from fastapi import FastAPI
from pydantic import BaseModel
from typing import List
import uvicorn

app = FastAPI()
model = tokenizer = None

class Message(BaseModel):
    role: str
    content: str

class ChatRequest(BaseModel):
    model: str = "saullm-54b"
    messages: List[Message]
    max_tokens: int = 2000
    temperature: float = 0.3

@app.on_event("startup")
def load_model():
    global model, tokenizer
    from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
    print("Loading SaulLM-54B-Instruct (4-bit)...", flush=True)
    t0 = time.time()
    bnb = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_compute_dtype=torch.float16, bnb_4bit_quant_type="nf4")
    tokenizer = AutoTokenizer.from_pretrained("Equall/SaulLM-54B-Instruct", token=os.environ.get("HF_TOKEN",""))
    model = AutoModelForCausalLM.from_pretrained("Equall/SaulLM-54B-Instruct", quantization_config=bnb, device_map="auto", token=os.environ.get("HF_TOKEN",""))
    print(f"Model loaded in {time.time()-t0:.0f}s", flush=True)

@app.post("/v1/chat/completions")
def chat(req: ChatRequest):
    prompt = " ".join(f"[INST] {m.content} [/INST]" if m.role == "user" else m.content for m in req.messages)
    inputs = tokenizer(prompt, return_tensors="pt").to("cuda")
    with torch.no_grad():
        out = model.generate(**inputs, max_new_tokens=req.max_tokens, temperature=max(req.temperature, 0.01),
                             do_sample=True, top_p=0.9, repetition_penalty=1.1)
    text = tokenizer.decode(out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True).strip()
    return {"id":"chatcmpl-1","object":"chat.completion","model":req.model,
            "choices":[{"index":0,"message":{"role":"assistant","content":text},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":int(inputs["input_ids"].shape[1]),"completion_tokens":int(out.shape[1]-inputs["input_ids"].shape[1]),"total_tokens":int(out.shape[1])}}

@app.get("/v1/models")
def models():
    return {"object":"list","data":[{"id":"saullm-54b","object":"model","owned_by":"equall","root":"Equall/SaulLM-54B-Instruct"}]}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
PYEOF

scp -i "$SSH_KEY" -P "$SSH_PORT" -o StrictHostKeyChecking=no \
    /tmp/saullm_serve.py root@"$SSH_IP":/workspace/serve.py

# Also upload ngrok_serve.py if it exists
if [[ -f "$SCRIPT_DIR/../deploy/v3-run.sh" ]]; then
  cat > /tmp/ngrok_serve.py << 'NGEOF'
from pyngrok import ngrok, conf
import os, time
conf.get_default().auth_token = os.environ["NGROK_TOKEN"]
url = ngrok.connect(8000, bind_tls=True).public_url
print("NGROK_URL=" + url, flush=True)
while True: time.sleep(3600)
NGEOF
  scp -i "$SSH_KEY" -P "$SSH_PORT" -o StrictHostKeyChecking=no \
      /tmp/ngrok_serve.py root@"$SSH_IP":/workspace/ngrok_serve.py
fi

# ── Step 4: Install deps + start server (ALL IN ONE SESSION) ──
# This is critical: install and serve MUST be in the SAME shell session.
# If you split them into separate SSH calls, the nohup'd server doesn't
# see the newly installed packages. See RUNPOD_SETUP_ISSUES.md #8.
echo ">> [4/5] Installing deps + starting server (model download ~10-20 min)..."
$RSH 'bash -s' << REMOTE
set -e
echo "Installing deps..."
pip install -q --upgrade pip
pip install -q 'transformers>=4.44,<4.50' accelerate bitsandbytes \
    tiktoken sentencepiece protobuf fastapi uvicorn pyngrok 2>&1 | tail -3

# Verify before serving — fail fast, don't waste 10 min downloading
# a model just to crash on a missing import
python3 -c "
import transformers, accelerate, bitsandbytes, tiktoken, torch, fastapi
print(f'deps OK: tf={transformers.__version__} acc={accelerate.__version__} bnb={bitsandbytes.__version__} torch={torch.__version__}')
"

echo "Starting server..."
export HF_TOKEN=$HF_TOKEN
export HF_HOME=/workspace/hf_cache
python3 /workspace/serve.py > /workspace/serve.log 2>&1 &
SERVER_PID=\$!
echo "Server PID: \$SERVER_PID"

# Wait for model to load (poll serve.log for "Model loaded" or error)
echo "Waiting for model download + load..."
for i in \$(seq 1 120); do
  if grep -q "Model loaded" /workspace/serve.log 2>/dev/null; then
    echo "Model loaded!"
    break
  fi
  if grep -q "Application startup failed" /workspace/serve.log 2>/dev/null; then
    echo "STARTUP FAILED:"
    tail -15 /workspace/serve.log
    exit 1
  fi
  # Show progress every 30s
  if (( i % 6 == 0 )); then
    tail -1 /workspace/serve.log 2>/dev/null | tr '\r' '\n' | tail -1
  fi
  sleep 5
done

# Verify server is responding
if curl -s -m 5 http://localhost:8000/v1/models | grep -q "saullm"; then
  echo "Server is UP and responding."
else
  echo "WARNING: Server may not be ready yet. Check /workspace/serve.log"
fi
REMOTE

# ── Step 5: Start ngrok via ssh -f (proven pattern) ──
echo ">> [5/5] Starting ngrok tunnel..."
ssh -f -i "$SSH_KEY" -o StrictHostKeyChecking=no root@"$SSH_IP" -p "$SSH_PORT" \
    "NGROK_TOKEN=$NGROK_TOKEN python3 -u /workspace/ngrok_serve.py > /workspace/ngrok.log 2>&1"
sleep 8

NGROK_URL=$($RSH "grep -oE 'https://[a-z0-9.-]+\.ngrok-free\.dev' /workspace/ngrok.log 2>/dev/null | tail -1")

cat <<BANNER

============================================================
 SaulLM-54B server ready
============================================================
 Pod:         $POD_ID
 SSH:         ssh -i $SSH_KEY root@$SSH_IP -p $SSH_PORT
 Public URL:  ${NGROK_URL:-"(check /workspace/ngrok.log)"}
 API:         ${NGROK_URL:-"..."}/v1/chat/completions
 Model name:  saullm-54b

 Quick test:
   curl ${NGROK_URL:-"<URL>"}/v1/chat/completions \\
     -H "Content-Type: application/json" \\
     -d '{"model":"saullm-54b","messages":[{"role":"user","content":"Draft a 3-line termination clause."}],"max_tokens":200}'

 Backend .env:
   LEGALPARTNER_CHAT_PROVIDER=vllm
   LEGALPARTNER_CHAT_API_URL=${NGROK_URL:-"<URL>"}/v1
   LEGALPARTNER_CHAT_API_MODEL=saullm-54b

 Manage:
   runpodctl pod stop $POD_ID    # pause billing
   runpodctl pod delete $POD_ID  # zero cost
============================================================
BANNER
