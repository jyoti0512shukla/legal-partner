# Playbooks & Clause Library — Legal Partner

Firm-level negotiation standards and reusable clause templates.

---

## 1. Playbooks

A playbook defines the firm's standard negotiation positions for a specific deal type. Each playbook contains positions for multiple clause types.

### Playbook CRUD

**Controller:** `PlaybookController` at `/api/v1/playbooks`

| Endpoint | Method | Access | What it does |
|----------|--------|--------|-------------|
| `/` | GET | All | List playbooks (optionally filtered by dealType) |
| `/{id}` | GET | All | Get a specific playbook with positions |
| `/{id}/positions` | GET | All | Get all clause positions for a playbook |
| `/` | POST | ADMIN/PARTNER | Create a new playbook |
| `/{id}` | PUT | ADMIN/PARTNER | Update a playbook |
| `/{id}` | DELETE | ADMIN/PARTNER | Delete a playbook |

### Playbook entity

| Field | Description |
|-------|-------------|
| name | Playbook name (e.g., "Standard SaaS Vendor Agreement") |
| dealType | Type of deal this playbook applies to |
| description | Free-text description of when to use this playbook |
| isDefault | Whether this is the default playbook for its deal type |
| positions | List of PlaybookPosition entries |

### Playbook Position

Each position defines the firm's stance on a specific clause type:

| Field | Description |
|-------|-------------|
| clauseType | Which clause (e.g., "LIABILITY", "TERMINATION") |
| standardPosition | The firm's preferred language (the "ask") |
| minimumAcceptable | The floor — what the firm will accept at minimum |
| nonNegotiable | Boolean — if true, any deviation is HIGH severity |
| notes | Internal notes for the negotiating attorney |

### How playbooks are used

1. **Matter agent:** When a document is linked to a matter with a default playbook, the deal intelligence agent automatically compares each clause against the playbook positions and generates findings.

2. **Workflow context:** The REDLINE_SUGGESTIONS workflow step uses the playbook's standard positions as the basis for suggested replacement language.

3. **Manual review:** Attorneys can pull up the playbook positions alongside a contract to manually check compliance.

---

## 2. Clause Library

A library of reusable, curated clause templates organized by type, contract type, practice area, and industry.

### Clause Library CRUD

**Controller:** `ClauseLibraryController` at `/api/v1/clause-library`

| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/` | GET | List all clause library entries |
| `/{clauseType}` | GET | List entries by clause type |
| `/` | POST | Create a new clause library entry |
| `/{id}/golden` | PATCH | Toggle "golden" status |
| `/{id}` | DELETE | Delete a clause entry |

### Clause Library Entry

| Field | Description |
|-------|-------------|
| clauseType | Clause category (LIABILITY, CONFIDENTIALITY, etc.) |
| title | Human-readable title (e.g., "Standard Liability Cap — SaaS") |
| content | The full clause text (HTML or plain text) |
| contractType | Which contract type this clause is for (MSA, SaaS, NDA, etc.) |
| practiceArea | Practice area (CORPORATE, IP, etc.) |
| industry | Industry sector |
| counterpartyType | Type of counterparty (enterprise, SMB, government) |
| jurisdiction | Jurisdiction this clause is drafted for |
| golden | Boolean — "golden" clauses are firm-standard, preferred in drafting |
| usageCount | How many times this clause has been used in draft generation |

### Golden clauses

Marking a clause as "golden" means it's the firm's approved standard for that clause type. Golden clauses are:
- **Preferred in drafting:** The RAG context retriever prioritizes golden clauses from the clause library when generating drafts
- **Used in redlines:** REDLINE_SUGGESTIONS prefer golden clause language as the replacement text
- **Visually distinguished in the UI:** Golden badge on the clause card

### Usage tracking

Every time a clause library entry is used as RAG context in draft generation, its `usageCount` is incremented. This helps the firm understand which clauses are actually being used in practice.

### Context-aware retrieval

When the drafting engine retrieves clause library entries for RAG context, it filters by:
- clauseType (exact match)
- contractType (if available)
- practiceArea (if available)
- industry (if available)

This ensures that a SaaS liability clause isn't used as context when drafting an employment agreement's liability clause.
