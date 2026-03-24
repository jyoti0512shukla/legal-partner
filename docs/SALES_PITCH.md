# Legal Partner — Sales Pitch & Competitive Positioning

## One-Line Pitch

> "The only AI contract platform where your client data never touches a third-party server — not OpenAI, not Google, not us."

---

## Target Market

**Mid-size US law firms (20-200 attorneys)** that:
- Handle sensitive M&A, litigation, corporate transactions
- Want AI productivity but can't pass compliance review for cloud AI tools
- Currently do contract review manually (3-4 hours per contract)
- Have explicit policies against uploading client documents to ChatGPT/Copilot

---

## Pricing

| Component | Cost | What they get |
|-----------|------|---------------|
| **Setup fee** | $30,000 one-time | Dedicated GPU + VM, model fine-tuned on their clause library, integrations configured, 2-week onboarding |
| **Monthly retainer** | $1,000/month | Infrastructure hosting, software updates, quarterly model retraining, support |

### 3-Year Total Cost Comparison

| Vendor | Year 1 | Year 2 | Year 3 | **3-Year Total** |
|--------|--------|--------|--------|-----------------|
| **Legal Partner** | $42,000 | $12,000 | $12,000 | **$66,000** |
| Harvey AI (50 users) | $60,000 | $60,000 | $60,000 | $180,000 |
| CoCounsel | $48,000 | $48,000 | $48,000 | $144,000 |
| Kira/Luminance | $75,000 | $75,000 | $75,000 | $225,000 |

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
