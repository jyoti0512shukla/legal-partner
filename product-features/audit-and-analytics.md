# Audit Logging & Analytics — Legal Partner

Complete audit trail, workflow analytics, and AI feedback collection.

---

## 1. Audit Logging

**Controller:** `AuditController` at `/api/v1/audit`

Every significant action in the platform is logged to the `audit_logs` table via Spring's event system.

### 31 Audit Action Types

**Document operations:**
- DOCUMENT_UPLOADED, DOCUMENT_DELETED, DOCUMENT_INDEXED

**AI operations:**
- AI_QUERY, AI_COMPARISON, AI_RISK_ASSESSMENT, AI_EXTRACTION, AI_DRAFT, AI_REVIEW

**Auth events:**
- LOGIN_SUCCESS, LOGIN_FAILED, MFA_ENABLED, MFA_DISABLED, PASSWORD_CHANGED, ACCOUNT_LOCKED, ACCOUNT_UNLOCKED

**Workflow lifecycle:**
- WORKFLOW_STARTED, WORKFLOW_COMPLETED, WORKFLOW_FAILED, WORKFLOW_CANCELLED

**Connector events:**
- CONNECTOR_EMAIL_SENT, CONNECTOR_EMAIL_FAILED
- CONNECTOR_SLACK_SENT, CONNECTOR_SLACK_FAILED
- CONNECTOR_TEAMS_SENT, CONNECTOR_TEAMS_FAILED
- CONNECTOR_WEBHOOK_SENT, CONNECTOR_WEBHOOK_FAILED

**Integration events:**
- INTEGRATION_CONNECTED, INTEGRATION_DISCONNECTED

**Agent events:**
- AGENT_ANALYSIS_TRIGGERED, AGENT_ANALYSIS_COMPLETED

**Matter events:**
- MATTER_CREATED, MATTER_UPDATED

**Other:**
- PLAYBOOK_CREATED, USER_CREATED, USER_DELETED

### Audit log entry

| Field | Description |
|-------|-------------|
| timestamp | When the action occurred |
| username | Who performed the action |
| userRole | ADMIN, PARTNER, or ASSOCIATE |
| action | One of the 31 action types |
| documentId | Related document (if applicable) |
| details | JSON blob with action-specific details |

### Endpoints

| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/logs` | GET | Paginated audit logs with filters (user, role, action, date range, documentId) |
| `/users` | GET | Distinct usernames in audit log (for filter dropdowns) |
| `/stats` | GET | Audit statistics |
| `/export` | GET | Export filtered logs as CSV download |
| `/recent` | GET | Recent activity feed (last 20 entries) |

### Audit statistics

`GET /api/v1/audit/stats` returns:
- Total events
- Total uploads, queries, comparisons, risk assessments
- By-user breakdown (action counts per user)
- By-day timeline (action counts per day)

### CSV export

`GET /api/v1/audit/export?startDate=...&endDate=...&user=...&action=...`

Downloads a CSV file with all matching audit entries. Useful for compliance reporting and external audit requirements.

---

## 2. Workflow Analytics

**Endpoint:** `GET /api/v1/workflows/analytics`

Tracks execution metrics across all workflows for the authenticated user.

### Analytics response

| Field | Description |
|-------|-------------|
| totalRuns | Total workflow executions |
| completedRuns | Successfully completed |
| failedRuns | Failed with error |
| runningRuns | Currently in progress |
| completionRate | `completed / total * 100` (1 decimal) |
| avgDurationMs | Average execution time in milliseconds |
| byWorkflow | Per-workflow breakdown: `{name, runs, completed}` |
| byDay | Daily run counts for last 7 days |

### Workflow run export

`GET /api/v1/workflows/runs/{id}/export` — returns full JSON export of a single workflow run including all step results, metadata, and timing.

---

## 3. AI Feedback Collection

**Controller:** `FeedbackController` at `/api/v1/feedback`

Users can rate AI-generated answers and provide corrections.

### Submit feedback

```
POST /api/v1/feedback
{
  "conversationId": "uuid",
  "queryText": "What is the liability cap?",
  "answerText": "The liability cap is...",
  "rating": 4,
  "isCorrect": true,
  "correctedAnswer": null,
  "note": "Good answer but missed the IP carve-out",
  "matterId": "uuid"
}
```

### Feedback fields

| Field | Description |
|-------|-------------|
| rating | 1-5 star rating |
| isCorrect | Boolean — was the answer factually correct? |
| correctedAnswer | User-provided correct answer (if isCorrect=false) |
| note | Free-text note |
| queryText | The original question |
| answerText | The AI's answer |
| matterId | Associated matter (optional) |

### Feedback statistics

`GET /api/v1/feedback/stats` returns:
- Average rating across all feedback
- Count of answers marked as incorrect

This data helps the team track AI quality over time and identify areas where the model needs improvement.

---

## 4. Feature Flags

**Endpoint:** `GET /api/v1/config/features`

Returns currently enabled features:
- `workflowsEnabled` — whether the workflow engine is active

Feature flags allow enabling/disabling major features without redeployment.
