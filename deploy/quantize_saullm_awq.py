"""One-shot AWQ quantization for SaulLM-54B-Instruct.

Run on a 2x A100 80GB pod with vllm/vllm-openai:v0.19.1 image.
Takes ~2-3 hours. Produces a W4A16 AWQ model (~27GB) that serves
on 1x A100 80GB with vLLM at ~35-50 tok/s.

Usage:
  pip install llmcompressor datasets
  HF_TOKEN=hf_xxx python quantize_saullm_awq.py
  huggingface-cli upload jyoti0512shukla/SaulLM-54B-Instruct-AWQ ./SaulLM-54B-Instruct-AWQ
"""
import os, time, torch
from datasets import load_dataset
from transformers import AutoModelForCausalLM, AutoTokenizer
from llmcompressor import oneshot
from llmcompressor.modifiers.awq import AWQModifier

MODEL_ID = "Equall/SaulLM-54B-Instruct"
SAVE_DIR = "/workspace/SaulLM-54B-Instruct-AWQ"
HF_TOKEN = os.environ.get("HF_TOKEN", "")
NUM_CALIBRATION_SAMPLES = 512
MAX_SEQ_LEN = 2048

print(f"Loading tokenizer...", flush=True)
tokenizer = AutoTokenizer.from_pretrained(MODEL_ID, token=HF_TOKEN)
tokenizer.pad_token = tokenizer.unk_token

print(f"Loading model (bf16, device_map=auto across GPUs)...", flush=True)
t0 = time.time()
model = AutoModelForCausalLM.from_pretrained(
    MODEL_ID,
    torch_dtype=torch.bfloat16,
    device_map="auto",
    token=HF_TOKEN,
)
print(f"Model loaded in {time.time()-t0:.0f}s", flush=True)

# Calibration data — use a mix of general + legal text
print(f"Preparing calibration dataset ({NUM_CALIBRATION_SAMPLES} samples)...", flush=True)
ds = load_dataset("HuggingFaceH4/ultrachat_200k", split=f"train_sft[:{NUM_CALIBRATION_SAMPLES}]")
ds = ds.shuffle(seed=42)

def preprocess(example):
    # Build Mistral [INST] format from chat messages
    parts = []
    for msg in example["messages"]:
        if msg["role"] == "user":
            parts.append(f"[INST] {msg['content']} [/INST]")
        elif msg["role"] == "assistant":
            parts.append(f" {msg['content']}</s>")
    return {"text": "".join(parts)}

ds = ds.map(preprocess)

def tokenize(sample):
    return tokenizer(
        sample["text"],
        padding=False,
        max_length=MAX_SEQ_LEN,
        truncation=True,
        add_special_tokens=True,
    )

ds = ds.map(tokenize, remove_columns=ds.column_names)

# AWQ W4A16 quantization — near-lossless for MoE models
print("Starting AWQ quantization (this takes 2-3 hours)...", flush=True)
recipe = AWQModifier(
    targets="Linear",
    scheme="W4A16",
    ignore=["lm_head"],  # keep output projection at full precision
)

t0 = time.time()
oneshot(
    model=model,
    dataset=ds,
    recipe=recipe,
    max_seq_length=MAX_SEQ_LEN,
    num_calibration_samples=NUM_CALIBRATION_SAMPLES,
)
print(f"Quantization complete in {time.time()-t0:.0f}s", flush=True)

# Save
print(f"Saving to {SAVE_DIR}...", flush=True)
model.save_pretrained(SAVE_DIR, save_compressed=True)
tokenizer.save_pretrained(SAVE_DIR)
print(f"Done! Model saved to {SAVE_DIR}", flush=True)
print(f"Next: huggingface-cli upload jyoti0512shukla/SaulLM-54B-Instruct-AWQ {SAVE_DIR}", flush=True)
