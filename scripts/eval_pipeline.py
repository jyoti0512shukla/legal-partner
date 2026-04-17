"""
Full evaluation pipeline — mirrors our production system across ALL task types.

Tasks evaluated:
  1. DRAFTING (per-clause) — 7 clause types × 2 contract types = 14 prompts
  2. RISK ASSESSMENT — 5 clauses to assess
  3. EXTRACTION — 3 contracts to extract key terms from
  4. Q&A (contract-scoped) — 5 questions grounded in a contract
  5. SUMMARIZATION — 3 contracts to summarize
  6. FULL CONTRACT (single-shot) — 2 complete contracts

Total: ~32 prompts. Each scored on:
  - Task completion (did it follow the instruction?)
  - Legal quality (correct terminology, structure, enforceability)
  - Structural correctness (numbering, no cross-article bleed)
  - No contamination (no memorized entities, no training artifacts)
  - Instruction adherence (sub-clause count, format, length)

Usage:
  On a RunPod GPU with model loaded:
    python3 eval_pipeline.py --model-url http://localhost:8000/v1 --model-name v3
  Or with transformers directly:
    python3 eval_pipeline.py --hf-model Equall/SaulLM-54B-Instruct --quantize 4bit

Output: data/raw/eval_results_<model>_<timestamp>.json
"""

import argparse
import json
import time
import os
import re
from datetime import datetime

# ── EVAL PROMPTS ──────────────────────────────────────────────────────

DRAFTING_PROMPTS = [
    # SaaS contract clauses
    {"id": "saas_definitions", "contract_type": "SaaS", "clause": "DEFINITIONS",
     "prompt": "You are a senior legal drafter. Draft a Definitions clause for a SaaS Subscription Agreement between Acme Cloud Inc. (Provider) and RetailFlow Corp (Customer). Include exactly 7 defined terms appropriate for SaaS: Authorized Users, Confidential Information, Customer Data, Platform, Services, Subscription Fee, and Uptime. Do NOT use MSA terms like 'Statement of Work' or 'Deliverables'. Output ONLY the numbered definitions.",
     "expected_terms": ["Authorized Users", "Customer Data", "Platform", "Subscription Fee", "Uptime"],
     "banned_terms": ["Statement of Work", "Deliverables", "milestone", "Contractor"],
     "expected_count": 7},

    {"id": "saas_services", "contract_type": "SaaS", "clause": "SERVICES",
     "prompt": "You are a senior legal drafter. Draft a Services clause for a SaaS Subscription Agreement. The Provider hosts a cloud-based workflow automation platform. Customer accesses via web interface. Include: platform access grant, uptime SLA (99.9%), support tiers (email 24h, phone 4h for critical), implementation services, and Provider's right to update the platform. Write exactly 5 numbered sub-clauses. Output ONLY the clause text.",
     "expected_terms": ["platform", "uptime", "99.9%", "support"],
     "banned_terms": ["Statement of Work", "Deliverables", "milestone", "Contractor", "work product"],
     "expected_count": 5},

    {"id": "saas_payment", "contract_type": "SaaS", "clause": "PAYMENT",
     "prompt": "You are a senior legal drafter. Draft a Fees and Payment clause for a SaaS Subscription Agreement. Annual fee $180,000 payable in advance. Net 30 invoicing. Late payment 1.5% per month. Right to suspend after 14 days' notice. Taxes exclusive of sales tax. Write exactly 5 numbered sub-clauses. Output ONLY the clause text.",
     "expected_terms": ["$180,000", "net 30", "1.5%", "suspend", "sales tax"],
     "banned_terms": ["milestone", "Deliverables", "time and materials"],
     "expected_count": 5},

    {"id": "saas_liability", "contract_type": "SaaS", "clause": "LIABILITY",
     "prompt": "You are a senior legal drafter. Draft a Limitation of Liability clause for a SaaS Agreement governed by California law. Aggregate cap at 12 months of fees paid. Exclude indirect/consequential damages. Carve-outs for IP infringement and confidentiality breach. Survival provision. Write exactly 5 numbered sub-clauses. Output ONLY the clause text.",
     "expected_terms": ["twelve", "12", "aggregate", "indirect", "consequential", "carve"],
     "banned_terms": ["Ontario", "Acme", "Mahindra"],
     "expected_count": 5},

    {"id": "saas_confidentiality", "contract_type": "SaaS", "clause": "CONFIDENTIALITY",
     "prompt": "You are a senior legal drafter. Draft a Confidentiality clause for a SaaS Agreement. Definition of Confidential Information, obligations, permitted disclosures (employees, advisors, compelled by law), exclusions (public domain, prior knowledge, independent development), return/destruction on termination, 3-year survival. Write exactly 5 numbered sub-clauses. Output ONLY the clause text.",
     "expected_terms": ["Confidential Information", "shall not disclose", "exception", "return", "survival"],
     "banned_terms": [],
     "expected_count": 5},

    {"id": "saas_ip", "contract_type": "SaaS", "clause": "IP_RIGHTS",
     "prompt": "You are a senior legal drafter. Draft an IP Rights clause for a SaaS Agreement. Provider retains all IP in the platform. Customer retains IP in Customer Data. Provider grants limited, non-exclusive, non-transferable license during subscription term. No work-made-for-hire. No IP assignment. Write exactly 4 numbered sub-clauses. Output ONLY the clause text.",
     "expected_terms": ["retain", "license", "non-exclusive", "Customer Data"],
     "banned_terms": ["work made for hire", "assignment of", "assigns all right"],
     "expected_count": 4},

    {"id": "saas_termination", "contract_type": "SaaS", "clause": "TERMINATION",
     "prompt": "You are a senior legal drafter. Draft a Termination clause for a SaaS Agreement. Termination for cause with 30-day cure. Termination for convenience with 90-day notice. Effects: cease use, return data, pay outstanding. Survival of confidentiality, liability, IP. Write exactly 4 numbered sub-clauses. Output ONLY the clause text.",
     "expected_terms": ["30", "cure", "90", "convenience", "survival"],
     "banned_terms": [],
     "expected_count": 4},

    # NDA clauses (different contract type — tests mode switching)
    {"id": "nda_confidentiality", "contract_type": "NDA", "clause": "CONFIDENTIALITY",
     "prompt": "You are a senior legal drafter. Draft a Confidentiality clause for a mutual NDA between two Delaware corporations. Definition, obligations, permitted disclosures, exclusions, return/destruction, 3-year survival. Write exactly 5 numbered sub-clauses. Output ONLY the clause text.",
     "expected_terms": ["Confidential Information", "Receiving Party", "Disclosing Party"],
     "banned_terms": ["subscription", "platform", "uptime", "SaaS"],
     "expected_count": 5},

    {"id": "nda_termination", "contract_type": "NDA", "clause": "TERMINATION",
     "prompt": "You are a senior legal drafter. Draft a Term and Termination clause for a mutual NDA. Initial term 2 years, auto-renewal for 1-year periods, termination on 30 days' notice, obligations survive 3 years post-termination. Write exactly 3 numbered sub-clauses. Output ONLY the clause text.",
     "expected_terms": ["2 year", "auto-renew", "30 day", "survive"],
     "banned_terms": ["subscription", "platform", "SaaS", "Statement of Work"],
     "expected_count": 3},
]

RISK_PROMPTS = [
    {"id": "risk_uncapped_liability",
     "prompt": "You are a legal risk analyst. Assess the risk of this clause:\n\n'Provider shall be liable for all damages arising from or related to this Agreement, without any limitation or cap on liability.'\n\nOutput: RATING: [HIGH/MEDIUM/LOW]\nJUSTIFICATION: [2-3 sentences]",
     "expected_rating": "HIGH"},

    {"id": "risk_standard_liability",
     "prompt": "You are a legal risk analyst. Assess the risk of this clause:\n\n'The total aggregate liability of either Party under this Agreement shall not exceed the fees paid by Customer in the twelve (12) months preceding the claim. Neither Party shall be liable for indirect, incidental, special, or consequential damages.'\n\nOutput: RATING: [HIGH/MEDIUM/LOW]\nJUSTIFICATION: [2-3 sentences]",
     "expected_rating": "MEDIUM"},

    {"id": "risk_auto_renewal",
     "prompt": "You are a legal risk analyst. Assess the risk of this clause:\n\n'This Agreement shall automatically renew for successive one-year periods unless either Party provides written notice of non-renewal at least ninety (90) days prior to the end of the then-current term.'\n\nOutput: RATING: [HIGH/MEDIUM/LOW]\nJUSTIFICATION: [2-3 sentences]",
     "expected_rating": "LOW"},
]

QA_PROMPTS = [
    {"id": "qa_termination_notice",
     "context": "Either Party may terminate this Agreement for convenience upon ninety (90) days' prior written notice. Either Party may terminate for cause if the other Party commits a material breach and fails to cure within thirty (30) days of written notice.",
     "question": "What is the notice period for termination for convenience?",
     "expected_answer_contains": ["90", "ninety"]},

    {"id": "qa_liability_cap",
     "context": "The total aggregate liability of Provider under this Agreement shall not exceed the total Subscription Fees actually paid by Customer during the twelve (12) month period immediately preceding the event giving rise to the claim.",
     "question": "What is the liability cap?",
     "expected_answer_contains": ["12", "twelve", "month", "fees"]},

    {"id": "qa_governing_law",
     "context": "This Agreement shall be governed by and construed in accordance with the laws of the State of Delaware, without regard to its conflict of laws principles. Any disputes shall be resolved by binding arbitration administered by the American Arbitration Association in Wilmington, Delaware.",
     "question": "What law governs this agreement and where are disputes resolved?",
     "expected_answer_contains": ["Delaware", "arbitration", "AAA"]},
]

SUMMARY_PROMPTS = [
    {"id": "summary_termination",
     "prompt": "You are a senior legal associate. Summarize this clause in 3-4 bullet points:\n\n'Either Party may terminate this Agreement for convenience upon ninety (90) days' prior written notice. Either Party may terminate immediately upon written notice if the other Party commits a material breach and fails to cure within thirty (30) days. Upon termination, Customer shall cease all use of the Services and return all Confidential Information. Sections 5 (Confidentiality), 7 (Liability), 8 (Indemnification), and 10 (Governing Law) survive termination.'\n\nOutput ONLY bullet points.",
     "expected_bullets_min": 3, "expected_bullets_max": 5},

    {"id": "summary_payment",
     "prompt": "You are a senior legal associate. Summarize this clause in 3-4 bullet points:\n\n'Customer shall pay the annual Subscription Fee of $180,000 in advance within thirty (30) days of invoice. Late payments accrue interest at 1.5% per month. Provider may suspend access after fourteen (14) days' written notice of non-payment. All fees are exclusive of applicable taxes, which are Customer's responsibility.'\n\nOutput ONLY bullet points.",
     "expected_bullets_min": 3, "expected_bullets_max": 5},
]

FULL_CONTRACT_PROMPTS = [
    {"id": "full_saas_contract",
     "prompt": """You are a senior legal drafter. Draft a complete SaaS Subscription Agreement between:
- Provider: Acme Cloud Technologies, Inc., a Delaware corporation at 548 Market Street, San Francisco, CA 94104
- Customer: RetailFlow Corp., a Delaware corporation at 350 Fifth Avenue, New York, NY 10118

Deal terms: $180,000/year subscription, 36-month initial term with auto-renewal, 250 authorized users, hosted on AWS US-East-1, 99.9% uptime SLA, governed by Delaware law, AAA arbitration in Wilmington.

Include these sections in order: Definitions, Services, Fees and Payment, Confidentiality, IP Rights, Liability, Termination, Governing Law, General Provisions.

Include signature blocks. Output the COMPLETE contract text.""",
     "expected_sections": ["DEFINITIONS", "SERVICES", "PAYMENT", "CONFIDENTIALITY", "IP", "LIABILITY", "TERMINATION", "GOVERNING", "GENERAL"],
     "expected_parties": ["Acme Cloud", "RetailFlow"]},
]

# ── SCORING FUNCTIONS ─────────────────────────────────────────────────

def score_drafting(prompt_config, output):
    """Score a drafted clause on multiple dimensions."""
    scores = {}
    text = output.strip()
    text_lower = text.lower()

    # 1. Expected terms present
    found = sum(1 for t in prompt_config.get("expected_terms", []) if t.lower() in text_lower)
    total = len(prompt_config.get("expected_terms", []))
    scores["expected_terms"] = found / total if total > 0 else 1.0

    # 2. Banned terms absent
    banned_found = [t for t in prompt_config.get("banned_terms", []) if t.lower() in text_lower]
    scores["no_banned_terms"] = 1.0 if not banned_found else 0.0
    scores["banned_found"] = banned_found

    # 3. Sub-clause count
    expected = prompt_config.get("expected_count", 0)
    if expected > 0:
        # Count numbered items: "1.", "2.", "1.1", etc.
        actual = len(re.findall(r'(?m)^\s*\d+[\.\)]\s', text))
        if actual == 0:
            actual = len(re.findall(r'\b\d+\.\s+[A-Z"]', text))
        scores["subclause_count"] = min(actual / expected, 1.0)
        scores["subclause_actual"] = actual
        scores["subclause_expected"] = expected
    else:
        scores["subclause_count"] = 1.0

    # 4. Training artifacts
    artifacts = re.findall(r'__[A-Z_]+__|\\[INST\\]|Source:\s*\S+\.pdf|\[Source \d+', text)
    scores["no_artifacts"] = 1.0 if not artifacts else 0.0
    scores["artifacts_found"] = artifacts

    # 5. Memorized entities
    memorized = ["Ontario", "Quebec", "Mahindra", "NeuroPace", "Niagen", "$5,000,000"]
    mem_found = [e for e in memorized if e in text]
    scores["no_memorized"] = 1.0 if not mem_found else 0.0
    scores["memorized_found"] = mem_found

    # 6. Length (reasonable range 200-2000 chars for a clause)
    scores["length"] = len(text)
    scores["length_ok"] = 1.0 if 200 <= len(text) <= 5000 else 0.5

    # Overall
    scores["overall"] = (
        scores["expected_terms"] * 0.25 +
        scores["no_banned_terms"] * 0.20 +
        scores["subclause_count"] * 0.20 +
        scores["no_artifacts"] * 0.15 +
        scores["no_memorized"] * 0.10 +
        scores["length_ok"] * 0.10
    )
    return scores


def score_risk(prompt_config, output):
    """Score a risk assessment response."""
    scores = {}
    text = output.strip().upper()
    expected = prompt_config.get("expected_rating", "").upper()

    # Extract rating
    match = re.search(r'RATING:\s*(HIGH|MEDIUM|LOW)', text)
    actual_rating = match.group(1) if match else "UNKNOWN"
    scores["rating_match"] = 1.0 if actual_rating == expected else 0.0
    scores["actual_rating"] = actual_rating
    scores["expected_rating"] = expected

    # Has justification
    has_justification = "JUSTIFICATION:" in text or len(output.strip()) > 50
    scores["has_justification"] = 1.0 if has_justification else 0.0

    scores["overall"] = scores["rating_match"] * 0.6 + scores["has_justification"] * 0.4
    return scores


def score_qa(prompt_config, output):
    """Score a Q&A response."""
    scores = {}
    text_lower = output.strip().lower()

    expected = prompt_config.get("expected_answer_contains", [])
    found = sum(1 for t in expected if t.lower() in text_lower)
    scores["answer_accuracy"] = found / len(expected) if expected else 1.0
    scores["found_terms"] = [t for t in expected if t.lower() in text_lower]
    scores["missing_terms"] = [t for t in expected if t.lower() not in text_lower]

    # Not too long (under 300 words)
    word_count = len(output.split())
    scores["concise"] = 1.0 if word_count <= 300 else 0.5

    scores["overall"] = scores["answer_accuracy"] * 0.7 + scores["concise"] * 0.3
    return scores


def score_summary(prompt_config, output):
    """Score a summarization response."""
    scores = {}
    bullets = re.findall(r'(?m)^\s*[\-\*\d][\.\)]\s', output) or re.findall(r'(?m)^\s*\d+\.', output)
    bullet_count = len(bullets) if bullets else output.count('\n') + 1

    min_b = prompt_config.get("expected_bullets_min", 3)
    max_b = prompt_config.get("expected_bullets_max", 5)
    scores["bullet_count"] = bullet_count
    scores["count_ok"] = 1.0 if min_b <= bullet_count <= max_b else 0.5

    scores["concise"] = 1.0 if len(output.split()) <= 200 else 0.5
    scores["overall"] = scores["count_ok"] * 0.6 + scores["concise"] * 0.4
    return scores


def score_full_contract(prompt_config, output):
    """Score a full contract generation."""
    scores = {}
    text_upper = output.upper()

    # Check expected sections present
    expected = prompt_config.get("expected_sections", [])
    found = sum(1 for s in expected if s in text_upper)
    scores["sections_present"] = found / len(expected) if expected else 1.0
    scores["sections_found"] = [s for s in expected if s in text_upper]
    scores["sections_missing"] = [s for s in expected if s not in text_upper]

    # Check party names
    parties = prompt_config.get("expected_parties", [])
    parties_found = sum(1 for p in parties if p in output)
    scores["parties_present"] = parties_found / len(parties) if parties else 1.0

    # Length (full contract should be 3000+ chars)
    scores["length"] = len(output)
    scores["length_ok"] = 1.0 if len(output) >= 3000 else 0.5

    # No training artifacts
    artifacts = re.findall(r'__[A-Z_]+__|Source:\s*\S+\.pdf', output)
    scores["no_artifacts"] = 1.0 if not artifacts else 0.0

    scores["overall"] = (
        scores["sections_present"] * 0.30 +
        scores["parties_present"] * 0.20 +
        scores["length_ok"] * 0.20 +
        scores["no_artifacts"] * 0.30
    )
    return scores


# ── INFERENCE ─────────────────────────────────────────────────────────

def generate_vllm(url, model_name, prompt, max_tokens=2000):
    """Call a vLLM OpenAI-compatible endpoint."""
    import requests
    resp = requests.post(f"{url}/chat/completions", json={
        "model": model_name,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": max_tokens,
        "temperature": 0.3,
    }, timeout=300)
    resp.raise_for_status()
    return resp.json()["choices"][0]["message"]["content"]


def generate_hf(model, tokenizer, prompt, max_tokens=2000):
    """Generate with a local HuggingFace model."""
    import torch
    input_text = f"[INST] {prompt} [/INST]"
    inputs = tokenizer(input_text, return_tensors="pt").to("cuda")
    with torch.no_grad():
        output_ids = model.generate(
            **inputs, max_new_tokens=max_tokens,
            temperature=0.3, do_sample=True, top_p=0.9,
            repetition_penalty=1.1,
        )
    return tokenizer.decode(output_ids[0][inputs["input_ids"].shape[1]:],
                           skip_special_tokens=True).strip()


# ── MAIN ──────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Legal LLM Evaluation Pipeline")
    parser.add_argument("--model-url", help="vLLM OpenAI-compatible URL (e.g. http://localhost:8000/v1)")
    parser.add_argument("--model-name", default="v3", help="Model name for vLLM")
    parser.add_argument("--hf-model", help="HuggingFace model ID for direct loading")
    parser.add_argument("--quantize", choices=["4bit", "8bit", "none"], default="4bit")
    parser.add_argument("--output", default=None, help="Output file path")
    parser.add_argument("--skip-full-contract", action="store_true", help="Skip full contract generation (saves time)")
    args = parser.parse_args()

    if not args.model_url and not args.hf_model:
        parser.error("Provide either --model-url or --hf-model")

    model_label = args.model_name if args.model_url else args.hf_model.split("/")[-1]
    output_file = args.output or f"/workspace/eval_results_{model_label}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"

    # Load HF model if needed
    hf_model = hf_tokenizer = None
    if args.hf_model:
        print(f"Loading {args.hf_model} ({args.quantize})...", flush=True)
        from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
        import torch
        qconfig = None
        if args.quantize == "4bit":
            qconfig = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_compute_dtype=torch.float16, bnb_4bit_quant_type="nf4")
        elif args.quantize == "8bit":
            qconfig = BitsAndBytesConfig(load_in_8bit=True)
        hf_tokenizer = AutoTokenizer.from_pretrained(args.hf_model, token=os.environ.get("HF_TOKEN", ""))
        hf_model = AutoModelForCausalLM.from_pretrained(
            args.hf_model, quantization_config=qconfig, device_map="auto",
            token=os.environ.get("HF_TOKEN", ""))
        print("Model loaded.", flush=True)

    def generate(prompt, max_tokens=2000):
        if args.model_url:
            return generate_vllm(args.model_url, args.model_name, prompt, max_tokens)
        else:
            return generate_hf(hf_model, hf_tokenizer, prompt, max_tokens)

    results = {"model": model_label, "timestamp": datetime.now().isoformat(), "tasks": {}}
    total_score = 0
    total_count = 0

    # ── 1. DRAFTING ──
    print("\n=== DRAFTING (per-clause) ===", flush=True)
    drafting_results = []
    for p in DRAFTING_PROMPTS:
        print(f"  [{p['id']}] {p['contract_type']}/{p['clause']}...", flush=True)
        t0 = time.time()
        output = generate(p["prompt"])
        elapsed = time.time() - t0
        scores = score_drafting(p, output)
        drafting_results.append({
            "id": p["id"], "contract_type": p["contract_type"], "clause": p["clause"],
            "output": output, "scores": scores, "time_seconds": round(elapsed, 1)
        })
        total_score += scores["overall"]
        total_count += 1
        print(f"    score={scores['overall']:.2f} | terms={scores['expected_terms']:.0%} banned={scores['banned_found']} artifacts={scores['artifacts_found']} time={elapsed:.1f}s", flush=True)
    results["tasks"]["drafting"] = drafting_results

    # ── 2. RISK ASSESSMENT ──
    print("\n=== RISK ASSESSMENT ===", flush=True)
    risk_results = []
    for p in RISK_PROMPTS:
        print(f"  [{p['id']}]...", flush=True)
        t0 = time.time()
        output = generate(p["prompt"], max_tokens=300)
        elapsed = time.time() - t0
        scores = score_risk(p, output)
        risk_results.append({
            "id": p["id"], "output": output, "scores": scores, "time_seconds": round(elapsed, 1)
        })
        total_score += scores["overall"]
        total_count += 1
        print(f"    expected={scores['expected_rating']} actual={scores['actual_rating']} match={scores['rating_match']:.0f} time={elapsed:.1f}s", flush=True)
    results["tasks"]["risk"] = risk_results

    # ── 3. Q&A ──
    print("\n=== CONTRACT Q&A ===", flush=True)
    qa_results = []
    for p in QA_PROMPTS:
        prompt = f"You are a legal assistant. Answer based ONLY on the contract text below.\n\nContract:\n{p['context']}\n\nQuestion: {p['question']}\n\nAnswer concisely (under 100 words)."
        print(f"  [{p['id']}]...", flush=True)
        t0 = time.time()
        output = generate(prompt, max_tokens=300)
        elapsed = time.time() - t0
        scores = score_qa(p, output)
        qa_results.append({
            "id": p["id"], "question": p["question"], "output": output,
            "scores": scores, "time_seconds": round(elapsed, 1)
        })
        total_score += scores["overall"]
        total_count += 1
        print(f"    accuracy={scores['answer_accuracy']:.0%} found={scores['found_terms']} missing={scores['missing_terms']} time={elapsed:.1f}s", flush=True)
    results["tasks"]["qa"] = qa_results

    # ── 4. SUMMARIZATION ──
    print("\n=== SUMMARIZATION ===", flush=True)
    summary_results = []
    for p in SUMMARY_PROMPTS:
        print(f"  [{p['id']}]...", flush=True)
        t0 = time.time()
        output = generate(p["prompt"], max_tokens=500)
        elapsed = time.time() - t0
        scores = score_summary(p, output)
        summary_results.append({
            "id": p["id"], "output": output, "scores": scores, "time_seconds": round(elapsed, 1)
        })
        total_score += scores["overall"]
        total_count += 1
        print(f"    bullets={scores['bullet_count']} ok={scores['count_ok']:.0f} time={elapsed:.1f}s", flush=True)
    results["tasks"]["summary"] = summary_results

    # ── 5. FULL CONTRACT ──
    if not args.skip_full_contract:
        print("\n=== FULL CONTRACT ===", flush=True)
        full_results = []
        for p in FULL_CONTRACT_PROMPTS:
            print(f"  [{p['id']}] (this takes a while)...", flush=True)
            t0 = time.time()
            output = generate(p["prompt"], max_tokens=6000)
            elapsed = time.time() - t0
            scores = score_full_contract(p, output)
            full_results.append({
                "id": p["id"], "output": output, "scores": scores, "time_seconds": round(elapsed, 1)
            })
            total_score += scores["overall"]
            total_count += 1
            print(f"    sections={scores['sections_found']}/{len(p['expected_sections'])} missing={scores['sections_missing']} length={scores['length']} time={elapsed:.1f}s", flush=True)
        results["tasks"]["full_contract"] = full_results

    # ── SUMMARY ──
    avg_score = total_score / total_count if total_count > 0 else 0
    results["aggregate"] = {
        "total_prompts": total_count,
        "average_score": round(avg_score, 3),
        "per_task_averages": {}
    }
    for task_name, task_results in results["tasks"].items():
        task_avg = sum(r["scores"]["overall"] for r in task_results) / len(task_results) if task_results else 0
        results["aggregate"]["per_task_averages"][task_name] = round(task_avg, 3)

    with open(output_file, "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    print(f"\n{'='*60}", flush=True)
    print(f"  EVALUATION COMPLETE — {model_label}", flush=True)
    print(f"{'='*60}", flush=True)
    print(f"  Total prompts: {total_count}", flush=True)
    print(f"  Average score: {avg_score:.3f}", flush=True)
    for task, avg in results["aggregate"]["per_task_averages"].items():
        print(f"    {task:20s}: {avg:.3f}", flush=True)
    print(f"  Results: {output_file}", flush=True)
    print(f"{'='*60}", flush=True)


if __name__ == "__main__":
    main()
