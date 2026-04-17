# RunPod Setup Issues — Complete Reference

Every issue we've hit deploying models on RunPod, the root cause, and the
fix. Read this BEFORE spinning any pod.

---

## The runpod-torch-v240 image ships broken defaults

**Base image:** `runpod/pytorch:2.4.0-py3.11-cuda12.4.1-devel-ubuntu22.04`

The following are PRE-INSTALLED but at WRONG versions:

| Package | Image version | What breaks | Fix |
|---|---|---|---|
| `transformers` | 4.57.6 | No `gemma4` model type, no `Gemma3Config` | `pip install --upgrade 'transformers>=4.58'` |
| `torch` | 2.10.0+cu128 | Conflicts with older `torchaudio 2.4.1` (pip warning only) | Ignore — not a real problem |
| `accelerate` | NOT installed | `device_map="auto"` fails | `pip install accelerate` |
| `tiktoken` | NOT installed | SaulLM/Mistral tokenizer fails | `pip install tiktoken` |
| `sentencepiece` | NOT installed | Some tokenizers fail | `pip install sentencepiece` |
| `pyngrok` | NOT installed | ngrok tunnel fails | `pip install pyngrok` |
| `screen` | NOT installed | Can't detach processes | Use `ssh -f` or `setsid` instead |

---

## Issue-by-issue reference

### 1. `transformers` doesn't recognize `model_type: gemma4`

```
ValueError: The checkpoint you are trying to load has model type `gemma4`
but Transformers does not recognize this architecture.
```

**Affects:** Gemma 4 26B, any model using gemma4 architecture
**Root cause:** Image ships transformers 4.57.6; gemma4 was added in 4.58+
**Fix:** `pip install --upgrade 'transformers>=4.58'`
**Gotcha:** vLLM 0.19 also fails on this (same import path)

### 2. `Gemma3Config` import error in vLLM

```
ImportError: cannot import name 'Gemma3Config' from 'transformers'
```

**Affects:** vLLM 0.19.0 on this image
**Root cause:** vLLM imports `Gemma3Config` at module level. Image's
transformers 4.57.6 doesn't have it. But upgrading to 5.5.x ALSO fails
because vLLM 0.19 wasn't built against 5.5.
**Fix:** There IS no clean fix for vLLM 0.19 on this image. The
transformers version vLLM needs (has Gemma3Config but isn't too new) is
a narrow window. Workaround: **don't use vLLM** — use a FastAPI +
transformers + bitsandbytes server instead.
**Status:** Unresolved for vLLM. Use direct transformers for now.

### 3. `tiktoken` missing for Mistral/SaulLM tokenizer

```
ValueError: `tiktoken` is required to read a `tiktoken` file.
```

**Affects:** SaulLM-54B, any Mistral/Mixtral model
**Root cause:** Not pre-installed on image
**Fix:** `pip install tiktoken`

### 4. `set_submodule` error with bitsandbytes

```
AttributeError: 'MixtralForCausalLM' object has no attribute 'set_submodule'
```

**Affects:** bitsandbytes 4-bit quantization with newer transformers (5.5+)
**Root cause:** transformers 5.5 calls `model.set_submodule()` which
doesn't exist in the image's torch. Upgrading torch fixes it but risks
breaking CUDA compatibility.
**Fix:** Pin `transformers>=4.44,<4.50` when using bitsandbytes directly
(not through vLLM). This version has Mixtral support but doesn't use
the new `set_submodule` API.

### 5. `accelerate` missing for `device_map="auto"`

```
ImportError: Using `low_cpu_mem_usage=True` or a `device_map` requires
Accelerate: `pip install 'accelerate>=0.26.0'`
```

**Affects:** Any model loaded with `device_map="auto"` or bitsandbytes
**Root cause:** Not pre-installed on image
**Fix:** `pip install accelerate`

### 6. `chat_template` not set on tokenizer

```
ValueError: Cannot use chat template functions because
tokenizer.chat_template is not set
```

**Affects:** SaulLM-54B with older transformers (<4.50)
**Root cause:** The tokenizer config doesn't include a chat_template field;
newer transformers auto-detects, older doesn't.
**Fix:** Don't use `tokenizer.apply_chat_template()`. Format manually:
```python
input_text = f"[INST] {prompt} [/INST]"
```

### 7. ngrok tunnel dies when SSH session closes

**Affects:** All ngrok setups via SSH
**Root cause:** `nohup` doesn't fully detach from the SSH session's
process group on this image. When SSH closes, SIGHUP kills the process.
**Fix:** Use `ssh -f` (forks SSH to background on LOCAL side):
```bash
ssh -f -i $KEY root@$IP -p $PORT 'NGROK_TOKEN=xxx python3 -u ngrok_serve.py > ngrok.log 2>&1'
```
NOT `nohup` inside an SSH command.

### 8. nohup environment doesn't inherit pip installs

**Affects:** Packages installed then immediately used in `nohup` commands
**Root cause:** The nohup command may start before pip's changes to
`site-packages` are visible, OR the nohup shell doesn't re-read PATH.
**Fix:** Run install and serve in the SAME shell session:
```bash
ssh root@host 'bash -s' << 'EOF'
pip install accelerate bitsandbytes
python3 serve.py &
EOF
```
NOT: `ssh host "pip install X"` then `ssh host "nohup python3 serve.py"`

### 9. SaulLM-54B doesn't fit A100 80GB in bf16

**Affects:** SaulLM-54B (108GB at bf16), SaulLM-141B (282GB)
**Root cause:** 54B × 2 bytes = 108GB > 80GB VRAM
**Fix:** Use bitsandbytes 4-bit quantization (~27GB, fits easily):
```python
BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_compute_dtype=torch.float16,
                   bnb_4bit_quant_type="nf4")
```
**Inference speed:** ~8.7 tok/s (benchmark measured). Production would
use vLLM + AWQ for ~35-50 tok/s, but vLLM has its own issues (see #2).

### 10. Gemma 4 merge: `model.load_adapter()` routes through plain PEFT

```
ValueError: Target module Gemma4ClippableLinear(...) is not supported.
```

**Affects:** Merging Gemma 4 LoRA adapter into base for serving
**Root cause:** `model.load_adapter()` in transformers >=5.5 delegates
to plain PEFT, which doesn't support Gemma 4's custom linear layers.
**Fix:** Pass the ADAPTER repo to `FastLanguageModel.from_pretrained()`
(Unsloth's native path), not base + load_adapter:
```python
# WRONG:
model = FastLanguageModel.from_pretrained(BASE_MODEL, ...)
model.load_adapter(ADAPTER_REPO, ...)

# RIGHT:
model = FastLanguageModel.from_pretrained(ADAPTER_REPO, ...)
```
Unsloth reads `adapter_config.json`, loads base + applies LoRA internally.

### 11. `model.load_adapter(..., token=HF_TOKEN)` — token kwarg removed

```
TypeError: LoadStateDictConfig.__init__() got an unexpected keyword
argument 'token'
```

**Affects:** transformers >= 5.5
**Root cause:** API change — `token` kwarg was removed from `load_adapter`
**Fix:** Remove `token=HF_TOKEN`. Use `huggingface_hub.login(token=...)`
before loading instead.

---

## Battle-tested install commands

### For Gemma 4 26B (our v3 model)
```bash
pip install -q --upgrade pip
pip install -q unsloth huggingface_hub[cli] pyngrok
pip install -q vllm
pip install -q --upgrade 'transformers>=4.58'
# Verify:
python3 -c "
import vllm, unsloth, transformers
from transformers.models.auto.configuration_auto import CONFIG_MAPPING_NAMES
assert 'gemma4' in CONFIG_MAPPING_NAMES
print(f'OK: vllm={vllm.__version__} unsloth={unsloth.__version__} transformers={transformers.__version__}')
"
```

### For SaulLM-54B (benchmark / FastAPI serving)
```bash
pip install -q --upgrade pip
pip install -q 'transformers>=4.44,<4.50' accelerate bitsandbytes \
    tiktoken sentencepiece protobuf fastapi uvicorn pyngrok
# Verify:
python3 -c "
import transformers, accelerate, bitsandbytes, tiktoken, torch
print(f'OK: tf={transformers.__version__} acc={accelerate.__version__} bnb={bitsandbytes.__version__} torch={torch.__version__}')
"
```

### For SaulLM-54B (vLLM serving — CURRENTLY BROKEN)
```
vLLM 0.19.0 on runpod-torch-v240 is INCOMPATIBLE with SaulLM-54B due
to the Gemma3Config import issue. No clean fix exists. Options:
- Wait for vLLM 0.20+ (may fix the transformers version pinning)
- Use a different RunPod template with pre-installed vLLM
- Use FastAPI + bitsandbytes instead (slower but works)
```

---

## Ngrok persistent tunnel pattern (proven to work)

```bash
# 1. Upload ngrok_serve.py (has while True: time.sleep(3600) loop)
scp -i $KEY -P $PORT ngrok_serve.py root@$IP:/workspace/

# 2. Start via ssh -f (NOT nohup inside SSH)
ssh -f -i $KEY root@$IP -p $PORT \
    'NGROK_TOKEN=xxx python3 -u /workspace/ngrok_serve.py > /workspace/ngrok.log 2>&1'

# 3. Wait and read URL
sleep 8
ssh -i $KEY root@$IP -p $PORT "cat /workspace/ngrok.log"
# → NGROK_URL=https://circulable-chere-lucidly.ngrok-free.dev
```

---

## Pre-flight checklist before any pod launch

1. [ ] Which model? (Gemma 4 / SaulLM-54B / other)
2. [ ] Does it fit A100 80GB in bf16? If not, quantization needed.
3. [ ] Use the correct install block above — DON'T improvise.
4. [ ] Install AND serve in the SAME SSH session (not separate calls).
5. [ ] Use `ssh -f` for background processes, not `nohup`.
6. [ ] Verify all imports BEFORE starting the server.
7. [ ] Set `HF_TOKEN` as env var, not as function arg.
8. [ ] For ngrok: use the `ngrok_serve.py` pattern with `ssh -f`.
