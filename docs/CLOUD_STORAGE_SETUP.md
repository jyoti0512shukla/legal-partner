# Cloud Storage Integrations

Legal Partner supports direct import from **Google Drive**, **OneDrive/SharePoint**, and **Dropbox**. Users connect their accounts via OAuth and browse/import documents into the corpus.

---

## Overview

| Provider | OAuth | API | Import | Save |
|----------|-------|-----|--------|------|
| Google Drive | OAuth 2.0 | Drive API v3 | ✅ | ✅ |
| OneDrive / SharePoint | Microsoft Graph | Graph API v1.0 | ✅ | ✅ |
| Dropbox | OAuth 2.0 | Dropbox API v2 | ✅ | ✅ |

**Save to Cloud**: Drafts can be saved directly to connected cloud storage (Draft page → Save to Cloud). Users who connected before the save feature was added may need to disconnect and reconnect to grant write scope.

---

## Configuration

Enable providers and set credentials via environment variables or `application.yml`:

```yaml
legalpartner:
  cloud:
    frontend-url: ${LEGALPARTNER_CLOUD_FRONTEND_URL:http://localhost:5173}
    backend-url: ${LEGALPARTNER_CLOUD_BACKEND_URL:http://localhost:8080}
    google:
      enabled: ${LEGALPARTNER_GOOGLE_DRIVE_ENABLED:false}
      client-id: ${LEGALPARTNER_GOOGLE_DRIVE_CLIENT_ID:}
      client-secret: ${LEGALPARTNER_GOOGLE_DRIVE_CLIENT_SECRET:}
    microsoft:
      enabled: ${LEGALPARTNER_ONEDRIVE_ENABLED:false}
      client-id: ${LEGALPARTNER_ONEDRIVE_CLIENT_ID:}
      client-secret: ${LEGALPARTNER_ONEDRIVE_CLIENT_SECRET:}
      tenant: ${LEGALPARTNER_ONEDRIVE_TENANT:common}
    dropbox:
      enabled: ${LEGALPARTNER_DROPBOX_ENABLED:false}
      app-key: ${LEGALPARTNER_DROPBOX_APP_KEY:}
      app-secret: ${LEGALPARTNER_DROPBOX_APP_SECRET:}
```

---

## Google Drive Setup

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create or select a project
3. Enable **Google Drive API**
4. **APIs & Services → Credentials → Create Credentials → OAuth 2.0 Client ID**
5. Application type: **Web application**
6. Add **Authorized redirect URIs** and ensure scope includes `drive.file` (for save) or `drive` (full access):
   - Local: `http://localhost:8080/api/v1/cloud-storage/callback`
   - Prod: `https://your-api-domain.com/api/v1/cloud-storage/callback`
7. Copy Client ID and Client Secret
8. Set env vars:
   ```bash
   LEGALPARTNER_GOOGLE_DRIVE_ENABLED=true
   LEGALPARTNER_GOOGLE_DRIVE_CLIENT_ID=your-client-id
   LEGALPARTNER_GOOGLE_DRIVE_CLIENT_SECRET=your-client-secret
   ```

---

## OneDrive / Microsoft Graph Setup

1. Go to [Azure Portal](https://portal.azure.com/) → **App registrations → New registration**
2. Name: e.g. `Legal Partner`
3. Supported account types: **Accounts in any organizational directory and personal Microsoft accounts**
4. Redirect URI: **Web** → `http://localhost:8080/api/v1/cloud-storage/callback`
5. After creation: **Certificates & secrets** → New client secret
6. **API permissions** → Add:
   - `Files.Read` (Delegated)
   - `User.Read` (Delegated)
   - `offline_access` (Delegated)
7. Copy Application (client) ID and client secret
8. Set env vars:
   ```bash
   LEGALPARTNER_ONEDRIVE_ENABLED=true
   LEGALPARTNER_ONEDRIVE_CLIENT_ID=your-app-id
   LEGALPARTNER_ONEDRIVE_CLIENT_SECRET=your-client-secret
   LEGALPARTNER_ONEDRIVE_TENANT=common
   ```

---

## Dropbox Setup

1. Go to [Dropbox App Console](https://www.dropbox.com/developers/apps)
2. Create app → **Scoped access** → **Full Dropbox** or **App folder**
3. App name: e.g. `Legal Partner`
4. **Settings** → OAuth 2 → **Redirect URI**:
   - `http://localhost:8080/api/v1/cloud-storage/callback`
5. Copy **App key** and **App secret**
6. Set env vars:
   ```bash
   LEGALPARTNER_DROPBOX_ENABLED=true
   LEGALPARTNER_DROPBOX_APP_KEY=your-app-key
   LEGALPARTNER_DROPBOX_APP_SECRET=your-app-secret
   ```

---

## OAuth Redirect URI

The OAuth callback is served by the **backend**. Configure the redirect URI in each provider's console as:

- **Local**: `http://localhost:8080/api/v1/cloud-storage/callback`
- **Production**: `https://<your-backend-host>/api/v1/cloud-storage/callback`

After OAuth, users are redirected to the frontend: `{frontend-url}/documents?cloud=connected`.

---

## User Flow

1. User opens **Documents** → clicks **Import from Cloud**
2. Selects provider (Google Drive, OneDrive, Dropbox)
3. Clicks **Connect** → redirected to provider's OAuth page
4. Authorizes → redirected back to Legal Partner
5. Clicks **Browse & Import** → navigates folders, selects file
6. Fills metadata (document type, client, jurisdiction, etc.)
7. Clicks **Import & Index** → document is downloaded and ingested into the corpus

---

## Supported File Types

- PDF
- DOCX
- HTML

Same as direct upload.
