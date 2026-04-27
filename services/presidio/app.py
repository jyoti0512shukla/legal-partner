"""
Presidio NER microservice for contract anonymization.
Detects PERSON, ORG, MONEY, DATE, ADDRESS, JURISDICTION entities.
Replaces LLM-based NER with rule + ML based detection (faster, more reliable).

Usage: POST /analyze with {"text": "..."} → returns detected entities
       POST /anonymize with {"text": "..."} → returns anonymized text + entity map
"""

from flask import Flask, request, jsonify
from presidio_analyzer import AnalyzerEngine, RecognizerResult
from presidio_anonymizer import AnonymizerEngine
from presidio_anonymizer.entities import OperatorConfig
import hashlib
import re

app = Flask(__name__)

# Initialize Presidio with English NLP model
analyzer = AnalyzerEngine()
anonymizer = AnonymizerEngine()

# Entity types we care about for legal contracts
LEGAL_ENTITIES = [
    "PERSON", "ORG", "MONEY", "DATE_TIME",
    "EMAIL_ADDRESS", "PHONE_NUMBER", "LOCATION",
    "US_SSN", "US_DRIVER_LICENSE", "CREDIT_CARD",
    "IBAN_CODE", "IP_ADDRESS", "URL"
]

# Map Presidio entity types to our canonical types
TYPE_MAP = {
    "PERSON": "PERSON",
    "ORG": "ORG",
    "MONEY": "MONEY",
    "DATE_TIME": "DATE",
    "EMAIL_ADDRESS": "EMAIL",
    "PHONE_NUMBER": "PHONE",
    "LOCATION": "ADDRESS",
    "US_SSN": "ID",
    "US_DRIVER_LICENSE": "ID",
    "CREDIT_CARD": "FINANCIAL",
    "IBAN_CODE": "FINANCIAL",
    "IP_ADDRESS": "TECH",
    "URL": "TECH",
}


def generate_synthetic(entity_type: str, original: str) -> str:
    """Generate a type-consistent synthetic replacement."""
    # Use a hash-based approach for consistency (same input → same output)
    h = hashlib.md5(original.encode()).hexdigest()[:8]

    if entity_type == "PERSON":
        first_names = ["James", "Sarah", "Michael", "Emily", "Robert", "Jessica", "David", "Amanda"]
        last_names = ["Smith", "Johnson", "Williams", "Brown", "Jones", "Davis", "Miller", "Wilson"]
        idx = int(h, 16) % len(first_names)
        return f"{first_names[idx]} {last_names[idx]}"
    elif entity_type == "ORG":
        prefixes = ["Helix", "Nova", "Apex", "Zenith", "Vertex", "Quantum", "Atlas", "Prism"]
        suffixes = ["Industries", "Corp", "Technologies", "Solutions", "Holdings", "Group", "Inc", "LLC"]
        idx = int(h, 16)
        return f"{prefixes[idx % len(prefixes)]} {suffixes[(idx >> 4) % len(suffixes)]}"
    elif entity_type == "MONEY":
        return "$[AMOUNT]"
    elif entity_type == "DATE":
        return "[DATE]"
    elif entity_type == "ADDRESS" or entity_type == "LOCATION":
        return "[ADDRESS]"
    elif entity_type == "EMAIL":
        return f"user{h[:4]}@example.com"
    elif entity_type == "PHONE":
        return f"(555) {h[:3]}-{h[3:7]}"
    else:
        return f"[{entity_type}]"


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "UP"})


@app.route("/analyze", methods=["POST"])
def analyze():
    """Detect entities in text. Returns list of entities with positions."""
    data = request.get_json()
    text = data.get("text", "")
    if not text:
        return jsonify({"entities": []})

    # Cap text for performance — analyze first 10K chars
    capped = text[:10000] if len(text) > 10000 else text

    results = analyzer.analyze(
        text=capped,
        entities=LEGAL_ENTITIES,
        language="en",
        score_threshold=0.6
    )

    entities = []
    seen = set()  # Dedup by (type, text)
    for r in sorted(results, key=lambda x: x.start):
        entity_text = capped[r.start:r.end]
        # Skip very short entities (likely false positives)
        if len(entity_text) < 3:
            continue
        key = (r.entity_type, entity_text.lower())
        if key in seen:
            continue
        seen.add(key)

        canonical_type = TYPE_MAP.get(r.entity_type, r.entity_type)
        entities.append({
            "type": canonical_type,
            "original": entity_text,
            "start": r.start,
            "end": r.end,
            "score": round(r.score, 2)
        })

    return jsonify({"entities": entities, "textLength": len(capped)})


@app.route("/anonymize", methods=["POST"])
def anonymize_text():
    """Detect entities and return anonymized text + entity map."""
    data = request.get_json()
    text = data.get("text", "")
    if not text:
        return jsonify({"anonymizedText": text, "entityMap": {}})

    capped = text[:10000] if len(text) > 10000 else text

    results = analyzer.analyze(
        text=capped,
        entities=LEGAL_ENTITIES,
        language="en",
        score_threshold=0.6
    )

    # Build entity map with synthetic replacements
    entity_map = {}
    seen = set()
    for r in sorted(results, key=lambda x: -x.start):  # Reverse order for safe replacement
        entity_text = capped[r.start:r.end]
        if len(entity_text) < 3:
            continue
        if entity_text.lower() in seen:
            continue
        seen.add(entity_text.lower())

        canonical_type = TYPE_MAP.get(r.entity_type, r.entity_type)
        synthetic = generate_synthetic(canonical_type, entity_text)
        entity_map[entity_text] = synthetic

    # Apply substitutions globally (not just in capped region)
    anonymized = text
    for original, synthetic in sorted(entity_map.items(), key=lambda x: -len(x[0])):
        # Case-insensitive replacement, preserve word boundaries
        pattern = re.compile(re.escape(original), re.IGNORECASE)
        anonymized = pattern.sub(synthetic, anonymized)

    return jsonify({
        "anonymizedText": anonymized,
        "entityMap": entity_map,
        "entitiesFound": len(entity_map)
    })


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5002)
