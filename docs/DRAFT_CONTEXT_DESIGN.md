# Draft Context Retrieval — Design Document

This document describes the context retrieval pipeline for AI-assisted contract drafting, designed around industry best practices for RAG-based legal drafting.

## Principles

1. **Context quality > quantity** — Fewer, highly relevant chunks outperform many noisy ones.
2. **Metadata-aware retrieval** — Filter by document type, jurisdiction, clause type before semantic ranking.
3. **Multi-query retrieval** — Different query formulations improve recall (liability vs indemnity vs ICA references).
4. **Document diversity** — Cap chunks per document to avoid over-representation from a single contract.
5. **Structured context** — Organize context with provenance (source, section, document type) so the LLM knows what it's citing.
6. **Graceful degradation** — When metadata filters return few matches, fall back to unfiltered retrieval.

## Pipeline

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 1. Multi-Query Retrieval                                                 │
│    - 4 query variants: liability/indemnity/cap/ICA                        │
│    - Query expansion (legal synonyms)                                    │
│    - Merge + dedupe by (document_id, chunk_index)                        │
│    - Top 30 candidates                                                    │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 2. Metadata Filtering                                                    │
│    - document_type: match NDA→NDA, MSA→MSA/SOW/VENDOR                    │
│    - jurisdiction: match or "India" fallback                              │
│    - clause_type: LIABILITY, INDEMNITY, GENERAL only                      │
│    - Fallback: if < 3 matches, use unfiltered top candidates             │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 3. Document Diversity                                                    │
│    - Max 3 chunks per document                                           │
│    - Prevents one long contract from dominating context                   │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 4. Re-Ranking                                                            │
│    - Vector similarity (0.7) + keyword overlap (0.2) + recency (0.1)     │
│    - Top 12 chunks                                                       │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 5. Context Structuring                                                   │
│    - [Source N: filename | section | doc_type | jurisdiction]            │
│    - Decrypt chunk text                                                  │
│    - Truncate to 8000 chars total                                        │
└─────────────────────────────────────────────────────────────────────────┘
```

## Configuration

```yaml
legalpartner:
  draft:
    retrieval:
      candidate-count: 30    # Initial retrieval pool
      top-k: 12             # After re-ranking
      max-chunks-per-doc: 3  # Diversity cap
      context-max-chars: 8000  # LLM context budget
```

## Query Variants (Liability Clause)

| Query | Purpose |
|-------|---------|
| liability limitation indemnity cap damages exclusion consequential | Broad semantic |
| limitation of liability aggregate cap direct damages indirect | Specific phrasing |
| indemnify hold harmless defend claims breach | Indemnity angle |
| Indian Contract Act Section 73 74 liquidated damages | Indian law citations |

## Metadata Stored per Chunk

- `document_id`, `file_name`, `section_path`
- `document_type` (NDA, MSA, SOW, etc.)
- `jurisdiction`, `year`, `practice_area`, `client_name`
- `clause_type` (LIABILITY, INDEMNITY, TERMINATION, etc.)

## Future Enhancements

- **Hybrid search**: Add BM25/keyword search alongside vector (requires PGVector full-text or separate index).
- **Cross-encoder re-ranking**: Use a small model to re-rank top-20 for better precision.
- **Clause-type-specific retrievers**: Separate retrieval configs per clause type (termination, confidentiality, etc.).
- **Metadata filtering at DB level**: When PgVector supports metadata filters, push filtering to the store.
