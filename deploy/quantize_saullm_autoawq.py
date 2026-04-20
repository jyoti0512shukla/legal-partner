"""AWQ-quantize SaulLM-54B-Instruct using AutoAWQ (not llmcompressor).

AutoAWQ has native Mixtral MoE support (awq/models/mixtral.py) — handles
expert sub-modules correctly, unlike llmcompressor which chokes on MoE.

Run on 1x A100 80GB. Model loads to CPU RAM (251GB available),
AutoAWQ moves layers to GPU one at a time for quantization.
DO NOT use device_map="auto" — it creates meta tensors that crash on save.

Usage:
  pip install autoawq==0.2.7.post3 'transformers==4.46.3' accelerate sentencepiece tiktoken protobuf
  HF_TOKEN=hf_xxx python quantize_saullm_autoawq.py
  huggingface-cli upload jyoti0512shukla/SaulLM-54B-Instruct-AWQ ./SaulLM-54B-Instruct-AWQ
"""
import os, time

MODEL_ID = "Equall/SaulLM-54B-Instruct"
SAVE_DIR = "/workspace/SaulLM-54B-Instruct-AWQ"
HF_TOKEN = os.environ.get("HF_TOKEN", "")

# Set HF token for gated model access
os.environ["HUGGING_FACE_HUB_TOKEN"] = HF_TOKEN

from awq import AutoAWQForCausalLM
from transformers import AutoTokenizer

# AWQ config — same as TheBloke's proven Mixtral config
quant_config = {
    "zero_point": True,
    "q_group_size": 128,
    "w_bit": 4,
    "version": "GEMM",
}

print(f"Loading tokenizer...", flush=True)
tokenizer = AutoTokenizer.from_pretrained(MODEL_ID, token=HF_TOKEN, trust_remote_code=True)

print(f"Loading model to CPU RAM (AutoAWQ handles GPU offload per-layer)...", flush=True)
t0 = time.time()
model = AutoAWQForCausalLM.from_pretrained(
    MODEL_ID,
    trust_remote_code=True,
    safetensors=True,
    token=HF_TOKEN,
)
print(f"Model loaded in {time.time()-t0:.0f}s", flush=True)

print(f"Starting AWQ quantization (4-bit, group_size=128)...", flush=True)
print(f"This takes 4-5 hours on 1x A100 with CPU offload.", flush=True)
t0 = time.time()
model.quantize(tokenizer, quant_config=quant_config)
print(f"Quantization complete in {time.time()-t0:.0f}s ({(time.time()-t0)/3600:.1f} hours)", flush=True)

print(f"Saving to {SAVE_DIR}...", flush=True)
model.save_quantized(SAVE_DIR)
tokenizer.save_pretrained(SAVE_DIR)
print(f"Done! Model saved to {SAVE_DIR}", flush=True)
print(f"Next: huggingface-cli upload jyoti0512shukla/SaulLM-54B-Instruct-AWQ {SAVE_DIR}", flush=True)
