"""
SaulLM-54B vs Gemma 4 26B benchmark — 10 contract-drafting prompts.

Runs on a RunPod A100 GPU. Loads SaulLM-54B-Instruct with 4-bit quantization
(~27GB VRAM), generates 10 responses, saves to JSON.

Usage: called from benchmark_saullm.sh (on the pod, not locally).
"""
import json
import time
import os

MODEL_ID = "Equall/SaulLM-54B-Instruct"
HF_TOKEN = os.environ.get("HF_TOKEN", "")
OUTPUT_FILE = "/workspace/saullm_benchmark_results.json"
MAX_NEW_TOKENS = 1500
TEMPERATURE = 0.3

# ── 10 contract-drafting prompts (same ones we'll run on Gemma 4 for comparison) ──
PROMPTS = [
    {
        "id": "saas_liability",
        "task": "drafting",
        "prompt": "You are a senior legal drafter. Draft a Limitation of Liability clause for a SaaS Subscription Agreement between a cloud platform provider and a retail customer, governed by California law. Include: aggregate cap at 12 months of fees paid, exclusion of indirect/consequential damages, carve-outs for IP infringement and confidentiality breach, and a survival provision. Write exactly 5 numbered sub-clauses. Output ONLY the clause text."
    },
    {
        "id": "nda_confidentiality",
        "task": "drafting",
        "prompt": "You are a senior legal drafter. Draft a Confidentiality clause for a mutual Non-Disclosure Agreement between two Delaware corporations. Include: definition of Confidential Information, obligations of the Receiving Party, permitted disclosures (employees, advisors, compelled by law), exclusions (public domain, prior knowledge, independent development), return/destruction on termination, and 3-year survival. Write exactly 5 numbered sub-clauses. Output ONLY the clause text."
    },
    {
        "id": "msa_termination",
        "task": "drafting",
        "prompt": "You are a senior legal drafter. Draft a Termination clause for a Master Services Agreement. Include: termination for cause with 30-day cure period, termination for convenience with 90-day notice, effects of termination (cease use, return materials, pay outstanding fees), and survival of confidentiality/liability/IP provisions. Write exactly 4 numbered sub-clauses. Output ONLY the clause text."
    },
    {
        "id": "saas_definitions",
        "task": "drafting",
        "prompt": "You are a senior legal drafter. Draft a Definitions clause for a SaaS Subscription Agreement. Include exactly 7 defined terms appropriate for a SaaS contract: Authorized Users, Confidential Information, Customer Data, Platform, Services, Subscription Fee, and Uptime. Each definition should be 1-3 sentences. Do NOT use MSA-specific terms like 'Statement of Work' or 'Deliverables'. Output ONLY the numbered definitions."
    },
    {
        "id": "payment_clause",
        "task": "drafting",
        "prompt": "You are a senior legal drafter. Draft a Fees and Payment clause for a SaaS Subscription Agreement with annual subscription pricing of $180,000. Include: subscription fee payable in advance, invoicing terms (net 30), late payment interest at 1.5% per month, right to suspend on non-payment after 14 days' notice, and tax obligations (exclusive of sales tax). Write exactly 5 numbered sub-clauses. Output ONLY the clause text."
    },
    {
        "id": "ip_rights",
        "task": "drafting",
        "prompt": "You are a senior legal drafter. Draft an Intellectual Property Rights clause for a SaaS Subscription Agreement. The Provider retains all IP in the platform; the Customer retains all IP in Customer Data. Provider grants Customer a limited, non-exclusive, non-transferable license to access and use the platform during the subscription term. No work-made-for-hire, no IP assignment. Write exactly 4 numbered sub-clauses. Output ONLY the clause text."
    },
    {
        "id": "force_majeure",
        "task": "drafting",
        "prompt": "You are a senior legal drafter. Draft a Force Majeure clause suitable for a commercial services agreement governed by California law. Include: definition of force majeure events, notification requirement within 5 business days, mitigation obligations, suspension of affected obligations, and right to terminate if the event continues beyond 90 days. Write exactly 5 numbered sub-clauses. Output ONLY the clause text."
    },
    {
        "id": "governing_law",
        "task": "drafting",
        "prompt": "You are a senior legal drafter. Draft a Governing Law and Dispute Resolution clause for a SaaS Agreement between a California provider and a New York customer. Include: governed by Delaware law without regard to conflict-of-laws principles, mandatory mediation before litigation, binding arbitration under AAA Commercial Rules with seat in Wilmington Delaware, English language, single arbitrator, and exclusive jurisdiction of Delaware courts for injunctive relief. Write exactly 4 numbered sub-clauses. Output ONLY the clause text."
    },
    {
        "id": "risk_assessment",
        "task": "risk",
        "prompt": "You are a legal risk analyst. Assess the risk in the following contract clause and rate it HIGH, MEDIUM, or LOW with a brief justification:\n\n'The Service Provider's total aggregate liability under this Agreement shall not exceed the fees paid by the Customer in the twelve (12) months immediately preceding the claim. IN NO EVENT SHALL EITHER PARTY BE LIABLE FOR ANY INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR PUNITIVE DAMAGES.'\n\nOutput format: RATING: [HIGH/MEDIUM/LOW]\nJUSTIFICATION: [2-3 sentences]"
    },
    {
        "id": "contract_summary",
        "task": "summary",
        "prompt": "You are a senior legal associate. Summarize the following contract clause in 3-4 bullet points for a partner's quick review:\n\n'Either Party may terminate this Agreement for convenience upon ninety (90) days' prior written notice to the other Party. Either Party may terminate this Agreement immediately upon written notice if the other Party commits a material breach and fails to cure such breach within thirty (30) days of receiving written notice thereof. Upon termination for any reason, Customer shall immediately cease all use of the Services, and each Party shall return or destroy all Confidential Information of the other Party. The obligations of the Parties under Sections 5 (Confidentiality), 7 (Limitation of Liability), 8 (Indemnification), and 10 (Governing Law) shall survive any termination or expiration of this Agreement.'\n\nOutput ONLY the bullet-point summary."
    }
]


def main():
    print(f"Loading {MODEL_ID} with 4-bit quantization...", flush=True)
    t0 = time.time()

    from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
    import torch

    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_compute_dtype=torch.float16,
        bnb_4bit_quant_type="nf4",
    )

    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID, token=HF_TOKEN)
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_ID,
        quantization_config=bnb_config,
        device_map="auto",
        token=HF_TOKEN,
    )
    print(f"Model loaded in {time.time()-t0:.1f}s", flush=True)

    results = []
    for i, p in enumerate(PROMPTS):
        print(f"\n[{i+1}/{len(PROMPTS)}] {p['id']}...", flush=True)
        t1 = time.time()

        # SaulLM uses Mistral instruct format: [INST] {prompt} [/INST]
        # Older transformers may not have chat_template set on the tokenizer,
        # so we format manually rather than relying on apply_chat_template.
        input_text = f"[INST] {p['prompt']} [/INST]"
        inputs = tokenizer(input_text, return_tensors="pt").to("cuda")

        with torch.no_grad():
            output_ids = model.generate(
                **inputs,
                max_new_tokens=MAX_NEW_TOKENS,
                temperature=TEMPERATURE,
                do_sample=True,
                top_p=0.9,
                repetition_penalty=1.1,
            )

        # Decode only the generated part (skip input tokens)
        generated = tokenizer.decode(
            output_ids[0][inputs["input_ids"].shape[1]:],
            skip_special_tokens=True
        ).strip()

        elapsed = time.time() - t1
        tok_count = output_ids.shape[1] - inputs["input_ids"].shape[1]
        tok_per_sec = tok_count / elapsed if elapsed > 0 else 0

        result = {
            "id": p["id"],
            "task": p["task"],
            "prompt": p["prompt"],
            "output": generated,
            "tokens_generated": tok_count,
            "time_seconds": round(elapsed, 1),
            "tokens_per_second": round(tok_per_sec, 1),
            "model": MODEL_ID,
            "quantization": "4-bit-nf4",
        }
        results.append(result)
        print(f"  {tok_count} tokens in {elapsed:.1f}s ({tok_per_sec:.1f} tok/s)", flush=True)
        print(f"  Preview: {generated[:200]}...", flush=True)

    with open(OUTPUT_FILE, "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
    print(f"\nResults saved to {OUTPUT_FILE}", flush=True)
    print(f"Total time: {time.time()-t0:.1f}s", flush=True)


if __name__ == "__main__":
    main()
