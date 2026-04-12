# Matter Management & Deal Intelligence — Legal Partner

Matters are the central organizing concept — a deal, transaction, or legal engagement. Documents, findings, workflows, and team members are all scoped to matters.

---

## 1. Matter CRUD

**Controller:** `MatterController` at `/api/v1/matters`

| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/` | POST | Create a matter (ADMIN/PARTNER only) |
| `/` | GET | List all matters (filtered by membership for non-ADMINs) |
| `/active` | GET | List active matters only |
| `/{id}` | GET | Get matter detail (membership check) |
| `/{id}` | PUT | Update a matter |
| `/{id}/status` | PATCH | Change matter status |
| `/{id}/review-pipeline` | PATCH | Set default review pipeline |
| `/{id}/documents` | GET | List matter documents (paginated) |

### Matter fields

| Field | Description |
|-------|-------------|
| name | Matter name (e.g., "Acme Corp Series B Financing") |
| matterRef | Reference number (e.g., "2024-CORP-001") |
| clientName | Client name |
| practiceArea | Practice area (CORPORATE, LITIGATION, IP, etc.) |
| status | ACTIVE, CLOSED, or ARCHIVED |
| description | Free-text description |
| dealType | Type of deal (used for playbook matching) |
| defaultPlaybook | The negotiation playbook used for auto-analysis |
| reviewPipeline | The approval pipeline for this matter |

### Access control

- **ADMIN** users can see and manage all matters
- **Non-ADMIN** users can only see matters they are a member of
- `MatterAccessService.requireMembership()` enforces this on every matter operation

---

## 2. Matter Team

| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/{id}/team` | GET | List matter team members |
| `/{id}/team` | POST | Add a team member |
| `/{id}/team/add-team` | POST | Add an entire team (bulk) |
| `/{id}/team/{memberId}` | DELETE | Remove a team member |

### Member roles

| Role | Permissions |
|------|------------|
| LEAD_PARTNER | Full access, receives PARTNER-level notifications |
| PARTNER | Full access, receives PARTNER-level notifications |
| ASSOCIATE | Standard access (cannot see confidential docs), receives ASSOCIATE notifications |
| PARALEGAL | Limited access, receives PARALEGAL notifications |
| OBSERVER | Read-only access |

---

## 3. Deal Intelligence Agent

**Service:** `MatterAgentService`

An automated background agent that analyzes every document linked to a matter. Triggered when a document is uploaded to or linked to an active matter.

### What it does

```
Document linked to matter (via MatterDocumentEvent)
    ↓
Acquire semaphore (max 3 concurrent, configurable)
    ↓
Load agent configuration
    ↓
If checkPlaybook enabled AND matter has a default playbook:
    Run playbook comparison:
    - Compare each clause in the document against playbook positions
    - Flag deviations (PLAYBOOK_DEVIATION finding)
    - Flag non-negotiable violations (PLAYBOOK_NON_NEGOTIABLE finding)
    ↓
If crossReferenceDocs enabled:
    Run cross-document conflict detection:
    - Compare extracted fields across all documents in the matter
    - Fields checked: liability_cap, governing_law, notice_period,
      arbitration_venue, contract_value
    - Flag inconsistencies (CROSS_DOC_CONFLICT finding)
    ↓
Save findings to database
    ↓
Notify matter members (if configured)
    ↓
Publish AGENT_ANALYSIS_COMPLETED audit event
```

### Batch re-analysis

`POST /api/v1/matters/{id}/agent/reanalyze` — deletes existing playbook findings and re-analyzes all documents in the matter. Useful after changing the default playbook.

### Agent configuration

**Endpoint:** `GET/PUT /api/v1/agent/config`

| Setting | Default | Description |
|---------|---------|-------------|
| autoAnalyzeOnUpload | true | Trigger analysis when docs are uploaded |
| crossReferenceDocs | true | Enable cross-document conflict detection |
| checkPlaybook | true | Enable playbook comparison |
| notifyOnHigh | IN_APP | Notification channel for HIGH severity |
| notifyOnMedium | IN_APP | Notification channel for MEDIUM severity |
| notifyOnLow | NONE | Notification channel for LOW severity |
| quietHoursStart | null | Start of quiet hours (notifications suppressed) |
| quietHoursEnd | null | End of quiet hours |
| maxConcurrent | 3 | Max simultaneous analysis threads |

Notification channels: IN_APP, SLACK, EMAIL, TEAMS, NONE

---

## 4. Matter Findings

**Controller:** `FindingsController`

Findings are the output of the deal intelligence agent. Each finding represents a risk, deviation, or conflict detected in a matter's documents.

### Finding types

| Type | Source | Description |
|------|--------|-------------|
| PLAYBOOK_DEVIATION | Playbook comparison | Clause deviates from firm's standard position |
| PLAYBOOK_NON_NEGOTIABLE | Playbook comparison | Non-negotiable clause is missing or violates firm policy |
| CROSS_DOC_CONFLICT | Cross-document detection | Two documents in the same matter have conflicting terms |
| MISSING_DOCUMENT | System check | Expected document type is missing from the matter |
| INFO | Various | Informational finding (no action required) |

### Finding severities

- **HIGH** — requires immediate attention (e.g., non-negotiable violation, conflicting governing law)
- **MEDIUM** — should be reviewed (e.g., clause deviates from playbook but is within acceptable range)
- **LOW** — informational (e.g., minor wording difference from playbook)

### Finding status workflow

```
NEW → ACCEPTED (finding acknowledged, will be addressed)
NEW → REJECTED (finding dismissed as non-issue)
ACCEPTED → RESOLVED (issue has been fixed in the contract)
```

### Findings dashboard

**Endpoint:** `GET /api/v1/findings/dashboard`

Returns:
- Global severity counts (HIGH / MEDIUM / LOW across all matters)
- Unreviewed finding count
- Recent findings (last 10)
- Per-matter risk summary (matter name, high/medium/low counts)

### Per-matter findings

**Endpoint:** `GET /api/v1/matters/{matterId}/findings`

Filterable by severity and status. Each finding includes:
- Finding type, severity, status
- Document reference
- Clause type affected
- Description of the issue
- Playbook position (for playbook findings)
- Conflicting document and value (for cross-doc conflicts)

---

## 5. Playbook Comparison Detail

**Service:** `PlaybookComparisonService`

Compares a document's clauses against the matter's playbook positions one by one.

### Comparison logic

```
For each PlaybookPosition in the matter's default playbook:
    1. Extract the corresponding clause from the document
    2. Compare against the playbook's standardPosition
    3. Verdict:
       - MATCHES: clause aligns with firm's standard position
       - DEVIATES: clause exists but differs from standard position
       - MISSING: clause not found in the document
    4. If position is nonNegotiable AND verdict is DEVIATES or MISSING:
       → Create PLAYBOOK_NON_NEGOTIABLE finding (always HIGH severity)
    5. If verdict is DEVIATES (and not non-negotiable):
       → Create PLAYBOOK_DEVIATION finding (severity based on deviation degree)
```

---

## 6. Cross-Document Conflict Detection

**Service:** `CrossDocConflictDetector`

Compares extracted key terms across all documents in a matter to find inconsistencies.

### Fields compared

| Field | Example conflict |
|-------|-----------------|
| liability_cap | Doc A: "12 months of fees", Doc B: "total contract value" |
| governing_law | Doc A: "State of Delaware", Doc B: "State of New York" |
| notice_period | Doc A: "30 days", Doc B: "60 days" |
| arbitration_venue | Doc A: "New York, NY", Doc B: "San Francisco, CA" |
| contract_value | Doc A: "$2.4M", Doc B: "$1.8M" |

When a conflict is detected, a CROSS_DOC_CONFLICT finding is created with both document references and the conflicting values.
