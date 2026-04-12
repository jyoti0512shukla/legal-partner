# AI Capabilities — Legal Partner

All AI-powered features exposed through the platform, outside of the multi-step agentic workflows (documented separately in [agentic-workflows.md](agentic-workflows.md)).

---

## 1. RAG-Powered Q&A (Intelligence Chat)

**Endpoint:** `POST /api/v1/ai/query`

Ask natural-language questions about any contract in the corpus. The system retrieves relevant passages, generates an answer grounded in the actual contract text, and cites its sources.

### RAG pipeline (10-stage)

```
User question
    ↓
1. Query expansion — generates 2-3 semantic variants of the question for broader retrieval
    ↓
2. Embedding — embeds query via Ollama all-minilm
    ↓
3. Vector retrieval — fetches top 200 candidates from pgvector (encrypted at rest, decrypted on read)
    ↓
4. Matter-scoped filtering — if matterId provided, only chunks from that matter's documents
    ↓
5. Deduplication — merges primary + expanded query results, removes duplicate chunks
    ↓
6. Re-ranking — scores candidates by combined vector similarity (0.7), keyword overlap (0.2), document type match (0.1)
    ↓
7. Token budgeting — dynamically calculates how many chunks fit in the context window
   Model window: 8192 tokens, minus history, minus query, minus 512-token answer headroom
    ↓
8. Decryption — decrypts selected chunk content via Jasypt
    ↓
9. LLM generation — sends context + question + conversation history to LLM
    ↓
10. Post-processing:
    - Citation extraction — identifies which source documents were referenced
    - Confidence calibration — rates answer confidence
    - Faithfulness check — verifies answer is grounded in the provided context
```

### Conversation memory

- In-memory conversation history per session
- Per-turn question embeddings stored for semantic relevance
- **Semantic pruning:** Only includes past turns whose question is semantically relevant to the current query (cosine similarity threshold: 0.35)
- **Rolling summarization:** When history exceeds 800 tokens, compresses old turns into a summary, keeps last 2 turns verbatim
- **Hard cap:** 10 turns per conversation

### Configuration

```yaml
legalpartner:
  rag:
    candidate-count: 20
    top-k: 5
    context-max-chars: 6000
    retrieve-candidates: 200
  context:
    model-window-tokens: 8192
    answer-headroom-tokens: 512
  conversation:
    max-history-turns: 10
    summarize-threshold-tokens: 800
    keep-verbatim-turns: 2
    semantic-relevance-threshold: 0.35
```

---

## 2. Risk Assessment

**Endpoint:** `POST /api/v1/ai/risk-assessment/{docId}`

Analyzes a contract for legal and commercial risks across 9 standard categories.

### 3-pass batched assessment

```
Pass 1: INVENTORY
  Scans full document text for 9 clause types using keyword matching:
  Liability, Indemnity, Termination, Confidentiality, IP Rights,
  Governing Law, Force Majeure, Payment, Warranties
    ↓
Pass 2: TOKEN-BUDGETED ANALYSIS
  Packs found clauses into batches within 3500-token budget
  Analyzes risk per clause via LLM (guided JSON → CSV fallback → line-by-line fallback)
    ↓
Pass 3: MISSING CLAUSE DETECTION
  Any standard clause not found in Pass 1 is flagged as HIGH risk
```

### Output per category

```json
{
  "name": "LIABILITY",
  "rating": "HIGH",
  "justification": "Liability cap set at 12 months of fees but excludes IP indemnification carve-outs",
  "sectionRef": "Clause 8.2"
}
```

### Overall risk computation

- **HIGH:** 3+ high-risk categories, OR 1+ high-risk category that is a missing clause
- **MEDIUM:** 1+ high-risk categories, OR 4+ medium-risk categories
- **LOW:** Otherwise

### Risk Drilldown

**Endpoint:** `POST /api/v1/ai/risk-drilldown/{docId}`

Deep dive into a specific risk category. Returns: specific risk explanation, business impact, suggested fix, and replacement language.

---

## 3. Key Term Extraction

**Endpoint:** `POST /api/v1/ai/extract/{docId}`

Extracts structured fields from a contract:

| Field | Example |
|-------|---------|
| Party A | "Acme Corporation" |
| Party B | "Beta Industries, Inc." |
| Effective Date | "January 1, 2025" |
| Expiry Date | "December 31, 2027" |
| Contract Value | "$2,400,000" |
| Liability Cap | "12 months of fees" |
| Governing Law | "State of Delaware" |
| Notice Period (Days) | "30" |
| Arbitration Venue | "New York, NY" |

Extracted values are stored on the `DocumentMetadata` entity for use in cross-document comparison and matter-level analysis.

---

## 4. Document Comparison

**Endpoint:** `POST /api/v1/ai/compare`

Compares two contracts side-by-side across 7 legal dimensions:

1. **Liability** — cap structure, carve-outs, consequential damages exclusion
2. **Indemnity** — scope, procedure, IP infringement coverage
3. **Termination** — for cause, for convenience, notice periods, wind-down
4. **Confidentiality** — definition breadth, exceptions, survival period
5. **Governing Law** — jurisdiction, venue, arbitration vs litigation
6. **Force Majeure** — trigger events, notice, termination rights
7. **IP Rights** — ownership, license grants, work product assignment

### Output per dimension

```json
{
  "dimension": "LIABILITY",
  "doc1Summary": "Liability capped at 12 months of fees, excludes indirect damages",
  "doc2Summary": "Liability capped at contract value, includes consequential damages up to the cap",
  "moreFavorableTo": "DOC_1",
  "reasoning": "Doc 1 provides stronger protection by excluding indirect damages entirely..."
}
```

---

## 5. Contract Review (Clause Checklist)

**Endpoint:** `POST /api/v1/review`

Audits a contract against 12 canonical clause types. For each clause, determines presence, quality, and risk.

### 12 canonical clauses

| ID | Clause Name |
|----|-------------|
| LIABILITY_LIMIT | Limitation of Liability |
| INDEMNITY | Indemnification |
| TERMINATION_CONVENIENCE | Termination for Convenience |
| TERMINATION_CAUSE | Termination for Cause |
| FORCE_MAJEURE | Force Majeure |
| CONFIDENTIALITY | Confidentiality / NDA |
| GOVERNING_LAW | Governing Law |
| DISPUTE_RESOLUTION | Dispute Resolution / Arbitration |
| IP_OWNERSHIP | Intellectual Property Ownership |
| DATA_PROTECTION | Data Protection |
| PAYMENT_TERMS | Payment Terms |
| ASSIGNMENT | Assignment / Change of Control |

### Per-clause output

```json
{
  "clauseId": "LIABILITY_LIMIT",
  "clauseName": "Limitation of Liability",
  "status": "WEAK",
  "riskLevel": "HIGH",
  "sectionRef": "Section 8.2",
  "finding": "Cap exists but does not exclude IP infringement or data breach claims",
  "recommendation": "Add carve-outs for IP indemnification and data breach liability"
}
```

### Generation strategy

Primary: vLLM guided JSON (Outlines constrained decoding, maxTokens=1200)
Fallback 1: CSV completions (`LIABILITY_LIMIT=PRESENT-LOW,INDEMNITY=WEAK-MEDIUM,...`)
Fallback 2: Pipe-delimited line-by-line parsing
Fallback 3: Prose response with regex extraction

---

## 6. Redline Suggestions

Generated as part of workflows (REDLINE_SUGGESTIONS step). Produces specific replacement language for weak or missing clauses.

### Per-suggestion output

```json
{
  "clauseType": "LIABILITY",
  "originalText": "The aggregate liability shall not exceed the fees paid in the prior 12 months.",
  "suggestedLanguage": "The aggregate liability of each Party under this Agreement shall not exceed the total fees paid or payable under this Agreement in the twelve (12) months preceding the claim, provided that this limitation shall not apply to (a) breaches of confidentiality, (b) IP infringement indemnification, or (c) willful misconduct.",
  "sectionRef": "Section 8.2",
  "rationale": "The current clause lacks standard carve-outs for confidentiality breaches, IP indemnification, and willful misconduct, which are commercially expected in enterprise SaaS agreements."
}
```

---

## 7. Executive Summary

Generated as part of workflows (GENERATE_SUMMARY step). Receives all prior analysis results and produces a concise executive summary.

### Output

```json
{
  "executiveSummary": "This is a 3-year Master Services Agreement between Acme Corp and Beta Inc...",
  "topConcerns": [
    "Liability cap does not include IP indemnification carve-out",
    "No data breach notification timeline specified"
  ],
  "recommendations": [
    "Negotiate IP indemnification carve-out from liability cap",
    "Add 72-hour breach notification requirement with supervisory authority notification"
  ]
}
```

---

## 8. Clause Refinement

**Endpoint:** `POST /api/v1/ai/refine-clause`

AI-powered clause editing. User provides a clause and an instruction (e.g., "make this more protective for the buyer", "add a force majeure carve-out"), and the LLM rewrites the clause accordingly.

---

## LLM Provider Support

The platform supports two LLM backends:

| Provider | Use Case | Configuration |
|----------|----------|---------------|
| **vLLM** (default) | All AI features. Supports guided_json via Outlines for guaranteed-valid JSON output. OpenAI-compatible API. | `legalpartner.chat-provider: vllm` |
| **Gemini** | Alternative provider. Google Gemini 2.5 Flash via API key. | `legalpartner.chat-provider: gemini` |

**Embedding:** Ollama all-minilm (local) or external embedding API
**Vector store:** PostgreSQL with pgvector extension
