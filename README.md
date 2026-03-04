# Legal Partner — Private Contract Intelligence

A private, on-premises contract intelligence assistant built for mid-tier Indian law firms (10-20 Cr turnover). All AI processing runs locally via Ollama — zero data leaves the network.

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  Local Machine                                       │
│  ┌────────────────────┐                              │
│  │  React + Vite       │ localhost:5173               │
│  │  (frontend/)        │────────────┐                │
│  └────────────────────┘            │ REST API        │
│                                     ▼                │
│  ┌─── Docker Compose ────────────────────────────┐   │
│  │                                                │   │
│  │  Spring Boot API      :8080                    │   │
│  │  PostgreSQL + PGVector :5432                    │   │
│  │  Ollama LLM Server     :11434                   │   │
│  │  (tinyllama + all-minilm models)                │   │
│  │                                                │   │
│  └────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

## Prerequisites

- **Docker** and **Docker Compose** v2+
- **Node.js** 18+ and **npm** (for frontend dev server)
- **Java 21** (only if running backend outside Docker)
- ~6GB free disk space (for Docker images + LLM models)

## Quick Start

### 1. Start Backend Services (Docker)

```bash
cd legal-partner
docker compose up --build
```

This starts:
- **PostgreSQL 16 + PGVector** on port 5432
- **Ollama** on port 11434 (auto-pulls tinyllama + all-minilm models)
- **Spring Boot API** on port 8080

First run takes ~3-5 minutes (model download). Subsequent runs: ~30 seconds.

### 2. Start Frontend (Local)

```bash
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173** in your browser.

### Optional: Use External Chat API (Colab, vLLM, etc.)

To use an OpenAI-compatible chat API (e.g. Colab with Mistral, ngrok):

```bash
export LEGALPARTNER_CHAT_API_URL=https://your-ngrok-url.ngrok-free.dev
docker compose up -d
```

Chat requests use the external API; embeddings stay on local Ollama.

### 3. Login

| User       | Password      | Role       | Access                         |
|------------|---------------|------------|-------------------------------|
| admin      | admin123      | ADMIN      | Full access                   |
| partner    | partner123    | PARTNER    | Full access (no admin panel)  |
| associate  | associate123  | ASSOCIATE  | No audit logs, no confidential docs |

## Features

### Document Ingestion
- Upload PDF, HTML, DOCX contracts
- Apache Tika text extraction
- **Legal-aware chunking** — splits at clause/section boundaries, not mid-sentence
- AES-256 encryption at rest (Jasypt)
- PGVector embedding storage for semantic search

### AI Intelligence (RAG Pipeline)
- **Query expansion** with legal synonyms
- **Metadata pre-filtering** (jurisdiction, year, clause type)
- **Re-ranking** (vector similarity + keyword overlap + recency boost)
- **Legal prompt templates** tailored for Indian law
- **Citation extraction** with verified/unverified indicators
- **Three-layer validation**: structure, faithfulness, confidence calibration

### Contract Comparison
- Side-by-side dimensional analysis (Liability, Termination, Governing Law, etc.)
- Favorable-to indicators per dimension

### Risk Assessment
- Categorized risk report (HIGH/MEDIUM/LOW per category)
- Visual risk gauge with clause references

### Audit Trail
- Complete compliance-grade logging of every action
- Filter by user, action type, date range
- CSV export for compliance reporting

### Security
- In-memory RBAC (3 roles: ADMIN, PARTNER, ASSOCIATE)
- HTTP Basic Auth
- AES-256 encryption at rest for document chunks
- Data-level filtering (Associates can't see confidential documents)

## Project Structure

```
legal-partner/
├── backend/                 # Spring Boot 3.2 + Gradle
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── src/main/java/com/legalpartner/
│       ├── config/          # Security, CORS, Encryption
│       ├── controller/      # REST endpoints
│       ├── model/           # JPA entities + DTOs
│       ├── repository/      # Spring Data JPA
│       ├── service/         # Business logic
│       ├── rag/             # Chunker, Expander, ReRanker, Citations
│       └── audit/           # Event-driven audit system
├── frontend/                # React 18 + Vite + Tailwind
│   └── src/
│       ├── pages/           # Dashboard, Intelligence, Documents, Compare, Risk, Audit
│       ├── components/      # Layout, shared UI components
│       ├── hooks/           # Auth context
│       └── api/             # Axios client
├── docker-compose.yml       # Postgres + Ollama + Backend
└── README.md
```

## API Endpoints

| Method | Path                              | Auth            | Description                |
|--------|-----------------------------------|-----------------|---------------------------|
| POST   | /api/v1/documents/upload          | All roles       | Upload + index document   |
| GET    | /api/v1/documents                 | All roles       | List documents            |
| GET    | /api/v1/documents/{id}            | All roles       | Get document detail       |
| DELETE | /api/v1/documents/{id}            | ADMIN, PARTNER  | Delete document           |
| GET    | /api/v1/documents/stats           | All roles       | Corpus statistics         |
| POST   | /api/v1/ai/query                  | All roles       | RAG query                 |
| POST   | /api/v1/ai/compare                | All roles       | Compare two documents     |
| POST   | /api/v1/ai/risk-assessment/{id}   | All roles       | Risk assessment           |
| GET    | /api/v1/audit/logs                | ADMIN, PARTNER  | Filterable audit trail    |
| GET    | /api/v1/audit/stats               | ADMIN, PARTNER  | Audit statistics          |
| GET    | /api/v1/audit/export              | ADMIN, PARTNER  | CSV export                |

## Environment Variables

| Variable                   | Default                              | Description              |
|---------------------------|--------------------------------------|--------------------------|
| DB_PASSWORD               | localdev123                          | PostgreSQL password      |
| ENCRYPTION_KEY            | demo-encryption-key-change-in-prod   | Jasypt encryption key    |
| LEGALPARTNER_OLLAMA_BASE_URL | http://ollama:11434               | Ollama server URL        |

## Tech Stack

**Backend**: Spring Boot 3.2.5, Java 21, LangChain4j 0.35.0, Apache Tika 2.9.1, Jasypt 3.0.5, Flyway, PostgreSQL 16 + PGVector

**Frontend**: React 18, Vite 5, Tailwind CSS 3, React Router 6, Axios, Recharts, Framer Motion, Lucide React, react-dropzone

**Infrastructure**: Docker Compose, Ollama (tinyllama + all-minilm), PGVector
