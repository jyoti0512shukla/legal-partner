# Integrations — Legal Partner

All external service integrations: cloud storage, communication, DMS, and data sources.

---

## 1. Integration Management

**Controller:** `IntegrationController` at `/api/v1/integrations`

| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/connections` | GET | Status of all integration connections for the user |
| `/auth-url` | GET | Get OAuth authorization URL for a provider |
| `/callback` | GET | Handle OAuth callback (exchange code for tokens) |
| `/slack/configure` | POST | Configure Slack webhook URL |
| `/teams/configure` | POST | Configure Teams webhook URL |
| `/disconnect` | DELETE | Disconnect an integration |

---

## 2. Communication Integrations

### Slack
- **Auth:** Webhook URL (user configures in settings)
- **Used by:** Workflow connectors, review pipeline notifications, agent finding notifications
- **Message format:** Markdown (`*bold*`, `` `code` ``)

### Microsoft Teams
- **Auth:** Webhook URL (user configures in settings)
- **Used by:** Workflow connectors, review pipeline notifications, agent finding notifications
- **Message format:** HTML (with `<br>` line breaks)

### Email (SMTP)
- **Auth:** Server-level SMTP configuration
- **Supports:** Gmail, SendGrid, AWS SES, any SMTP provider
- **Used by:** Invite emails, password reset, workflow connectors, review notifications, agent notifications
- **Template:** Styled HTML with dark header, white card, action buttons, deep links
- **Matter access validation:** Email recipients are validated against matter membership before sending

---

## 3. Cloud Storage Integrations

**Controller:** `CloudStorageController` at `/api/v1/cloud-storage`

### Google Drive
- **Auth:** OAuth2
- **Capabilities:** Browse folders, import files, save documents back
- **Config:** `legalpartner.cloud.google-drive.client-id`, `client-secret`

### OneDrive
- **Auth:** OAuth2 (Microsoft Graph API)
- **Capabilities:** Browse folders, import files, save documents back
- **Config:** `legalpartner.cloud.onedrive.client-id`, `client-secret`

### Dropbox
- **Auth:** OAuth2
- **Capabilities:** Browse folders, import files, save documents back
- **Config:** `legalpartner.cloud.dropbox.client-id`, `client-secret`

### Cloud import flow

```
User connects via OAuth → browses folders → selects files → import
    ↓
Backend downloads file from cloud provider
    ↓
Creates DocumentMetadata (source: CLOUD)
    ↓
Processes through standard indexing pipeline
```

### Save to cloud

Push any document (including AI-generated drafts) back to the user's connected cloud storage.

---

## 4. Document Management System (DMS) Integrations

### NetDocuments
- **Auth:** OAuth2
- **Provider class:** `NetDocumentsProvider`
- **Config:** `legalpartner.integrations.netdocuments.*`

### iManage
- **Auth:** OAuth2
- **Provider class:** `IManageProvider`
- **Config:** `legalpartner.integrations.imanage.*`

Both DMS integrations allow importing documents from and exporting documents to the firm's document management system.

---

## 5. E-Signature Integration

### DocuSign
- **Auth:** OAuth2
- **Provider class:** `DocuSignProvider`
- **Config:** `legalpartner.integrations.docusign.*`
- **Status:** Integration connection framework is in place. The SEND_FOR_SIGNATURE workflow step currently returns a pending status placeholder.

---

## 6. SEC EDGAR (Data Source)

**Controller:** `EdgarImportController` at `/api/v1/edgar`

Not a user-facing integration (no OAuth). The platform queries SEC EDGAR's public EFTS API to search for and import real commercial contracts (EX-10.x exhibits) to seed the RAG corpus.

- **10 preset search queries** (IT Services MSA, SaaS Agreement, NDA, etc.)
- **Custom search** supported
- **Rate limiting** respects EDGAR's 10 req/s limit
- **Filtering:** EX-10.x exhibits only, 2019-2024, max 500KB per document

---

## 7. Integration Connection Model

All integrations use a common `IntegrationConnection` entity:

| Field | Description |
|-------|-------------|
| user | The user who connected |
| provider | SLACK, MICROSOFT_TEAMS, DOCUSIGN, NETDOCUMENTS, IMANAGE |
| status | CONNECTED, DISCONNECTED |
| config | JSON blob (webhook URLs, OAuth tokens, etc.) |
| connectedAt | When the connection was established |

OAuth tokens are refreshed automatically on use. Webhook URLs are stored as-is.

---

## 8. Feature Toggle

Each integration provider can be individually enabled/disabled in the configuration:

```yaml
legalpartner:
  integrations:
    docusign:
      enabled: true
      client-id: ...
      client-secret: ...
    netdocuments:
      enabled: false
    imanage:
      enabled: false
  cloud:
    google-drive:
      enabled: true
      client-id: ...
    onedrive:
      enabled: true
      client-id: ...
    dropbox:
      enabled: false
```

Disabled providers don't show in the UI and their OAuth endpoints return 404.
