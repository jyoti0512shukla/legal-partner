# Legal Partner — Sales Pitch & Competitive Positioning

## One-Line Pitch

> "The only AI contract platform where your client data never touches a third-party server — not OpenAI, not Google, not us."

---

## Target Market

### Who is NOT our customer

Firms that can use Harvey/CoCounsel. If they have no compliance restrictions on cloud AI, Harvey has better brand recognition and they should use it. We don't compete with Harvey on features — we compete on architecture.

### Who IS our customer

**US law firms (20-200 attorneys) that WANT AI but CAN'T use cloud AI tools because of compliance.**

Specifically, firms where:
- **Client contracts prohibit cloud AI** — banks, defense contractors, pharma companies increasingly add AI clauses to outside counsel guidelines requiring all AI processing on firm-controlled infrastructure
- **Malpractice insurer issued AI guidance** — carriers like CNA, AIG are flagging firms that send client data to OpenAI/Anthropic
- **Internal security review failed Harvey/CoCounsel** — IT or compliance rejected cloud AI tools because data leaves firm control
- **State bar ethics opinions are strict** — California, New York, Florida bar opinions require "reasonable efforts" to protect confidentiality (ABA Model Rule 1.6), and cloud AI is a grey area
- **Practice areas involve trade secrets** — M&A, IP litigation, private equity due diligence — where exposure of deal terms could be catastrophic

### How to find them

| Signal | Where to look |
|--------|--------------|
| Firm has **financial services clients** (banks, PE funds) | AmLaw directories filtered by practice area |
| Firm handles **government/defense contracts** (ITAR, CMMC) | SAM.gov contractor lists, DFARS compliance firms |
| Firm does **healthcare M&A** (HIPAA) | Health law practice group directories |
| Firm's **outside counsel guidelines** mention AI restrictions | Ask during discovery call: "Have any of your clients updated their OCGs regarding AI?" |
| Firm's **malpractice carrier** issued AI bulletin | Ask: "Has your carrier provided guidance on AI tool usage?" |
| **State bar** issued strict AI ethics opinion | Target firms in CA, NY, FL, TX where opinions are strictest |
| Firm **evaluated Harvey but didn't adopt** | Ask: "Have you looked at AI contract tools? What stopped you?" |

### The buying conversation

This is NOT a technology purchase. It's a **risk mitigation purchase.**

- **Budget line:** Risk/compliance, not IT
- **Decision maker:** Managing partner or risk/compliance partner, not IT director
- **Comparison:** Not "us vs Harvey" but "us vs the $5-50M malpractice claim if client data leaks through a cloud AI tool"
- **Urgency:** Every month without compliant AI = associates billing $18-24k in manual contract review that AI does in minutes

> "Your top client just added an AI clause to their outside counsel guidelines. They require all AI processing to stay on firm-controlled infrastructure. Harvey can't do that. We can."

---

## Pricing — Three Tiers

### Lite — Small firms (10-20 attorneys)

| | Details |
|--|--------|
| **Setup** | $5,000 |
| **Monthly** | $500/month |
| **GPU** | Shared GPU across 5-10 firms (isolated databases) |
| **Privacy** | Firm's own VM + database. Model is stateless — reads contract, returns analysis, forgets. No data shared between firms. |
| **Includes** | 5 AI tasks, Google Drive integration, email notifications, 3 onboarding calls |
| **Pitch** | "AI contract review at 1/10th the cost of another associate. Your documents stay on your server." |
| **Decision maker** | Managing partner |
| **Your cost** | ~$80/firm/month |

**3-year cost: $23,000** vs Harvey at $24,000/year ($72k over 3 years)

---

### Standard — Mid-size firms (20-100 attorneys)

| | Details |
|--|--------|
| **Setup** | $30,000 |
| **Monthly** | $1,000/month |
| **GPU** | Dedicated A6000 (48GB) — handles 10-15 concurrent users comfortably |
| **Privacy** | Fully isolated. Dedicated VM + dedicated GPU. Zero external API calls. |
| **Includes** | 5 AI tasks, all integrations (Drive, DocuSign, Slack, NetDocuments), matter access control, 2-week onboarding, clause library seeding |
| **Pitch** | "Your data never touches a third-party server. Not OpenAI, not Google, not us. And the model is trained on YOUR contracts." |
| **Decision maker** | Managing partner or risk/compliance partner |
| **Your cost** | ~$450/month |

**3-year cost: $66,000** vs Harvey at $60,000/year for 50 users ($180k over 3 years)

---

### Enterprise — Large firms (100-200+ attorneys)

| | Details |
|--|--------|
| **Setup** | $50,000 |
| **Monthly** | $2,000/month |
| **GPU** | A100 80GB or 2x A6000 — handles 25-50 concurrent users |
| **Privacy** | Full isolation + redundancy. Failover GPU, daily backups, 99.5% SLA. |
| **Includes** | Everything in Standard + all enterprise features below |
| **Pitch** | "Your competitor uses Harvey — same generic GPT-4 as everyone else. Your model is trained on 500 of YOUR contracts. Your drafts sound like your firm wrote them." |
| **Decision maker** | General Counsel or Managing Partner — triggered by client OCG requiring firm-controlled AI |
| **Your cost** | ~$800/month |

**Enterprise-only features:**

| Feature | What it does | Why firms need it |
|---------|-------------|-------------------|
| **SSO (Microsoft Entra ID / Okta)** | Partners and associates log in with their firm credentials. No separate password. | Every firm 50+ uses Entra ID or Okta. Without SSO, IT won't approve deployment. |
| **Auto-provisioning (SCIM)** | New hire joins the firm → automatically gets Legal Partner account with correct role. Leaves → auto-deprovisioned. | 100+ attorneys means constant onboarding/offboarding. Manual account management doesn't scale. |
| **Custom model fine-tuning** | Retrain the model on the firm's 300-500+ reviewed contracts. Learns their drafting style, risk thresholds, clause preferences. | Drafts sound like their associates wrote them. Competitive moat vs other firms using generic Harvey. |
| **Priority support (4hr SLA)** | Dedicated Slack channel, 4-hour response for critical issues, monthly check-in call with account manager. | When a partner needs the system at 11pm before a deal closes, "24hr email response" isn't good enough. |
| **Custom workflows** | Bespoke multi-step workflows tailored to their practice (e.g., "M&A due diligence with custom checklist + board memo + signing"). | Every firm has unique processes. Predefined workflows cover 80%, custom workflows close the gap. |
| **Quarterly model retraining** | Every 3 months, retrain on their newest contracts so the model stays current with their evolving practice. | Firm style changes. New partners bring new preferences. The model should evolve with the firm. |
| **GPU failover** | If the primary GPU goes down, auto-switch to backup GPU or Gemini fallback. Zero downtime. | 100+ attorneys can't afford "the AI is down today." |
| **Full audit trail + compliance reporting** | Every AI action, document access, email notification — logged, exportable as CSV, filterable by user/date/matter. | Compliance team and malpractice carrier need proof of responsible AI use. |
| **Matter-based ethical walls** | Data from Matter A is invisible to users not on Matter A's team. Enforced at the database level. | Ethical wall violations = malpractice. Not optional for any firm handling opposing parties. |

**3-year cost: $122,000** vs Harvey at $180,000/year for 150 users ($540k over 3 years)

---

### Cost Comparison Across All Tiers

| | Lite (15 users) | Standard (50 users) | Enterprise (150 users) |
|--|----------------|--------------------|-----------------------|
| **Legal Partner — Year 1** | $11,000 | $42,000 | $74,000 |
| **Legal Partner — Year 2+** | $6,000 | $12,000 | $24,000 |
| **Legal Partner — 3yr total** | **$23,000** | **$66,000** | **$122,000** |
| Harvey — 3yr total | $54,000 | $180,000 | $540,000 |
| **Savings vs Harvey** | **57%** | **63%** | **77%** |

### Why each tier wins over Harvey

**Lite wins on price:** $500/month vs $1,500/month for Harvey. Same AI tasks. Small firms don't need dedicated GPU — the shared model is stateless and isolated by database.

**Standard wins on privacy:** These firms have clients (banks, pharma) whose outside counsel guidelines prohibit cloud AI. Harvey fails the compliance review. Legal Partner is the only option that passes.

**Enterprise wins on everything:** 77% cheaper over 3 years, custom model trained on their contracts, data ownership, no vendor lock-in. And when their biggest client asks "where does our data go when you use AI?" — they have an answer that holds up in court.

---

## Competitive Comparison

| | Harvey / CoCounsel | **Legal Partner** |
|--|-------------------|-------------------|
| **Where your data goes** | OpenAI/Anthropic servers. You hope they don't train on it. | Your dedicated GPU. Data never leaves. |
| **Model** | Generic GPT-4. Same model serving Goldman Sachs and your opposing counsel. | Fine-tuned on YOUR contracts, YOUR clause library, YOUR drafting style. |
| **Who else sees your queries?** | Shared infrastructure. Your M&A due diligence query sits in the same pipeline as everyone else's. | Nobody. Isolated instance. |
| **Compliance** | "We have SOC 2" — but data still leaves your control. | ABA Rule 1.6 compliant by architecture, not by policy. |
| **Customization** | None. One model fits all. | Model trained on your precedents. Learns your firm's risk tolerance and drafting style. |
| **Vendor lock-in** | Monthly SaaS. Leave and lose everything. | You own the model. You own the data. Walk away anytime. |
| **Cost (50 attorneys)** | $5,000-7,500/month forever | $30k once + $1k/month |

---

## The Problem We Solve

A 50-attorney firm doing contract review manually:
- Associate billing rate: $300/hour
- Contract review: 3-4 hours per contract
- 20 contracts/month = 60-80 billable hours = **$18,000-24,000/month in associate time**

Legal Partner cuts review time by 50%+ = **$9,000-12,000/month saved**.

But they can't use Harvey or CoCounsel because their compliance team won't approve sending client data to OpenAI.

**They're choosing between Legal Partner and doing nothing.**

---

## What the $30,000 Setup Includes

1. **Dedicated private infrastructure** — GPU + VM provisioned exclusively for the firm
2. **Custom fine-tuned model** — trained on their top 200 contracts and clause library, learns their drafting style
3. **5 AI-powered tasks** — contract drafting, risk assessment, key terms extraction, clause checklist, redline suggestions
4. **Integration setup** — Google Drive/OneDrive, DocuSign e-signature, email/Slack notifications
5. **Matter-based access control** — ethical walls, team-scoped documents and workflows
6. **2-week onboarding** — user training, workflow configuration, clause library seeding
7. **Full audit trail** — every AI action logged for compliance

---

## What the $1,000/Month Retainer Covers

> **"$1,000/month is not hosting. Hosting is $400. The other $600 is insurance — insurance that when a partner uploads a contract at 11pm before a deal closes, the system works. That when Google changes their OAuth API next quarter, your Drive integration doesn't break. That when you need a new workflow configured on Friday afternoon, someone responds within 24 hours."**

### What they see vs what's actually happening

| What the firm sees | What we're doing behind the scenes |
|-------------------|-----------------------------------|
| "It just works" | Monitoring GPU memory, disk usage, DB health, embedding quality, API uptime — continuous |
| "New features appeared" | Shipping product updates (new workflows, integrations, UI improvements) — they get everything for free |
| "The model still works after 6 months" | Ensuring compatibility with new document formats, patching dependencies, security updates |
| "Our DocuSign/Drive still connects" | OAuth APIs change 2-3x per year. We handle the breaking changes before they notice. |
| "Our data is safe" | Daily automated backups, tested recovery, encryption key rotation guidance |
| "Support was fast" | Dedicated support channel, 24-hour response SLA on business days |

### Annual prepaid option

**$10,000/year** (prepaid) instead of $1,000/month — saves the firm $2,000 and gives you upfront cash.

### Included in retainer

| Category | What's included | Limit |
|----------|----------------|-------|
| **Hosting** | Dedicated GPU + VM, 99.5% uptime, daily backups | Unlimited usage |
| **Software updates** | Bug fixes, security patches, new features as we ship them | Automatic — no action needed |
| **RAG indexing** | Upload unlimited contracts, auto-indexed into vector DB | Unlimited documents |
| **Clause library management** | Add/update golden clauses, firm templates | Unlimited |
| **Support** | Email + Slack, response within 24 hours (business days) | Reasonable volume |
| **Infrastructure monitoring** | Uptime monitoring, disk/GPU alerts, log review | Continuous |
| **Minor configuration** | Adjust workflow settings, update integration credentials, add users | Up to 2 hours/month |

### NOT included (paid add-ons)

| Add-on | What it is | Price |
|--------|-----------|-------|
| **Model fine-tuning** | Retrain the model on the firm's 300-500+ contracts. Learns their specific drafting style, risk thresholds, and clause preferences. Recommended after 6 months of use. | $5,000 per retrain |
| **Custom workflows** | Build bespoke multi-step workflows beyond the 6 predefined ones (e.g., "M&A due diligence with custom checklist + board memo") | $2,000-5,000 per workflow |
| **New integrations** | Connect to firm-specific systems not in our standard set (e.g., custom DMS, billing system, internal portal) | $3,000-8,000 per integration |
| **Custom clause templates** | Build firm-specific clause libraries with jurisdiction variants, partner-approved language for each clause type | $1,500-3,000 per library |
| **Training sessions** | Additional onboarding sessions beyond the initial 2-week period (new hires, new practice groups) | $500 per session |
| **Priority support** | 4-hour SLA, dedicated Slack channel, monthly check-in call | +$500/month |

### RAG vs Fine-tuning — When to Recommend Each

The base product uses RAG (retrieval-augmented generation) — the model reads the firm's contracts as context without being retrained. This works on day one and handles 80% of use cases.

| Task | RAG (included) | Fine-tuning (add-on) | Recommendation |
|------|---------------|---------------------|----------------|
| **Risk assessment** | Good — sees similar clauses from their past contracts | Marginal improvement | RAG is enough |
| **Extraction** | Not needed — extraction is mechanical | No improvement | RAG is enough |
| **Checklist** | Slightly helpful | No improvement | RAG is enough |
| **Redline** | Good — suggests language from firm precedents | Better — learns preferred phrasing | Offer fine-tuning after 6 months |
| **Drafting** | Good — pulls golden clauses as context | **High impact** — model generates their firm's style by default | Offer fine-tuning after 6 months |

**When to pitch fine-tuning:**
- After 6 months of usage (enough data collected)
- When partners are heavily editing AI drafts (model hasn't learned their style)
- When the firm does 20+ drafts per month (ROI is clear)
- Position it as "Level 2": "You've seen RAG work. Now the model learns your voice."

### Service Boundaries — What We Don't Do

To keep the retainer sustainable, clearly define what's outside scope:

| Outside scope | Why | Alternative |
|--------------|-----|-------------|
| Unlimited custom development | We're a product company, not a consulting firm | Paid add-ons above |
| Legal advice on AI output | We're technologists, not lawyers | Firm's own review process |
| Data migration from other systems | Complex, firm-specific, unpredictable scope | One-time project quote |
| On-site visits | Remote-first model | Video call support included |
| SLA guarantees on free tier | Retainer covers best-effort uptime | Priority support add-on for SLA |
| Regulatory compliance consulting | GDPR, ABA compliance is firm's responsibility | We provide the technical controls, they handle the legal interpretation |

---

## Three Sentences That Close the Deal

**To the Managing Partner:**
> "Your associates are spending 60+ hours a month on contract review that AI does in minutes. But you can't use Harvey because your compliance team won't approve sending client M&A data to OpenAI. We solve both problems."

**To the IT / Compliance Director:**
> "The model runs on a GPU we provision exclusively for your firm. No API calls to OpenAI, Google, or any third party. Your data stays in your instance — we can prove it architecturally, not just contractually."

**To the Innovation Partner:**
> "We fine-tune the model on your firm's top 200 contracts. It learns your drafting style, your risk thresholds, your clause preferences. Harvey gives every firm the same generic model. We give you YOUR model."

---

## Handling Objections

| Objection | Response |
|-----------|----------|
| "Harvey is backed by Sequoia, you're a startup" | "Harvey sends your data to OpenAI. We don't send it anywhere. Which matters more for your clients?" |
| "A 7B model can't match GPT-4" | "For contract review — specific, structured tasks — our fine-tuned 7B scores 8/8 on all task types. GPT-4 is general-purpose overkill and a compliance risk." |
| "What if you go out of business?" | "You own the model weights and the infrastructure. Worst case, your IT team runs one command and it keeps working. No vendor dependency." |
| "$30k upfront is a lot" | "Your 3-year TCO is $66k. Harvey is $180k+. And you own the asset — it's CapEx, not OpEx." |
| "We already use Kira/Luminance" | "Kira finds clauses. We draft, assess risk, extract terms, generate redlines, and review checklists — all with structured output your team can act on immediately." |
| "Can we try before we buy?" | "Yes. We offer a 2-week pilot on a demo instance with your sample contracts. No commitment, no data retention after the pilot." |

---

## Demo Flow (3 Minutes)

1. **Upload** a real MSA → risk assessment in 15 seconds → "4 HIGH risk categories found"
2. **Checklist** → "Force majeure MISSING, IP ownership WEAK, 2 critical gaps"
3. **Redline** → clean suggested language for each weak clause, ready to insert
4. **Draft** → generate a full agreement with all 12 sections, firm style
5. **Send for Signature** → one click to DocuSign
6. **Show the GPU** → `nvidia-smi` on the terminal → "This GPU is yours. Nobody else's data is on it."

Step 6 is what no competitor can show.

---

## Go-to-Market

### Channels
1. **Legal tech conferences** — ILTACON, Legalweek, ABA TECHSHOW
2. **LinkedIn outreach** — target Innovation Partners and CTO/IT Directors at mid-size firms
3. **Local bar associations** — CLE presentations on "AI Without the Compliance Risk"
4. **Referral partnerships** — legal IT consultants who advise firms on technology

### Sales Process
1. **Outreach** → 2-minute intro video showing the demo
2. **Discovery call** → understand their compliance constraints and contract volume
3. **Pilot** → 2 weeks on demo instance with their sample contracts
4. **Proposal** → $30k setup + $1k/month, include ROI calculation
5. **Close** → 2-week implementation and onboarding

### First 10 Clients Target
- Firms with 30-100 attorneys
- Corporate / M&A / Real Estate practices (highest contract volume)
- Firms that have rejected Harvey or CoCounsel on compliance grounds
- Firms with a designated Innovation Partner or Technology Committee

---

## Technical Architecture (for IT/Compliance audience)

```
Client's Dedicated Instance
├── GPU Server (L4/A6000)
│   └── vLLM serving fine-tuned Saul-7B (FP16, no quantization)
│       └── Model weights: owned by the firm
│
├── Application Server
│   ├── Spring Boot backend (contract analysis, workflows)
│   ├── PostgreSQL + PGVector (documents, embeddings, audit log)
│   ├── Ollama (local embeddings — all-MiniLM)
│   └── AES-256 encryption at rest
│
├── Integrations (firm's own accounts)
│   ├── Google Drive / OneDrive (OAuth — firm's credentials)
│   ├── DocuSign (firm's account)
│   └── Slack / Email (firm's SMTP)
│
└── Access Control
    ├── Matter-based ethical walls
    ├── Role-based permissions (Partner / Associate / Paralegal)
    └── Full audit trail (every AI action logged)

Zero external API calls. Zero data exfiltration paths.
```

---

## Security & Trust — How We Prove It

Law firms don't trust vendor promises. They trust controls they can verify themselves.

### Architectural Proof (verifiable on day one)

| What we show | What it proves |
|-------------|---------------|
| `nvidia-smi` on their VM | The GPU is theirs alone — no shared tenancy |
| `docker ps` showing the full stack | Everything runs locally — no hidden cloud services |
| `tcpdump` / `netstat` during a workflow | Zero outbound connections to OpenAI, Google, or any external AI service |
| `psql` access to their database | Their data, their Postgres, their disk — they can inspect every row |
| Model weights on their filesystem | They own the model. Not a subscription — an asset. |

### Technical Controls (built into the product)

| Control | What it proves | Can we fake it? |
|---------|---------------|-----------------|
| **VPN-only access** | Only their office IPs can reach the VM | No — firewall rule they control |
| **AES-256 encryption at rest** | All documents and embeddings encrypted on disk. Even if someone copies the database file, it's unreadable. | No — industry-standard encryption |
| **Client-managed encryption keys** | They hold the AES-256 key, not us. We can't read their data even with full server access. | No — without the key, data is ciphertext |
| **Full audit trail** | Every AI query, document access, workflow run — timestamped and exportable as JSON | No — immutable database log |
| **No SSH without approval** | We can't access their VM without their firewall rule. They grant access for maintenance windows only. | No — their infrastructure, their rules |
| **Matter-based ethical walls** | Data from Matter A is invisible to users not on Matter A's team | No — enforced at database query level |
| **Role-based access** | Associates can't see what Partners restrict | No — server-side enforcement |

### Contractual Protections (standard enterprise)

| Document | What it covers |
|----------|---------------|
| **Data Processing Agreement (DPA)** | Defines what we can/can't do with their data. Specifies data residency, retention, and deletion obligations. |
| **Right to Audit clause** | "You can inspect our infrastructure, processes, and security controls at any time with reasonable notice." |
| **Data Deletion guarantee** | "All data deleted within 30 days of contract termination. Certification of destruction provided." |
| **Source Code Escrow** | Code deposited with a third-party escrow agent. If we go out of business, they get the source. |
| **No training clause** | "Your data is never used to train, fine-tune, or improve any model — yours or anyone else's." |
| **Subprocessor transparency** | Full list of any infrastructure providers (GCP, AWS) with no AI subprocessors. |

### What Competitors Can't Offer

| Trust question | Harvey / CoCounsel | Legal Partner |
|---------------|-------------------|---------------|
| "Where does my data go?" | OpenAI/Anthropic servers. They have a DPA but data still leaves your control. | Your VM. Run `tcpdump` and verify — zero external calls. |
| "Can you read our documents?" | Yes. Their ops team has access to the shared infrastructure. | Only if you grant us SSH access. Encryption key is yours. |
| "What if there's a breach?" | Your data is in a multi-tenant system with thousands of other firms. | Single-tenant. Your data is on one VM. Blast radius = zero. |
| "Can you prove no one else sees our queries?" | No. Shared API, shared logs, shared infrastructure. | Yes. One GPU, one database, one process. `ps aux` proves it. |
| "What happens to our data when we leave?" | It's in their cloud. You hope they delete it. | You own the VM. Terminate it yourself. Or we hand you the Docker images and walk away. |

### The Pitch Line

> "We don't ask you to trust us. We give you the infrastructure and the controls to verify it yourself. Your IT team can SSH into the VM, monitor network traffic, and audit every action. The encryption key is yours — we can't read your data even if we wanted to. And if you ever want to leave, we hand you the model weights and the Docker images. You walk away with everything."

### For the Pilot

The strongest trust builder for the first client: **give them SSH access**. Let their IT person poke around for a week. That's more convincing than any SOC 2 badge or marketing page.

### Certification Roadmap

| When | Certification | Cost | What it gives us |
|------|--------------|------|-----------------|
| **Now** | Pentest report | ~$2,000 | Professional vulnerability assessment. Shareable report + verifiable certificate badge for website. 2-3 weeks. |
| **After 3-5 clients** | SOC 2 Type I | $10-20k | Point-in-time security controls audit. Use Vanta/Drata to automate. 2-3 months. |
| **After 12 months** | SOC 2 Type II | $20-50k | Continuous security controls audit over 6-12 months. Enterprise procurement requirement. |

<!-- INTERNAL NOTE — do not share with clients
Pentest vendor shortlist (India, budget ~$2k):
- Astra Security (Bangalore) — $1,999/app, gives verifiable certificate badge, 2-3 weeks. Best option. astra.security
- Breachlock (Delhi/US) — $1,995, has US presence which looks good. Hybrid automated + manual.
- Securelayer7 (Pune) — $1,000-2,000, focused on app pentesting.
- Indusface (Vadodara) — $1,500-2,500, CERT-In empanelled.
- WeSecureApp (Hyderabad) — $1,500-2,500, CERT-In empanelled.

What to get tested: deployed app, API auth/authz, OAuth flows, encryption at rest, matter isolation.
Astra gives a badge like "Penetration tested — No critical vulnerabilities found" — put on website + sales deck.
-->

---

## Key Metrics to Track

| Metric | Target |
|--------|--------|
| Contracts reviewed per month (per firm) | 20-50 |
| Average review time reduction | 50%+ |
| JSON format compliance (structured tasks) | 95%+ |
| System uptime | 99.5% |
| Time to onboard new client | 2 weeks |
| Client retention (annual) | 90%+ |
