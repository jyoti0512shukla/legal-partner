# Authentication & Administration — Legal Partner

User authentication, MFA, password policy, invite flow, and admin panel.

---

## 1. Authentication

**Controller:** `AuthController` at `/api/v1/auth`

### Login flow

```
POST /api/v1/auth/login {email, password}
    ↓
Check account lockout (5 failed attempts → 15 min lock)
    ↓
Validate credentials
    ↓
If MFA enabled:
    Return {mfaRequired: true}
    ↓
    POST /api/v1/auth/mfa/validate {email, code}
    ↓
    Validate TOTP code
    ↓
Set JWT in HttpOnly cookie (Strict SameSite)
    ↓
Return user info
```

### JWT configuration

| Setting | Default |
|---------|---------|
| Token expiry | 60 minutes |
| Remember-me expiry | 10,080 minutes (7 days) |
| Cookie flags | HttpOnly, Secure, SameSite=Strict |
| Storage | Cookie only (not bearer token) |

### Account lockout

| Setting | Default |
|---------|---------|
| Max failed attempts | 5 |
| Lock duration | 15 minutes |
| Counter reset | On successful login |

---

## 2. Multi-Factor Authentication (MFA)

TOTP-based (Google Authenticator, Authy, etc.)

| Endpoint | What it does |
|----------|-------------|
| `POST /mfa/setup` | Generates TOTP secret + QR code URL |
| `POST /mfa/verify` | Verifies first code to enable MFA |
| `POST /mfa/validate` | Validates code during login (2FA step) |
| `POST /mfa/disable` | Disables MFA |

**Implementation:** SHA1 algorithm, 6-digit codes, 30-second window.

---

## 3. Password Policy

Configurable via admin panel or API.

| Setting | Default |
|---------|---------|
| Minimum length | 12 |
| Maximum length | 128 |
| Require uppercase | Yes |
| Require lowercase | Yes |
| Require digit | Yes |
| Require special character | Yes |
| Common password blocklist | Yes |
| Password history | Last 5 (cannot reuse) |
| Password expiry | 90 days |

---

## 4. Invite Flow

```
Admin creates user (POST /api/v1/admin/users)
    ↓
System generates invite token (with expiry)
    ↓
Email sent with invite link: /invite/{token}
    ↓
User clicks link → validate-token confirms it's valid
    ↓
User sets password and display name (POST /auth/accept-invite)
    ↓
Account activated, user can log in
```

### Invite configuration

| Setting | Default |
|---------|---------|
| Invite expiry | Configurable (hours) |
| Resend cooldown | Configurable (minutes) |
| Max resets per hour | Configurable |

---

## 5. Password Reset

```
POST /auth/forgot-password {email}
    ↓
Always returns 200 (prevents email enumeration)
    ↓
If email exists: sends reset email with token
    ↓
GET /auth/validate-token?token={token}
    ↓
POST /auth/reset-password {token, newPassword}
    ↓
Password updated, all existing sessions invalidated
```

---

## 6. Roles

| Role | Access level |
|------|-------------|
| **ADMIN** | Full access to everything — all matters, all documents, user management, config |
| **PARTNER** | Can create matters, playbooks, pipelines. Sees matters they're a member of. |
| **ASSOCIATE** | Standard access. Cannot see confidential documents. Cannot create matters/playbooks. |

---

## 7. User Administration

**Controller:** `UserAdminController` at `/api/v1/admin/users` (ADMIN-only except `/search`)

| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/` | GET | List all users (with search) |
| `/` | POST | Create user + send invite |
| `/{id}/reset-password` | POST | Trigger password reset |
| `/{id}` | DELETE | Delete user |
| `/{id}/role` | PATCH | Change role |
| `/{id}/enable` | PATCH | Enable/disable account |
| `/{id}/resend-invite` | POST | Resend invite email |
| `/config` | GET/PUT | Auth configuration (lockout, invite, password) |
| `/search` | GET | Search users by name/email (any authenticated user) |

---

## 8. Team Management

**Controller:** `TeamController` at `/api/v1/teams`

Teams are groups of users that can be bulk-added to matters.

| Endpoint | Method | Access | What it does |
|----------|--------|--------|-------------|
| `/` | GET | All | List teams |
| `/{id}` | GET | All | Get team |
| `/` | POST | ADMIN/PARTNER | Create team |
| `/{id}` | PUT | ADMIN/PARTNER | Update team |
| `/{id}` | DELETE | ADMIN/PARTNER | Delete team |
| `/{id}/members` | GET | All | List members |
| `/{id}/members` | POST | ADMIN/PARTNER | Add member |
| `/{id}/members/{userId}` | DELETE | ADMIN/PARTNER | Remove member |

Teams can be added to matters in bulk via `POST /api/v1/matters/{id}/team/add-team`.
