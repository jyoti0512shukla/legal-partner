"""v3_merge.py — merge LoRA adapter into base model, push merged to HF.

Runs on the pod once (called by v3-run.sh). After this succeeds, the merged
model is on HF forever and vLLM can serve it directly without re-merging.

Why merge at all:
  vLLM's on-the-fly LoRA for Gemma 4 MoE is unreliable (the custom
  Gemma4ClippableLinear + MoE expert layers confuse the standard PEFT path).
  Merging once and serving the full model is the most robust production path.

Why Unsloth (not plain PEFT):
  Plain PEFT rejects Gemma 4's ClippableLinear layers with
  "Target module ... is not supported". Unsloth has native Gemma 4 support
  and handles MoE expert adapter targeting correctly during merge.

Env vars (set by the orchestrator script):
  HF_TOKEN      — write access for the push
  BASE_MODEL    — base model repo
  ADAPTER_REPO  — LoRA adapter repo
  MERGED_REPO   — destination repo for merged model
  HF_HOME       — cache dir (default /workspace/hf_cache)
"""

import os
import time

HF_TOKEN = os.environ["HF_TOKEN"]
BASE_MODEL = os.environ["BASE_MODEL"]
ADAPTER_REPO = os.environ["ADAPTER_REPO"]
MERGED_REPO = os.environ["MERGED_REPO"]
MERGED_DIR = "/workspace/merged"

print(f"[merge] base:    {BASE_MODEL}", flush=True)
print(f"[merge] adapter: {ADAPTER_REPO}", flush=True)
print(f"[merge] target:  {MERGED_REPO}", flush=True)

# ---------- 1. Login to HF ----------
from huggingface_hub import login, HfApi, create_repo
login(token=HF_TOKEN)

# ---------- 2. Load base + adapter via Unsloth ----------
# Unsloth loads base and applies the adapter in one call. We load in bf16 (not
# 4-bit) so the merge math is lossless — 4-bit merged-back would be noisy.
#
# IMPORTANT: we pass the ADAPTER repo (not BASE) to FastLanguageModel.from_pretrained.
# Unsloth reads adapter_config.json, pulls the base model itself, and applies
# the adapter via its Gemma-4-aware path. The alternative (load base, then call
# model.load_adapter(...)) routes through transformers' plain PEFT integration,
# which rejects Gemma 4's custom Gemma4ClippableLinear layer with:
#
#   ValueError: Target module Gemma4ClippableLinear(...) is not supported.
#
# Additionally, transformers>=5.5 removed the `token` kwarg from load_adapter(),
# so the old pattern fails twice. Unsloth's own path bypasses both issues.
print("[merge] loading adapter repo via Unsloth (pulls base + applies LoRA)…", flush=True)
t0 = time.time()
from unsloth import FastLanguageModel

model, tokenizer = FastLanguageModel.from_pretrained(
    model_name=ADAPTER_REPO,
    max_seq_length=8192,
    dtype=None,           # bf16 inferred on GPU
    load_in_4bit=False,   # full precision for clean merge
    token=HF_TOKEN,
)
# Adapter is already applied by the from_pretrained call above — no load_adapter needed.
print(f"[merge] loaded in {time.time()-t0:.1f}s", flush=True)

# ---------- 3. Merge adapter into base weights ----------
# save_pretrained_merged does: merge_and_unload() + save tokenizer + save model.
# "merged_16bit" = save weights in bf16 (≈52GB for 26B model).
print("[merge] merging + saving to local disk…", flush=True)
t0 = time.time()
model.save_pretrained_merged(MERGED_DIR, tokenizer, save_method="merged_16bit")
print(f"[merge] local save done in {time.time()-t0:.1f}s", flush=True)

# ---------- 4. Create HF repo (idempotent) and push ----------
print(f"[merge] creating + pushing to {MERGED_REPO}…", flush=True)
t0 = time.time()
create_repo(MERGED_REPO, token=HF_TOKEN, private=False, exist_ok=True)
api = HfApi()
api.upload_folder(
    folder_path=MERGED_DIR,
    repo_id=MERGED_REPO,
    token=HF_TOKEN,
    commit_message="merge gemma4-legal-v3 adapter into base (bf16)",
)
print(f"[merge] pushed in {time.time()-t0:.1f}s", flush=True)
print(f"[merge] ✓ merged model at https://huggingface.co/{MERGED_REPO}", flush=True)
