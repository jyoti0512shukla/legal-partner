# Review Pipelines — Legal Partner

Multi-stage human approval workflows for contract documents. This is the human-in-the-loop complement to the AI-powered agentic workflows.

---

## 1. Pipeline Structure

A review pipeline defines a sequence of approval stages. Each stage specifies who can act and what actions are available.

### Pipeline CRUD

**Controller:** `ReviewPipelineController` at `/api/v1/review-pipelines`

| Endpoint | Method | Access | What it does |
|----------|--------|--------|-------------|
| `/` | GET | All | List all pipelines |
| `/{id}` | GET | All | Get a pipeline with its stages |
| `/` | POST | ADMIN/PARTNER | Create a pipeline with stages |
| `/{id}` | PUT | ADMIN/PARTNER | Update a pipeline |
| `/{id}` | DELETE | ADMIN/PARTNER | Delete a pipeline |

### Pipeline entity

| Field | Description |
|-------|-------------|
| name | Pipeline name (e.g., "Standard Two-Stage Approval") |
| description | When to use this pipeline |
| isDefault | Default pipeline for new matters |
| stages | Ordered list of ReviewStage entries |

### Review Stage

| Field | Description |
|-------|-------------|
| stageOrder | Execution order (1, 2, 3...) |
| name | Stage name (e.g., "Associate Review", "Partner Approval") |
| requiredRole | Who can act (PARTNER, ASSOCIATE, PARALEGAL, ADMIN) |
| actions | Comma-separated valid actions (e.g., "APPROVE,RETURN") |
| autoNotify | Automatically notify stage members when review reaches this stage |

---

## 2. Review Flow

### Starting a review

```
POST /api/v1/review-pipelines/reviews/start
{
  "pipelineId": "uuid",
  "matterId": "uuid",
  "documentId": "uuid"
}
```

Creates a MatterReview entity:
- Status: IN_PROGRESS
- currentStage: first stage in the pipeline
- If first stage has autoNotify: sends notifications to stage members

### Taking action

```
POST /api/v1/review-pipelines/reviews/{reviewId}/action
{
  "action": "APPROVE",
  "username": "jane@firm.com"
}
```

### Action handling

| Action | What happens |
|--------|-------------|
| **APPROVE** | Advances to next stage. If no next stage: sets status to APPROVED, records completedAt. |
| **RETURN** | Moves back to previous stage (e.g., Partner returns to Associate for rework). |
| **SEND** | Sets status to SENT (used for sending to counterparty). Records completedAt. |

Every action is logged as a `ReviewAction` entity for the audit trail.

### State diagram

```
                    ┌──────────────┐
                    │  IN_PROGRESS │
                    │  Stage 1     │
                    └──────┬───────┘
                           │ APPROVE
                    ┌──────▼───────┐
                    │  IN_PROGRESS │
                    │  Stage 2     │◄──── RETURN (from Stage 3)
                    └──────┬───────┘
                           │ APPROVE
                    ┌──────▼───────┐
                    │  IN_PROGRESS │
                    │  Stage 3     │
                    └──────┬───────┘
                           │ APPROVE (last stage)
                    ┌──────▼───────┐
                    │   APPROVED   │
                    └──────────────┘

At any stage: SEND → SENT (final state)
```

---

## 3. Role Routing

When a review reaches a stage, the system maps the stage's `requiredRole` to matter members:

| Pipeline Role | Matter Roles Matched |
|---------------|---------------------|
| PARTNER | LEAD_PARTNER, PARTNER |
| ASSOCIATE | ASSOCIATE |
| PARALEGAL | PARALEGAL |
| ADMIN | LEAD_PARTNER |
| null / unrecognized | All matter members |

---

## 4. Multi-Channel Notifications

When a review advances to a stage with `autoNotify = true`, notifications are sent to all matching matter members.

### Slack notification
```
*Review {action}*
Pipeline: *{pipelineName}*
Now at: *{stageName}*
Matter: {matterName}
Document: {docName}
By: {actorName}

Your action is needed on this review.
```

### Microsoft Teams notification
Same content, sent via Teams webhook with HTML line breaks.

### Email notification
Styled HTML email with:
- **Color-coded action badge:**
  - Approved: green (#22c55e)
  - Returned: amber (#f59e0b)
  - Started: indigo (#6366f1)
- Pipeline name, current stage, matter name, document name, actor name
- **"Take Action" button** linking to `/matters/{matterId}`

One email sent to all matching members (not per-member).

---

## 5. Review Dashboard

**Endpoint:** `GET /api/v1/review-pipelines/dashboard`

Returns three sections based on the user's role:

| Section | What it shows |
|---------|--------------|
| **Needs action** | In-progress reviews where current stage's requiredRole matches the user's role |
| **Team activity** | Other in-progress reviews on the user's matters (informational) |
| **Recently completed** | Up to 10 recently APPROVED reviews on the user's matters |

---

## 6. Review History

**Endpoint:** `GET /api/v1/review-pipelines/reviews/{reviewId}/actions`

Returns the complete action history for a review — who did what, when, at which stage. Used for audit trail and compliance purposes.

---

## 7. Matter Integration

- Each matter can have a **default review pipeline** assigned (`PATCH /api/v1/matters/{id}/review-pipeline`)
- Reviews are scoped to a specific matter + document
- Multiple reviews can run on the same matter (different documents)
- `GET /api/v1/review-pipelines/reviews/matter/{matterId}` — list all reviews for a matter
