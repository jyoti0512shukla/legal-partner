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

- GPU + VM hosting (dedicated, not shared)
- Software updates and new features
- Quarterly model retraining as firm adds more contracts
- Email and Slack support
- Infrastructure monitoring and uptime

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

## Key Metrics to Track

| Metric | Target |
|--------|--------|
| Contracts reviewed per month (per firm) | 20-50 |
| Average review time reduction | 50%+ |
| JSON format compliance (structured tasks) | 95%+ |
| System uptime | 99.5% |
| Time to onboard new client | 2 weeks |
| Client retention (annual) | 90%+ |
