#!/usr/bin/env python3
"""
ContractIQ Evaluation Harness

Uploads contracts to the API, runs extraction + risk assessment,
compares results against expected YAML ground truth, reports accuracy.

Usage:
    python3 eval/run_eval.py --api-url https://legal.cognita-ai.com
    python3 eval/run_eval.py --api-url http://localhost:8080 --contract nda-mutual-tech.pdf

Requires: pip install requests pyyaml
"""

import argparse
import json
import os
import sys
import time
from pathlib import Path

import requests
import yaml


def login(api_url, email="admin@legalpartner.local", password="Admin123!"):
    r = requests.post(f"{api_url}/api/v1/auth/login",
                      json={"email": email, "password": password}, verify=False)
    r.raise_for_status()
    return r.json()["token"]


def upload_document(api_url, token, filepath, doc_type="OTHER"):
    with open(filepath, "rb") as f:
        r = requests.post(f"{api_url}/api/v1/documents/upload",
                          files={"file": (os.path.basename(filepath), f)},
                          data={"documentType": doc_type},
                          headers={"Authorization": f"Bearer {token}"},
                          verify=False)
    r.raise_for_status()
    return r.json()["id"]


def wait_for_indexing(api_url, token, doc_id, timeout=120):
    for _ in range(timeout // 5):
        r = requests.get(f"{api_url}/api/v1/documents/{doc_id}",
                         headers={"Authorization": f"Bearer {token}"}, verify=False)
        status = r.json().get("processingStatus", "PENDING")
        if status == "INDEXED":
            return True
        if status == "FAILED":
            return False
        time.sleep(5)
    return False


def run_extraction(api_url, token, doc_id):
    r = requests.post(f"{api_url}/api/v1/ai/extract/{doc_id}",
                      headers={"Authorization": f"Bearer {token}"}, verify=False,
                      timeout=180)
    r.raise_for_status()
    return r.json()


def run_risk_assessment(api_url, token, doc_id):
    r = requests.post(f"{api_url}/api/v1/ai/risk-assessment/{doc_id}",
                      headers={"Authorization": f"Bearer {token}"}, verify=False,
                      timeout=300)
    r.raise_for_status()
    return r.json()


def compare_extractions(expected, actual_entries):
    """Compare expected extractions against actual pipeline output."""
    results = {"correct": 0, "wrong": 0, "missing": 0, "extra": 0, "details": []}

    expected_fields = expected.get("expected_extractions", {})
    actual_map = {}
    for entry in actual_entries:
        key = entry.get("canonicalField") or entry.get("rawField", "")
        if entry.get("value"):
            actual_map[key.lower()] = entry["value"]

    for field, expected_val in expected_fields.items():
        actual_val = actual_map.get(field.lower())
        if actual_val is None:
            results["missing"] += 1
            results["details"].append({"field": field, "expected": expected_val, "actual": None, "match": "MISSING"})
        elif expected_val.lower() in actual_val.lower() or actual_val.lower() in expected_val.lower():
            results["correct"] += 1
            results["details"].append({"field": field, "expected": expected_val, "actual": actual_val, "match": "CORRECT"})
        else:
            results["wrong"] += 1
            results["details"].append({"field": field, "expected": expected_val, "actual": actual_val, "match": "WRONG"})

    # Count extra fields not in expected
    for key in actual_map:
        if key not in [f.lower() for f in expected_fields]:
            results["extra"] += 1

    total = results["correct"] + results["wrong"] + results["missing"]
    results["recall"] = results["correct"] / total if total > 0 else 0
    results["precision"] = results["correct"] / (results["correct"] + results["wrong"]) if (results["correct"] + results["wrong"]) > 0 else 0

    return results


def compare_risk(expected, actual_categories):
    """Compare expected risk ratings against actual."""
    results = {"correct": 0, "wrong": 0, "details": []}

    expected_ratings = expected.get("expected_risk_ratings", {})
    actual_map = {}
    for cat in actual_categories:
        actual_map[cat["name"].upper()] = cat["rating"]

    for clause, expected_rating in expected_ratings.items():
        actual_rating = actual_map.get(clause.upper())
        if actual_rating is None:
            results["details"].append({"clause": clause, "expected": expected_rating, "actual": "NOT_ASSESSED", "match": "MISSING"})
            results["wrong"] += 1
        elif actual_rating == expected_rating:
            results["correct"] += 1
            results["details"].append({"clause": clause, "expected": expected_rating, "actual": actual_rating, "match": "CORRECT"})
        else:
            results["wrong"] += 1
            results["details"].append({"clause": clause, "expected": expected_rating, "actual": actual_rating, "match": "WRONG"})

    total = results["correct"] + results["wrong"]
    results["accuracy"] = results["correct"] / total if total > 0 else 0

    return results


def compare_clauses(expected, actual_categories):
    """Compare expected present/missing clauses against actual."""
    actual_present = {cat["name"].upper() for cat in actual_categories if cat.get("clauseReference") != "Not present"}
    actual_missing = {cat["name"].upper() for cat in actual_categories if cat.get("clauseReference") == "Not present"}

    expected_present = set(c.upper() for c in expected.get("expected_clauses_present", []))
    expected_missing = set(c.upper() for c in expected.get("expected_clauses_missing", []))

    true_positives = len(expected_present & actual_present)
    false_negatives = len(expected_present - actual_present)  # present but not detected
    false_positives = len(expected_missing & actual_present)   # missing but detected as present

    total = true_positives + false_negatives + false_positives
    accuracy = true_positives / (true_positives + false_negatives) if (true_positives + false_negatives) > 0 else 0

    return {
        "true_positives": true_positives,
        "false_negatives": false_negatives,
        "false_positives": false_positives,
        "clause_detection_accuracy": accuracy,
    }


def evaluate_contract(api_url, token, contract_path, expected_path, results_dir):
    name = Path(contract_path).stem
    print(f"\n{'='*60}")
    print(f"Evaluating: {name}")
    print(f"{'='*60}")

    with open(expected_path) as f:
        expected = yaml.safe_load(f)

    doc_type = expected.get("contract_type", "OTHER")

    # Upload
    print(f"  Uploading {contract_path}...")
    doc_id = upload_document(api_url, token, contract_path, doc_type)
    print(f"  Document ID: {doc_id}")

    # Wait for indexing
    print("  Waiting for indexing...")
    if not wait_for_indexing(api_url, token, doc_id):
        print("  FAILED: Document indexing failed or timed out")
        return None

    # Run extraction
    print("  Running extraction pipeline...")
    try:
        extraction = run_extraction(api_url, token, doc_id)
        extraction_results = compare_extractions(expected, extraction.get("entries", []))
        print(f"  Extraction: recall={extraction_results['recall']:.0%}, precision={extraction_results['precision']:.0%}")
    except Exception as e:
        print(f"  Extraction FAILED: {e}")
        extraction = {}
        extraction_results = {"recall": 0, "precision": 0}

    # Run risk assessment
    print("  Running risk assessment...")
    try:
        risk = run_risk_assessment(api_url, token, doc_id)
        risk_results = compare_risk(expected, risk.get("categories", []))
        clause_results = compare_clauses(expected, risk.get("categories", []))
        print(f"  Risk accuracy: {risk_results['accuracy']:.0%}")
        print(f"  Clause detection: {clause_results['clause_detection_accuracy']:.0%}")
    except Exception as e:
        print(f"  Risk assessment FAILED: {e}")
        risk = {}
        risk_results = {"accuracy": 0}
        clause_results = {"clause_detection_accuracy": 0}

    # Save results
    result = {
        "contract": name,
        "doc_id": doc_id,
        "extraction": extraction_results,
        "risk": risk_results,
        "clauses": clause_results,
        "raw_extraction": extraction,
        "raw_risk": risk,
    }

    os.makedirs(results_dir, exist_ok=True)
    with open(os.path.join(results_dir, f"{name}_results.json"), "w") as f:
        json.dump(result, f, indent=2, default=str)

    return result


def main():
    parser = argparse.ArgumentParser(description="ContractIQ Evaluation Harness")
    parser.add_argument("--api-url", default="https://legal.cognita-ai.com")
    parser.add_argument("--email", default="admin@legalpartner.local")
    parser.add_argument("--password", default="Admin123!")
    parser.add_argument("--contract", help="Run single contract (filename)")
    parser.add_argument("--contracts-dir", default="eval/contracts")
    parser.add_argument("--expected-dir", default="eval/expected")
    parser.add_argument("--results-dir", default="eval/results")
    args = parser.parse_args()

    print("ContractIQ Evaluation Harness")
    print(f"API: {args.api_url}")

    token = login(args.api_url, args.email, args.password)
    print(f"Logged in as {args.email}")

    contracts_dir = Path(args.contracts_dir)
    expected_dir = Path(args.expected_dir)

    if args.contract:
        contract_path = contracts_dir / args.contract
        expected_path = expected_dir / (Path(args.contract).stem + ".yml")
        if not contract_path.exists():
            print(f"Contract not found: {contract_path}")
            sys.exit(1)
        if not expected_path.exists():
            print(f"Expected results not found: {expected_path}")
            sys.exit(1)
        evaluate_contract(args.api_url, token, str(contract_path), str(expected_path), args.results_dir)
        return

    # Run all contracts
    results = []
    for contract_file in sorted(contracts_dir.glob("*")):
        if contract_file.suffix not in (".pdf", ".docx", ".html", ".txt"):
            continue
        expected_file = expected_dir / (contract_file.stem + ".yml")
        if not expected_file.exists():
            print(f"  Skipping {contract_file.name} — no expected results file")
            continue
        result = evaluate_contract(args.api_url, token, str(contract_file), str(expected_file), args.results_dir)
        if result:
            results.append(result)

    # Summary
    if results:
        print(f"\n{'='*60}")
        print("SUMMARY")
        print(f"{'='*60}")
        avg_extraction_recall = sum(r["extraction"]["recall"] for r in results) / len(results)
        avg_extraction_precision = sum(r["extraction"]["precision"] for r in results) / len(results)
        avg_risk_accuracy = sum(r["risk"]["accuracy"] for r in results) / len(results)
        avg_clause_accuracy = sum(r["clauses"]["clause_detection_accuracy"] for r in results) / len(results)

        print(f"  Contracts evaluated: {len(results)}")
        print(f"  Extraction recall:   {avg_extraction_recall:.0%}")
        print(f"  Extraction precision: {avg_extraction_precision:.0%}")
        print(f"  Risk rating accuracy: {avg_risk_accuracy:.0%}")
        print(f"  Clause detection:     {avg_clause_accuracy:.0%}")

        summary = {
            "total_contracts": len(results),
            "avg_extraction_recall": avg_extraction_recall,
            "avg_extraction_precision": avg_extraction_precision,
            "avg_risk_accuracy": avg_risk_accuracy,
            "avg_clause_detection": avg_clause_accuracy,
            "per_contract": [{
                "name": r["contract"],
                "extraction_recall": r["extraction"]["recall"],
                "risk_accuracy": r["risk"]["accuracy"],
            } for r in results]
        }
        with open(os.path.join(args.results_dir, "summary.json"), "w") as f:
            json.dump(summary, f, indent=2)
        print(f"\n  Results saved to {args.results_dir}/")


if __name__ == "__main__":
    main()
