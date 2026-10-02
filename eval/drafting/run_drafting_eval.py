#!/usr/bin/env python3
"""
Drafting eval with round-trip metric.

For each brief in briefs.yml:
  1. POST /ai/draft/validate  → intake readiness
  2. POST /ai/draft/async     → poll until INDEXED / FAILED
  3. Deterministic checks on the draft HTML
       deal terms present · banned terms absent · unresolved placeholders ·
       sequential ARTICLE numbering · cross-references in range · invented amounts
  4. Round trip: POST /ai/risk-assessment/{id}?regenerate=true on our own draft
       → risk score, overall risk, failing review questions
  5. Optional LLM judge (--judge-url): per-article severity 1–5 on nine dimensions
       (LENS-CRAFT style), max-severity rule — one critical defect fails the article

Writes <out>/<run-id>/{summary.json, report.md, drafts/*.html}.
With --baseline, compares against a previous summary.json and exits 1 on regression.

Usage:
  python3 eval/drafting/run_drafting_eval.py --api-url http://localhost:8080 \\
      --email admin@legalpartner.local --password '...'
  python3 eval/drafting/run_drafting_eval.py ... --only saas-provider-first-draft,nda-mutual-neutral
  python3 eval/drafting/run_drafting_eval.py ... --baseline eval/results/drafting/baseline/summary.json
  python3 eval/drafting/run_drafting_eval.py --self-test      # checks only, no API

Use fictitious briefs only; never evaluate on client data against hosted models.
"""

import argparse
import collections
import datetime as dt
import html as htmllib
import json
import os
import re
import statistics
import sys
import time
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("pip install pyyaml requests")

HERE = Path(__file__).resolve().parent

# Regression thresholds for --baseline (absolute deltas).
THRESHOLDS = {
    "mean_risk_score": +5.0,          # higher = worse
    "term_coverage": -0.03,           # lower = worse
    "draft_success_rate": -0.05,
    "placeholders_per_draft": +0.5,
    "failing_review_questions_per_draft": +1.0,
    "judge_production_ready_rate": -0.05,
}

JUDGE_DIMENSIONS = [
    "legal_soundness", "expressiveness", "necessity_completeness", "scenario_alignment",
    "compliance_regulatory", "risk_allocation", "architectural_coherence",
    "formal_drafting_quality", "robustness",
]

# ── HTML helpers ────────────────────────────────────────────────────────────────

BLOCK_TAGS = re.compile(r"(?i)</?(p|div|h[1-6]|li|tr|br|table|section)[^>]*>")
TAGS = re.compile(r"<[^>]+>")


def to_text(html: str) -> str:
    s = re.sub(r"(?is)<(script|style)[^>]*>.*?</\1>", "", html or "")
    s = BLOCK_TAGS.sub("\n", s)
    s = htmllib.unescape(TAGS.sub("", s))
    s = re.sub(r"[ \t ]+", " ", s)
    s = re.sub(r" *\n *", "\n", s)
    return re.sub(r"\n{3,}", "\n\n", s).strip()


def split_articles(text: str):
    """[(number, title, body)] from 'ARTICLE n — TITLE' headings."""
    parts = list(re.finditer(r"(?m)^ARTICLE\s+(\d+)\s*[—–-]\s*(.+)$", text))
    out = []
    for i, m in enumerate(parts):
        end = parts[i + 1].start() if i + 1 < len(parts) else len(text)
        out.append((int(m.group(1)), m.group(2).strip(), text[m.end():end].strip()))
    return out

# ── Deterministic checks ────────────────────────────────────────────────────────

MONEY = re.compile(r"(?:[$£€₹]|USD|INR|EUR|GBP)\s?\d[\d,]*(?:\.\d+)?")
PLACEHOLDER = re.compile(r"\[(?!●)[^\]\n]{1,60}\]|\bTBD\b|\bTBC\b|\{\{[^}]+\}\}")
XREF = re.compile(r"(?i)\b(?:Article|Clause)\s+(\d+)\b")


def digits(s: str) -> str:
    return re.sub(r"[^\d.]", "", s).rstrip(".")


def term_present(term: str, text: str) -> bool:
    if MONEY.fullmatch(term.strip()) or re.fullmatch(r"[\d,.%]+", term.strip()):
        d = digits(term)
        return bool(d) and d in digits_index(text)
    return term.lower() in text.lower()


_digits_cache = {}


def digits_index(text: str) -> set:
    key = id(text)
    if key not in _digits_cache:
        _digits_cache.clear()
        _digits_cache[key] = {digits(m) for m in re.findall(r"\d[\d,]*(?:\.\d+)?%?", text)}
    return _digits_cache[key]


def deterministic_checks(brief: dict, html: str) -> dict:
    text = to_text(html)
    expect = brief.get("expect", {}) or {}
    present = expect.get("terms_present", []) or []
    absent = expect.get("terms_absent", []) or []
    articles = split_articles(text)
    numbers = [n for n, _, _ in articles]

    missing_terms = [t for t in present if not term_present(t, text)]
    banned_hits = [t for t in absent
                   if re.search(r"(?i)(?<![\w])" + re.escape(t) + r"(?![\w])", text)]
    placeholders = PLACEHOLDER.findall(text)
    xrefs = [int(n) for n in XREF.findall(text)]
    bad_xrefs = sorted({n for n in xrefs if n < 1 or n > max(numbers or [0])})
    brief_amounts = {digits(m) for m in MONEY.findall(brief.get("dealBrief", ""))}
    draft_amounts = {digits(m) for m in MONEY.findall(text)}
    invented = sorted(a for a in draft_amounts - brief_amounts if a)

    return {
        "articles": len(articles),
        "min_articles_ok": len(articles) >= int(expect.get("min_articles", 0)),
        "numbering_ok": numbers == list(range(1, len(numbers) + 1)) and len(numbers) > 0,
        "terms_expected": len(present),
        "terms_missing": missing_terms,
        "banned_hits": banned_hits,
        "placeholders": placeholders[:20],
        "placeholder_count": len(placeholders),
        "bad_xrefs": bad_xrefs,
        "invented_amounts": invented,
        "chars": len(text),
    }

# ── API client ──────────────────────────────────────────────────────────────────


class Api:
    def __init__(self, base: str, verify_tls: bool = True):
        import requests
        self.base = base.rstrip("/") + "/api/v1"
        self.s = requests.Session()
        self.s.verify = verify_tls

    def login(self, email: str, password: str):
        r = self.s.post(f"{self.base}/auth/login", json={"email": email, "password": password}, timeout=30)
        r.raise_for_status()
        body = r.json() if r.content else {}
        if body.get("mfaRequired"):
            sys.exit("MFA is enabled for this user — use an eval account without MFA.")
        token = body.get("token")
        if token:
            self.s.headers["Authorization"] = f"Bearer {token}"

    def post(self, path, payload=None, timeout=120, params=None):
        r = self.s.post(self.base + path, json=payload, timeout=timeout, params=params)
        r.raise_for_status()
        return r.json()

    def get(self, path, timeout=60):
        r = self.s.get(self.base + path, timeout=timeout)
        r.raise_for_status()
        return r.json()


def draft_request(b: dict) -> dict:
    keys = ["templateId", "clientPosition", "draftStance", "jurisdiction", "partyA", "partyB",
            "dealBrief", "contractTypeName", "industry", "practiceArea"]
    return {k: b[k] for k in keys if b.get(k) is not None}


def run_draft(api: Api, b: dict, timeout_s: int, poll_s: int) -> dict:
    req = draft_request(b)
    validation = api.post("/ai/draft/validate", req)
    t0 = time.time()
    submitted = api.post("/ai/draft/async", req)
    doc_id = submitted["id"]
    status, last = "PENDING", {}
    while time.time() - t0 < timeout_s:
        last = api.get(f"/ai/draft/async/{doc_id}")
        status = last.get("status")
        if status in ("INDEXED", "FAILED"):
            break
        time.sleep(poll_s)
    return {
        "doc_id": doc_id,
        "status": status if status in ("INDEXED", "FAILED") else "TIMEOUT",
        "seconds": round(time.time() - t0, 1),
        "html": last.get("draftHtml", "") or "",
        "error": last.get("errorMessage"),
        "validation": {
            "ready": validation.get("ready"),
            "missingRequired": [m.get("field") for m in validation.get("missingRequired", [])],
            "missingRecommended": [m.get("field") for m in validation.get("missingRecommended", [])],
        },
    }


def failing_questions(risk: dict) -> list:
    out = []
    for clause in risk.get("clauseResults") or []:
        for q in clause.get("questions") or []:
            ans = (q.get("answer") or "").upper()
            if ans.startswith("NO") and q.get("riskIfNo") in ("HIGH", "MEDIUM"):
                out.append(f"{clause.get('clauseType')}:{q.get('id')}")
            elif ans.startswith("YES") and q.get("riskIfYes") in ("HIGH", "MEDIUM"):
                out.append(f"{clause.get('clauseType')}:{q.get('id')}")
    return out


def run_review(api: Api, doc_id: str) -> dict:
    risk = api.post(f"/ai/risk-assessment/{doc_id}", params={"regenerate": "true"}, timeout=900)
    return {
        "overall_risk": risk.get("overallRisk"),
        "risk_score": risk.get("riskScore"),
        "missing_clauses": risk.get("missingClauses") or [],
        "high_clauses": [c.get("clauseType") for c in (risk.get("clauseResults") or [])
                         if c.get("overallRisk") == "HIGH"],
        "failing_questions": failing_questions(risk),
        "waived": sum(1 for c in (risk.get("clauseResults") or [])
                      for q in (c.get("questions") or []) if (q.get("answer") or "") == "WAIVED"),
    }

# ── Optional LLM judge ──────────────────────────────────────────────────────────

JUDGE_PROMPT = """You are a senior contracts partner grading one article of a draft contract.
Deal brief: {brief}
Drafted for: {position} ({stance})
Article {num} — {title}:
{body}
{reference}
Score each dimension for DEFECT SEVERITY from 1 (no issue) to 5 (critical: unenforceable,
dangerous, or wrong for this deal): {dims}.
Output ONLY JSON: {{"scores": {{"<dimension>": <1-5>, ...}}, "worst_issue": "<one sentence or empty>"}}"""


def reference_for(b: dict, title: str) -> str:
    """Signed text for this article (briefs exported from the app carry reference_clauses)."""
    refs = b.get("reference_clauses") or {}
    t = title.lower()
    for clause_key, text in refs.items():
        words = [w for w in clause_key.lower().split("_") if len(w) > 2]
        if words and all(w in t for w in words):
            return ("\nFor reference, the version this firm actually signed for this deal "
                    "(one acceptable wording, not the only one):\n" + text[:4000] + "\n")
    return ""


def judge_articles(b: dict, html: str, url: str, model: str, key: str) -> dict:
    import requests
    text = to_text(html)
    results = []
    for num, title, body in split_articles(text):
        prompt = JUDGE_PROMPT.format(brief=b.get("dealBrief", "").strip(), position=b.get("clientPosition"),
                                     stance=b.get("draftStance"), num=num, title=title, body=body[:6000],
                                     reference=reference_for(b, title), dims=", ".join(JUDGE_DIMENSIONS))
        try:
            r = requests.post(url.rstrip("/") + "/chat/completions", timeout=180,
                              headers={"Authorization": f"Bearer {key}"},
                              json={"model": model, "temperature": 0, "max_tokens": 400,
                                    "response_format": {"type": "json_object"},
                                    "messages": [{"role": "user", "content": prompt}]})
            r.raise_for_status()
            content = r.json()["choices"][0]["message"]["content"]
            content = re.sub(r"(?s)<think>.*?</think>", "", content)
            data = json.loads(content[content.index("{"): content.rindex("}") + 1])
            scores = {d: int(data.get("scores", {}).get(d, 3)) for d in JUDGE_DIMENSIONS}
            results.append({"article": num, "title": title, "max_severity": max(scores.values()),
                            "scores": scores, "worst_issue": data.get("worst_issue", "")})
        except Exception as e:  # keep going; record the failure
            results.append({"article": num, "title": title, "error": str(e)[:200]})
    graded = [r for r in results if "max_severity" in r]
    return {
        "articles": results,
        "production_ready_rate": (sum(1 for r in graded if r["max_severity"] <= 2) / len(graded)) if graded else None,
        "mean_max_severity": statistics.mean(r["max_severity"] for r in graded) if graded else None,
    }

# ── Aggregation, report, baseline ───────────────────────────────────────────────


def aggregate(rows: list) -> dict:
    done = [r for r in rows if r.get("status") == "INDEXED"]
    n = len(rows) or 1
    terms_expected = sum(r["checks"]["terms_expected"] for r in done) or 1
    terms_missing = sum(len(r["checks"]["terms_missing"]) for r in done)
    reviewed = [r for r in done if r.get("review") and r["review"].get("risk_score") is not None]
    judged = [r for r in done if r.get("judge") and r["judge"].get("production_ready_rate") is not None]
    q_counter = collections.Counter(q for r in reviewed for q in r["review"]["failing_questions"])
    return {
        "briefs": len(rows),
        "draft_success_rate": len(done) / n,
        "mean_seconds": statistics.mean(r["seconds"] for r in done) if done else None,
        "term_coverage": 1 - terms_missing / terms_expected,
        "banned_hits_per_draft": (sum(len(r["checks"]["banned_hits"]) for r in done) / len(done)) if done else None,
        "placeholders_per_draft": (sum(r["checks"]["placeholder_count"] for r in done) / len(done)) if done else None,
        "numbering_ok_rate": (sum(1 for r in done if r["checks"]["numbering_ok"]) / len(done)) if done else None,
        "xref_ok_rate": (sum(1 for r in done if not r["checks"]["bad_xrefs"]) / len(done)) if done else None,
        "invented_amounts_per_draft": (sum(len(r["checks"]["invented_amounts"]) for r in done) / len(done)) if done else None,
        "intake_ready_rate": sum(1 for r in rows if (r.get("validation") or {}).get("ready")) / n,
        # Round trip — our review of our own drafts
        "mean_risk_score": statistics.mean(r["review"]["risk_score"] for r in reviewed) if reviewed else None,
        "overall_risk_distribution": dict(collections.Counter(r["review"]["overall_risk"] for r in reviewed)),
        "failing_review_questions_per_draft": (sum(len(r["review"]["failing_questions"]) for r in reviewed) / len(reviewed)) if reviewed else None,
        "top_failing_questions": q_counter.most_common(15),
        "judge_production_ready_rate": statistics.mean(r["judge"]["production_ready_rate"] for r in judged) if judged else None,
        "judge_mean_max_severity": statistics.mean(r["judge"]["mean_max_severity"] for r in judged) if judged else None,
    }


def compare(current: dict, baseline: dict) -> list:
    regressions = []
    for metric, allowed in THRESHOLDS.items():
        cur, base = current.get(metric), baseline.get(metric)
        if cur is None or base is None:
            continue
        delta = cur - base
        worse = delta > allowed if allowed > 0 else delta < allowed
        if worse:
            regressions.append(f"{metric}: {base:.3f} → {cur:.3f} (Δ {delta:+.3f}, allowed {allowed:+})")
    return regressions


def fmt(v):
    if v is None:
        return "—"
    if isinstance(v, float):
        return f"{v:.2f}"
    return str(v)


def write_report(path: Path, meta: dict, agg: dict, rows: list, regressions):
    lines = [f"# Drafting eval — {meta['run_id']}", "",
             f"API `{meta['api_url']}` · {agg['briefs']} briefs · judge: {meta.get('judge_model') or 'off'}", "",
             "## Aggregate", "", "| Metric | Value |", "|---|---|"]
    for k, v in agg.items():
        if k == "top_failing_questions":
            continue
        lines.append(f"| {k} | {fmt(v)} |")
    lines += ["", "## Most common failing review questions (round trip)", "",
              "| Question | Drafts |", "|---|---|"]
    lines += [f"| `{q}` | {c} |" for q, c in agg["top_failing_questions"]] or ["| — | — |"]
    lines += ["", "## Per brief", "",
              "| Brief | Status | s | Articles | Missing terms | Banned | Placeholders | Risk | Score | Failing Qs |",
              "|---|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        c = r.get("checks") or {}
        rv = r.get("review") or {}
        lines.append(" | ".join([
            f"| {r['id']}", r.get("status", "—"), fmt(r.get("seconds")), fmt(c.get("articles")),
            ", ".join(c.get("terms_missing", [])) or "—", ", ".join(c.get("banned_hits", [])) or "—",
            fmt(c.get("placeholder_count")), fmt(rv.get("overall_risk")), fmt(rv.get("risk_score")),
            fmt(len(rv.get("failing_questions", []))) + " |"]))
    if regressions is not None:
        lines += ["", "## Baseline comparison", ""]
        lines += [f"- **REGRESSION** {x}" for x in regressions] or ["No regressions beyond thresholds."]
    path.write_text("\n".join(lines) + "\n")

# ── Self-test (no API) ──────────────────────────────────────────────────────────


def self_test():
    brief = {"dealBrief": "License fee $750,000; 500 users.",
             "expect": {"terms_present": ["$750,000", "500", "Northwind"], "terms_absent": ["uptime"], "min_articles": 2}}
    html = ("<h2>ARTICLE 1 — DEFINITIONS</h2><p>1. \"Software\" means Northwind's product for 500 users.</p>"
            "<h2>ARTICLE 2 — PAYMENT</h2><p>1. Fee of 750,000 USD, see Clause 7. Late fee $9,999. [insert rate]</p>")
    c = deterministic_checks(brief, html)
    assert c["articles"] == 2 and c["numbering_ok"], c
    assert c["terms_missing"] == [], c
    assert c["bad_xrefs"] == [7], c
    assert c["placeholder_count"] == 1, c
    assert "9999" in c["invented_amounts"], c
    rows = [{"id": "x", "status": "INDEXED", "seconds": 10.0, "checks": c,
             "review": {"risk_score": 30.0, "overall_risk": "MEDIUM", "failing_questions": ["LIABILITY:a"]}}]
    agg = aggregate(rows)
    assert abs(agg["term_coverage"] - 1.0) < 1e-9 and agg["mean_risk_score"] == 30.0
    worse = dict(agg, mean_risk_score=40.0)
    assert compare(worse, agg), "should flag risk score regression"
    print("self-test OK")

# ── Main ────────────────────────────────────────────────────────────────────────


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--api-url", default=os.getenv("LP_EVAL_API_URL", "http://localhost:8080"))
    ap.add_argument("--email", default=os.getenv("LP_EVAL_EMAIL", "admin@legalpartner.local"))
    ap.add_argument("--password", default=os.getenv("LP_EVAL_PASSWORD"))
    ap.add_argument("--briefs", default=str(HERE / "briefs.yml"))
    ap.add_argument("--only", help="comma-separated brief ids")
    ap.add_argument("--out", default=str(HERE.parent / "results" / "drafting"))
    ap.add_argument("--run-id", default=dt.datetime.now().strftime("%Y%m%d-%H%M%S"))
    ap.add_argument("--baseline", help="previous summary.json to compare against")
    ap.add_argument("--timeout", type=int, default=1800, help="seconds per draft")
    ap.add_argument("--poll", type=int, default=10)
    ap.add_argument("--skip-review", action="store_true", help="skip the round-trip review")
    ap.add_argument("--judge-url", default=os.getenv("LP_EVAL_JUDGE_URL"), help="OpenAI-compatible /v1 URL")
    ap.add_argument("--judge-model", default=os.getenv("LP_EVAL_JUDGE_MODEL"))
    ap.add_argument("--judge-key", default=os.getenv("LP_EVAL_JUDGE_KEY", "no-op"))
    ap.add_argument("--insecure", action="store_true", help="skip TLS verification")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        self_test()
        return 0
    if not args.password:
        sys.exit("--password or LP_EVAL_PASSWORD is required")

    briefs = yaml.safe_load(Path(args.briefs).read_text())["briefs"]
    if args.only:
        wanted = {x.strip() for x in args.only.split(",")}
        briefs = [b for b in briefs if b["id"] in wanted]

    out = Path(args.out) / args.run_id
    (out / "drafts").mkdir(parents=True, exist_ok=True)
    api = Api(args.api_url, verify_tls=not args.insecure)
    api.login(args.email, args.password)

    rows = []
    for i, b in enumerate(briefs, 1):
        print(f"[{i}/{len(briefs)}] {b['id']} …", flush=True)
        row = {"id": b["id"], "templateId": b["templateId"]}
        try:
            d = run_draft(api, b, args.timeout, args.poll)
            row.update({k: d[k] for k in ("doc_id", "status", "seconds", "error", "validation")})
            (out / "drafts" / f"{b['id']}.html").write_text(d["html"])
            row["checks"] = deterministic_checks(b, d["html"])
            if d["status"] == "INDEXED" and not args.skip_review:
                row["review"] = run_review(api, d["doc_id"])
            if d["status"] == "INDEXED" and args.judge_url and args.judge_model:
                row["judge"] = judge_articles(b, d["html"], args.judge_url, args.judge_model, args.judge_key)
        except Exception as e:
            row.update({"status": "ERROR", "error": str(e)[:300], "checks": deterministic_checks(b, "")})
        rv = row.get("review") or {}
        print(f"    {row.get('status')} {row.get('seconds', '')}s · missing terms {len(row['checks']['terms_missing'])}"
              f" · risk {rv.get('overall_risk', '—')} {rv.get('risk_score', '')}"
              f" · failing Qs {len(rv.get('failing_questions', []))}", flush=True)
        rows.append(row)

    agg = aggregate(rows)
    meta = {"run_id": args.run_id, "api_url": args.api_url, "judge_model": args.judge_model,
            "briefs_file": args.briefs, "created": dt.datetime.now().isoformat()}
    regressions = None
    if args.baseline:
        baseline = json.loads(Path(args.baseline).read_text())["aggregate"]
        regressions = compare(agg, baseline)
    (out / "summary.json").write_text(json.dumps({"meta": meta, "aggregate": agg, "rows": rows}, indent=2))
    write_report(out / "report.md", meta, agg, rows, regressions)
    print(f"\nWrote {out/'report.md'}")
    if regressions:
        print("REGRESSIONS:\n  " + "\n  ".join(regressions))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
