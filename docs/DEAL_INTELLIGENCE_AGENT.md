# Deal Intelligence Agent — Implementation Plan

## What it does

Proactive, configurable agent that automatically analyzes documents when they're linked to a matter. Checks against firm playbooks, detects cross-document conflicts, and surfaces findings without anyone clicking "run."

**The difference from workflows:** Nobody asks for it. Upload a document to a matter → findings appear automatically.

## Architecture

```
Associate uploads MSA to Acme matter
  → Document indexed (technical — RAG only)
  → Document linked to matter (business event — agent triggers)
      │
      ├── Matter is ACTIVE? ✓
      ├── Matter has playbook? ✓ (SaaS Acquisition Standard)
      │
      ├── Extract key terms from document
      ├── Compare each clause against playbook positions
      │   → IP Assignment: DEVIATES (non-negotiable!) 🔴
      │   → Liability cap: DEVIATES (below minimum) 🟡
      │   → Termination: MATCHES ✓
      │
      ├── Cross-reference against other matter docs
      │   → MSA liability cap contradicts Side Letter 🔴
      │
      ├── Store findings in matter_findings table
      └── Notify: Slack alert to matter team (HIGH severity)
```

## Key Design Decision: Matter-level triggers, not document-level

The agent hooks into **matter events**, not document indexing events.

| Why | Explanation |
|-----|------------|
| **Matter gives context** | Deal type, playbook, other documents, team members |
| **Docs without matter = no analysis** | Personal uploads, test docs don't waste LLM calls |
| **Late linking works** | Doc uploaded first, linked to matter later → agent fires on link |
| **Matter status matters** | CLOSED matters don't trigger analysis |
| **Playbook lives on matter** | Agent needs `matter.defaultPlaybook` to know what to check |

### Events that trigger the agent

| Event | When | Agent action |
|-------|------|-------------|
| `MatterDocumentEvent` (type=LINKED) | Doc uploaded with matter, OR existing doc linked to matter | Full analysis: extract → playbook → cross-doc → findings |
| `MatterDocumentEvent` (type=PLAYBOOK_CHANGED) | Playbook changed on a matter | Re-analyze all matter documents against new playbook |

### Events published from

| Source | Event |
|--------|-------|
| `DocumentService.ingestDocument()` | When doc uploaded with matterId |
| `MatterService.linkDocument()` | When existing doc linked to matter |
| `MatterService.updateMatter()` | When playbook changed on matter |

---

## Database Schema (V15)

### New tables

```sql
-- Playbooks: firm's standard positions per deal type
CREATE TABLE playbooks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    deal_type       VARCHAR(50) NOT NULL,
    description     TEXT,
    is_default      BOOLEAN NOT NULL DEFAULT false,
    created_by      UUID NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Playbook positions: per-clause standards
CREATE TABLE playbook_positions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    playbook_id         UUID NOT NULL REFERENCES playbooks(id) ON DELETE CASCADE,
    clause_type         VARCHAR(50) NOT NULL,
    standard_position   TEXT NOT NULL,
    minimum_acceptable  TEXT,
    non_negotiable      BOOLEAN NOT NULL DEFAULT false,
    notes               TEXT
);

-- Matter findings: accumulated intelligence per matter
CREATE TABLE matter_findings (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    matter_id               UUID NOT NULL REFERENCES matters(id) ON DELETE CASCADE,
    document_id             UUID REFERENCES document_metadata(id) ON DELETE SET NULL,
    finding_type            VARCHAR(50) NOT NULL,   -- PLAYBOOK_DEVIATION, PLAYBOOK_NON_NEGOTIABLE,
                                                    -- CROSS_DOC_CONFLICT, MISSING_DOCUMENT, INFO
    severity                VARCHAR(10) NOT NULL,   -- HIGH, MEDIUM, LOW
    clause_type             VARCHAR(50),
    title                   TEXT NOT NULL,
    description             TEXT NOT NULL,
    section_ref             TEXT,
    playbook_position_id    UUID REFERENCES playbook_positions(id) ON DELETE SET NULL,
    related_document_id     UUID REFERENCES document_metadata(id) ON DELETE SET NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'NEW',  -- NEW, REVIEWED, ACCEPTED, FLAGGED
    reviewed_by             UUID REFERENCES users(id),
    reviewed_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Agent config (single-tenant, single row)
CREATE TABLE agent_config (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auto_analyze_on_upload  BOOLEAN NOT NULL DEFAULT true,
    cross_reference_docs    BOOLEAN NOT NULL DEFAULT true,
    check_playbook          BOOLEAN NOT NULL DEFAULT true,
    notify_high             VARCHAR(50) NOT NULL DEFAULT 'IN_APP',
    notify_medium           VARCHAR(50) NOT NULL DEFAULT 'IN_APP',
    notify_low              VARCHAR(50) NOT NULL DEFAULT 'NONE',
    quiet_hours_start       TIME,
    quiet_hours_end         TIME,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO agent_config (auto_analyze_on_upload, cross_reference_docs, check_playbook)
VALUES (true, true, true);

-- Add deal context to matters
ALTER TABLE matters ADD COLUMN IF NOT EXISTS deal_type VARCHAR(50);
ALTER TABLE matters ADD COLUMN IF NOT EXISTS default_playbook_id UUID REFERENCES playbooks(id);
```

---

## Implementation Phases

### Phase 0: Schema + Enums (half day)

**Create:**
- `V15__deal_intelligence_agent.sql` — all tables above
- `DealType.java` — SAAS_ACQUISITION, M_AND_A, NDA, COMMERCIAL_LEASE, FINANCING, IP_LICENSE, EMPLOYMENT, GENERAL
- `FindingType.java` — PLAYBOOK_DEVIATION, PLAYBOOK_NON_NEGOTIABLE, CROSS_DOC_CONFLICT, MISSING_DOCUMENT, INFO
- `FindingSeverity.java` — HIGH, MEDIUM, LOW
- `FindingStatus.java` — NEW, REVIEWED, ACCEPTED, FLAGGED
- `NotifyChannel.java` — IN_APP, SLACK, EMAIL, TEAMS, NONE

**Modify:**
- `AuditActionType.java` — add AGENT_ANALYSIS_TRIGGERED, AGENT_ANALYSIS_COMPLETED, AGENT_FINDING_REVIEWED

### Phase 1: Entities + Repos + DTOs (1 day)

**Create entities:**
- `Playbook.java` — id, name, dealType, description, isDefault, createdBy, positions (OneToMany)
- `PlaybookPosition.java` — id, playbook (ManyToOne), clauseType, standardPosition, minimumAcceptable, nonNegotiable, notes
- `MatterFinding.java` — id, matter, document, findingType, severity, clauseType, title, description, sectionRef, playbookPosition, relatedDocument, status, reviewedBy, reviewedAt, createdAt
- `AgentConfig.java` — single-row config entity

**Create repos:**
- `PlaybookRepository` — findByDealType, findByDealTypeAndIsDefaultTrue, findAllByOrderByCreatedAtDesc
- `PlaybookPositionRepository` — findByPlaybookId
- `MatterFindingRepository` — findByMatterId, findByMatterIdAndSeverity, findByMatterIdAndStatus, countByMatterIdAndStatus, summary query (GROUP BY severity)
- `AgentConfigRepository`

**Create DTOs (in model/dto/agent/):**
- `PlaybookDto` — record
- `PlaybookPositionDto` — record
- `PlaybookCreateRequest` — record with validation
- `MatterFindingDto` — record
- `FindingSummaryDto` — record (highCount, mediumCount, lowCount, unreviewedCount)
- `AgentConfigDto` — record
- `FindingReviewRequest` — record (status)

**Modify:**
- `Matter.java` — add dealType (String), defaultPlaybook (ManyToOne to Playbook)
- `MatterRequest.java` — add dealType, defaultPlaybookId
- `MatterResponse.java` — add dealType, defaultPlaybookId, findingCount

### Phase 2: Playbook Service + Controller (half day)

**Create:**
- `PlaybookService.java`
  - listPlaybooks(), getPlaybook(id), getPositions(playbookId)
  - createPlaybook(req, username), updatePlaybook(id, req), deletePlaybook(id)
  - listByDealType(dealType)
- `PlaybookController.java` at `/api/v1/playbooks`
  - CRUD endpoints, PARTNER/ADMIN only for create/update/delete

### Phase 3: Agent Core (2 days)

**Create:**

**`MatterDocumentEvent.java`** — the business event
```java
public record MatterDocumentEvent(
    UUID matterId,
    UUID documentId,
    String eventType,    // "LINKED" or "PLAYBOOK_CHANGED"
    String triggeredBy   // username
) {}
```

**`PlaybookComparisonService.java`**
- compareClauses(matter, document, extractionResult) → List<MatterFinding>
- For each playbook position: LLM call comparing extracted clause against standard position
- LLM returns: MATCHES / DEVIATES / MISSING + severity + explanation
- Non-negotiable deviations always HIGH severity
- Run position comparisons in parallel (CompletableFuture, 3-4 concurrent)

**`CrossDocConflictDetector.java`**
- detectConflicts(matter, newDocument, extractionResult) → List<MatterFinding>
- Compare key terms (liability cap, governing law, notice period, etc.) across all matter docs
- Uses already-persisted extraction fields on DocumentMetadata (not fresh LLM calls)
- Flags when same term has different values in different documents

**`MatterAgentService.java`** — the brain
```
@Async analyzeDocument(matterId, documentId, username):
  1. Load agent config — bail if disabled
  2. Load matter — bail if not ACTIVE or no playbook
  3. Extract key terms (reuse AiService.extractKeyTerms)
  4. Persist extraction results to DocumentMetadata fields
  5. If checkPlaybook enabled → PlaybookComparisonService
  6. If crossReferenceDocs enabled → CrossDocConflictDetector
  7. Save all findings to matter_findings
  8. Notify if HIGH severity findings exist
  9. Audit log: AGENT_ANALYSIS_COMPLETED

@Async reanalyzeAllDocuments(matterId, username):
  — called when playbook changes
  — deletes existing playbook findings for matter
  — re-runs analyzeDocument for each doc in matter
```

**`AgentConfigService.java`**
- getConfig() — returns single row, creates default if missing
- updateConfig(dto) — updates the single row

### Phase 4: Event Trigger (half day)

**Create:**

**`AgentTriggerService.java`**
```java
@Service
@RequiredArgsConstructor
public class AgentTriggerService {

    private final MatterAgentService agentService;
    private final AgentConfigService configService;

    @Async
    @EventListener
    public void onMatterDocumentEvent(MatterDocumentEvent event) {
        AgentConfig config = configService.getConfig();
        if (!config.isAutoAnalyzeOnUpload()) return;

        if ("LINKED".equals(event.eventType())) {
            agentService.analyzeDocument(
                event.matterId(), event.documentId(), event.triggeredBy());
        } else if ("PLAYBOOK_CHANGED".equals(event.eventType())) {
            agentService.reanalyzeAllDocuments(
                event.matterId(), event.triggeredBy());
        }
    }
}
```

**Modify — publish events from:**

1. `DocumentService.ingestDocument()` — after doc saved with matter:
```java
if (doc.getMatter() != null) {
    eventPublisher.publishEvent(new MatterDocumentEvent(
        doc.getMatter().getId(), doc.getId(), "LINKED", username));
}
```

2. `MatterService` — if matter playbook changes:
```java
if (oldPlaybookId != newPlaybookId) {
    eventPublisher.publishEvent(new MatterDocumentEvent(
        matter.getId(), null, "PLAYBOOK_CHANGED", username));
}
```

### Phase 5: Findings API + Notifications (half day)

**Create:**

**`FindingsController.java`** at `/api/v1/matters/{matterId}/findings`
- GET / — list findings (filterable by severity, status)
- GET /summary — counts by severity + unreviewed count
- PATCH /{findingId} — review finding (set status, reviewedBy, reviewedAt)
- Protected by matter membership (MatterAccessService)

**`AgentNotificationService.java`**
- notifyIfNeeded(findings, config, matter)
- Check severity vs config notification channels
- Check quiet hours
- Dispatch via SlackWebhookProvider / MicrosoftTeamsProvider / JavaMailSender
- Recipients = matter team members

### Phase 6: Frontend — Playbook Management (1 day)

**Create:**
- `PlaybooksPage.jsx`
  - List playbooks as cards (name, deal type badge, position count, default badge)
  - Create/edit modal with positions table
  - Positions: clause type dropdown, standard position textarea, minimum textarea, non-negotiable toggle
  - PARTNER/ADMIN only

**Modify:**
- `App.jsx` — add /playbooks route
- `Sidebar.jsx` — add Playbooks nav item for PARTNER/ADMIN

### Phase 7: Frontend — Matter Intelligence Panel (1 day)

**Create:**
- `MatterFindingsPanel.jsx`
  - Fetches findings + summary for a matter
  - Groups by severity (🔴🟡🟢)
  - Each finding: title, description, section ref, document name, clause type badge
  - Review buttons: "Acceptable" / "Flag for Negotiation"
  - Summary bar: "3 HIGH / 5 MEDIUM / 2 LOW | 8 unreviewed"
  - Auto-polls every 30s for new findings

**Modify:**
- `MattersPage.jsx` — embed MatterFindingsPanel in expanded matter view, add findings badge

### Phase 8: Frontend — Agent Config in Settings (half day)

**Create:**
- `AgentConfigTab.jsx`
  - Toggle switches: auto-analyze, cross-reference, playbook check
  - Notification channel dropdowns per severity
  - Quiet hours time pickers
  - Link to Playbooks page

**Modify:**
- `SettingsPage.jsx` — add "Deal Intelligence" tab

### Phase 9: Frontend — Matter Creation Updates (half day)

**Modify:**
- `MattersPage.jsx` (CreateMatterModal)
  - Add "Deal Type" dropdown
  - Add "Playbook" dropdown (filtered by deal type)
  - Fetch playbooks on mount

- `MatterService.java`
  - Handle dealType and defaultPlaybookId in createMatter
  - Inject PlaybookRepository

---

## File Summary

### New files: 31

**Backend (28):**
- 1 migration
- 5 enums
- 4 entities
- 4 repositories
- 7 DTOs
- 1 event class (MatterDocumentEvent)
- 4 services (PlaybookService, MatterAgentService, PlaybookComparisonService, CrossDocConflictDetector)
- 1 service (AgentConfigService)
- 1 service (AgentTriggerService)
- 1 service (AgentNotificationService)
- 3 controllers (PlaybookController, FindingsController, AgentConfigController)

**Frontend (3):**
- PlaybooksPage.jsx
- MatterFindingsPanel.jsx
- AgentConfigTab.jsx

### Modified files: 9

**Backend (5):**
- Matter.java — add dealType, defaultPlaybook
- MatterRequest.java — add fields
- MatterResponse.java — add fields
- MatterService.java — handle new fields, publish events
- AuditActionType.java — add agent actions
- DocumentService.java — publish MatterDocumentEvent on upload with matter

**Frontend (4):**
- App.jsx — add route
- Sidebar.jsx — add nav item
- MattersPage.jsx — deal type/playbook in create modal, findings panel
- SettingsPage.jsx — add agent config tab

---

## Build Order

```
Phase 0 (schema + enums)
  → Phase 1 (entities + repos + DTOs)
    → Phase 2 (playbook CRUD)
    → Phase 3 (agent core) — can start parallel with Phase 2
      → Phase 4 (event trigger)
        → Phase 5 (findings API + notifications)
          → Phase 6 (playbook UI) — can start after Phase 2
          → Phase 7 (findings UI) — can start after Phase 5
          → Phase 8 (config UI) — can start after Phase 5
          → Phase 9 (matter form) — can start after Phase 2
```

**MVP (Phases 0-5):** ~4 days. Upload doc to matter → findings appear automatically.
**Full feature (Phases 0-9):** ~8 days. Complete UI for playbooks, findings, config.
