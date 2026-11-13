<div align="center">

# FinMind

**An AI-powered personal finance companion.**
Track spending, set budgets, upload bank statements, and ask questions about your money in plain English.

[![Live Demo](https://img.shields.io/badge/Live%20Demo-34--100--249--2.sslip.io-2ea44f?style=for-the-badge)](https://34-100-249-2.sslip.io)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)
![Next.js](https://img.shields.io/badge/Next.js-14-black)
![FastAPI](https://img.shields.io/badge/FastAPI-Python-009688)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED)

</div>

---

## 🚀 Try it out

**👉 https://34-100-249-2.sslip.io**

1. Open the link and **register** a new account (any email works, no verification).
2. Add a few **transactions** and a **budget** from the dashboard.
3. Upload a bank statement (PDF or DOCX) on the **Documents** page and wait for its status to become *READY*.
4. Open **Chat** and ask things like:
   - *"What did I spend the most on this month?"*
   - *"Am I over budget anywhere?"*
   - *"What does my uploaded statement say about subscriptions?"*

> The demo is a shared public instance. Don't upload real financial documents.

---

## Why FinMind

Most finance apps give you static charts. FinMind lets you ask questions instead. It retrieves your actual transactions, budgets and uploaded documents, then has Gemini answer using that data (Retrieval-Augmented Generation), so answers are grounded in your numbers rather than generic advice.

## Features

| Area | What you get |
|---|---|
| **Auth** | Register/login, BCrypt-hashed passwords, stateless JWT (24h expiry) |
| **Transactions** | Income/expense CRUD, date filtering, categories, summary totals |
| **Budgets** | Weekly / monthly / yearly limits per category, with live progress (spent, remaining, % used) |
| **Insights** | Spending by category vs the previous period, top merchants, detected monthly subscriptions and price hikes |
| **Alerts** | In-app bell when a budget reaches 80% / 100% or a new subscription or price hike appears; optional push to n8n |
| **Documents** | PDF/DOCX upload, async chunking + embedding, live indexing status |
| **AI chat** | Persistent chat sessions with three selectable RAG strategies |
| **Background jobs** | Celery workers (with Flower monitoring), nightly re-index at 02:00 |
| **Deployment** | One-command Docker Compose stack, automatic HTTPS through Caddy |

## Architecture

```
                  ┌──────────────────────────────┐
   Browser ─────▶ │  Caddy  (HTTPS, :80 / :443)  │
                  │   /api/*  → backend:8080     │
                  │   /*      → frontend:3000    │
                  └───────────────┬──────────────┘
                                  │
        Next.js 14 ◀──────────────┤
                                  ▼
          Spring Boot 3.5 (Java 21) ───▶ PostgreSQL 16   (Flyway migrations)
          auth · users · transactions     Redis 7
          budgets · categories
          documents · chat
                                  │ HTTP
                                  ▼
          FastAPI (Python) ───▶ Qdrant   (vector store)
          ingestion · RAG       Gemini   (LLM + embeddings)
                                  ▲
          Celery worker ──────────┘  (Redis broker)   Flower (monitoring)
```

**Design choices**

- **Modular monolith + sidecar.** Spring Boot is the single deployable core, organised by domain package (`user`, `transaction`, `budget`, `category`, `document`, `chat`). FastAPI exists only because LangChain is Python; it is an attached helper, not a microservice split.
- **Flyway owns the schema** (`ddl-auto=validate`). Migrations are append-only.
- **UUID primary keys** to avoid enumerable IDs.
- **Stateless JWT auth** so the backend scales horizontally without shared sessions.
- **Per-user vector isolation.** Every Qdrant query is filtered by `user_id`.
- **Single public entry point.** Only Caddy listens publicly; the AI service, databases and brokers stay on the internal Docker network.

### RAG strategies

| Mode | Pipeline | Best for |
|---|---|---|
| `naive` | Embed query → Qdrant similarity search (top 5) → Gemini | Fast, simple document questions |
| `advanced` | Hybrid vector + keyword search (top 10 each) → LLM re-ranking → Gemini | Higher-precision retrieval |
| `agent` (default) | Router: one structured Gemini call picks the needed sources (`documents`, `transactions`, `summary`, `budgets`) and a date range → sources fetched concurrently in code → one Gemini answer call. At most 2 LLM calls per question | Questions mixing documents and live account data |

Ingestion: PDF/DOCX → `RecursiveCharacterTextSplitter` (500-char chunks, 50 overlap) → Gemini embeddings (768 dimensions) → Qdrant.

## Project metrics

Measured from the repository (source files only, excluding dependencies).

| Metric | Value |
|---|---|
| Backend (Java) | 43 files, ~1,850 lines |
| AI service (Python) | 19 files, ~1,030 lines |
| Frontend (TypeScript/React) | 14 files, ~1,670 lines |
| Database migrations | 8 (6 domain tables) |
| REST endpoints (Spring Boot) | 23 across 7 controllers |
| AI service endpoints | 4 (`/ingest`, `/ingest/status/{id}`, `/chat`, `/health`) |
| RAG strategies | 3 |
| Agent data sources | 4 |
| Containers in the stack | 10 (Postgres, Redis, Qdrant, backend, AI service, Celery worker, Flower, frontend, n8n, Caddy) |
| Automated checks | 16 scripted end-to-end checks (`tests/e2e.sh`), 31 AI-service unit tests |

## Tech stack

| Layer | Technology |
|---|---|
| Frontend | Next.js 14 (App Router), React 18, TypeScript, Tailwind CSS |
| Core backend | Spring Boot 3.5, Java 21, Maven, Spring Security, JPA, JJWT |
| AI service | FastAPI, LangChain, Google Gemini |
| Data | PostgreSQL 16, Qdrant 1.13, Redis 7 |
| Async | Celery, Flower, APScheduler |
| Infra | Docker Compose, Caddy 2 (automatic HTTPS) |
| Automation (optional) | n8n workflow templates |

---

## Getting started

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) with Docker Compose v2
- A [Gemini API key](https://aistudio.google.com/apikey)
- About 4 GB of free RAM for the full stack

### 1. Clone

```bash
git clone <your-repo-url> FinMind
cd FinMind
```

### 2. Configure environment

```bash
cp .env.example .env
cp ai-service/.env.example ai-service/.env
```

Edit **`.env`**:

| Variable | Description |
|---|---|
| `PUBLIC_URL` | Public base URL, e.g. `http://localhost` (local) or `https://your-domain` |
| `POSTGRES_PASSWORD` | Database password (required) |
| `JWT_SECRET` | Random signing secret, at least 32 characters (required). Generate with `openssl rand -base64 48` |
| `CORS_ORIGINS` | Allowed browser origin(s); normally the same as `PUBLIC_URL` |

Edit **`ai-service/.env`**:

| Variable | Description |
|---|---|
| `GEMINI_API_KEY` | Your Gemini API key (required) |
| `GEMINI_MODEL` | Generation model (see `.env.example` for the default; change it if your key has no access) |
| `EMBEDDING_MODEL` / `EMBEDDING_DIMENSIONS` | Embedding model and vector size (default 768) |

Docker Compose refuses to start if a required variable is missing.

### 3. Choose your hostname

`Caddyfile` currently serves `34-100-249-2.sslip.io`. Change that first line:

- **Local:** replace it with `localhost` (or use `:80`), and set `PUBLIC_URL=http://localhost` and `CORS_ORIGINS=http://localhost` in `.env`.
- **Your own server:** use your domain (or `<ip-with-dashes>.sslip.io`), open ports 80 and 443, and Caddy fetches a Let's Encrypt certificate automatically.

### 4. Start

```bash
docker compose up -d --build
```

First build takes a few minutes. Check status:

```bash
docker compose ps
docker compose logs -f backend ai-service
```

### 5. Use it

| What | Where |
|---|---|
| App | `PUBLIC_URL` (e.g. http://localhost) |
| Flower (Celery monitor) | http://127.0.0.1:5555 (localhost only) |
| n8n | http://127.0.0.1:5678 (localhost only) |

On a remote server, reach Flower and n8n through an SSH tunnel, e.g. `ssh -L 5555:127.0.0.1:5555 user@server`.

### Stop / reset

```bash
docker compose down        # stop, keep data
docker compose down -v     # stop and delete all data (database, vectors, uploads)
```

### Updating a deployment

```bash
git pull
docker compose up -d --build
```

Flyway applies new migrations on backend start-up.

---

## Local development (without Docker for the apps)

Start only the infrastructure, then run each app from source:

```bash
docker compose up -d postgres redis qdrant
```

> The compose file doesn't publish database ports. For local app development add a `docker-compose.override.yml` exposing `5432`, `6379` and `6333`.

**Backend** (Spring does not read the root `.env`; export variables first):

```bash
export JWT_SECRET=<32+ char secret>
export SPRING_DATASOURCE_PASSWORD=<same as POSTGRES_PASSWORD>
cd backend && ./mvnw spring-boot:run          # http://localhost:8080
```

**AI service:**

```bash
cd ai-service
pip install -r requirements.txt
uvicorn main:app --port 8000                  # http://localhost:8000
celery -A app.celery_app worker --loglevel=info
```

**Frontend:**

```bash
cd frontend
npm install
npm run dev                                   # http://localhost:3000
```

Set `NEXT_PUBLIC_API_URL` in `frontend/.env` to point at the backend.

## API reference

Backend routes are served under `/api` in the Docker deployment. Everything except `/auth/*` and `/health` requires `Authorization: Bearer <jwt>`.

| Area | Endpoints |
|---|---|
| Auth | `POST /auth/register` · `POST /auth/login` |
| User | `GET /users/me` |
| Transactions | `GET /transactions` · `POST /transactions` · `GET /transactions/summary` · `GET /transactions/by-category?from=&to=` · `DELETE /transactions/{id}` |
| Categories | `GET /categories` · `POST /categories` · `DELETE /categories/{id}` |
| Budgets | `GET /budgets` · `GET /budgets/status` · `POST /budgets` · `PUT /budgets/{id}` · `DELETE /budgets/{id}` |
| Recurring | `GET /recurring` |
| Alerts | `GET /alerts` · `POST /alerts/{id}/read` · `POST /alerts/read-all` |
| Internal (n8n only) | `POST /internal/recurring/scan` with header `X-Service-Token` (blocked at Caddy, reachable only on the Docker network) |
| Documents | `GET /documents` · `POST /documents/upload` · `GET /documents/{id}/status` |
| Chat | `POST /chat` · `GET /chat/sessions` · `GET /chat/sessions/{sessionId}` |
| Health | `GET /health` |

Example:

```bash
BASE=https://34-100-249-2.sslip.io/api

curl -X POST $BASE/auth/register -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"testpass1","fullName":"You"}'

TOKEN=$(curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"testpass1"}')   # login returns the raw JWT string

curl $BASE/users/me -H "Authorization: Bearer $TOKEN"
```

Error semantics: duplicate email → `409`; wrong credentials → `401` (same message for wrong email or password); missing/expired JWT → `401`; unknown resource → `404`.

## Testing

```bash
bash tests/e2e.sh              # end-to-end API checks against a running stack
cd ai-service && pytest tests  # AI-service unit tests
cd backend && ./mvnw test      # backend tests
```

`tests/e2e.sh` expects the backend on `localhost:8080` and the AI service on `localhost:8000` (use the override file above), and uses fixtures in `tests/fixtures/`.

## Project structure

```
FinMind/
├── backend/            Spring Boot app
│   └── src/main/
│       ├── java/com/smartspend/   auth, user, transaction, budget, category, document, chat, recurring, alert, common
│       └── resources/db/migration/  Flyway V1–V10
├── ai-service/         FastAPI + Celery
│   ├── routers/        /api/v1/ingest, /api/v1/chat
│   ├── services/       ingestion, re-indexing, rag/{naive,advanced,agent}_rag.py
│   └── app/            Celery app, tasks, nightly scheduler
├── frontend/           Next.js app (auth + dashboard route groups)
├── n8n/workflows/      Optional workflow templates (see below)
├── tests/              e2e.sh + fixtures
├── Caddyfile           HTTPS reverse proxy
└── docker-compose.yml
```

## Security notes

- Passwords hashed with BCrypt; JWTs signed with a secret from the environment.
- Secrets live in git-ignored `.env` files. Never commit them.
- Only ports 80/443 are public; Flower and n8n bind to `127.0.0.1`.
- Vector queries are scoped per user.
- Use a strong `JWT_SECRET` and `POSTGRES_PASSWORD` in any real deployment.

## Automation (optional, work in progress)

- **Nightly re-index** runs inside the AI service (APScheduler, 02:00) and is active by default.
- **n8n** is included in the stack, with two older workflow templates in `n8n/workflows/` (nightly health check, weekly AI spending summary). They are **templates**: import them through the n8n UI and fill in the placeholders (alert webhook, SMTP credentials, recipient, auth token) before activating. They are not wired up out of the box.

### Alerts and n8n

Alerts work without n8n: the backend writes them and the bell in the sidebar shows them. n8n adds delivery and a nightly scan. Two workflows in `n8n/workflows/` are ready to import (they import as inactive):

| Workflow | What it does |
|---|---|
| `alert-fanout.json` | Receives a webhook for every new alert. Critical alerts go to the user by email, warnings and info to a Slack channel. |
| `nightly-recurring-scan.json` | At 02:30 calls `POST /internal/recurring/scan`, which looks for new subscriptions and price hikes across all users. |

Setup:

1. In `.env` set `N8N_SERVICE_TOKEN` to a long random string (e.g. `openssl rand -hex 32`). Leave it empty and the scan endpoint is disabled.
2. In n8n (http://127.0.0.1:5678) import both files and activate **Alert fan-out**. Copy its **production** webhook URL into `.env` as `N8N_ALERT_WEBHOOK_URL`, normally `http://n8n:5678/webhook/finmind-alerts`.
3. In **Alert fan-out**, the Email and Slack nodes ship **disabled**. Attach your SMTP / Slack credentials, set the From address or channel, and enable the ones you use.
4. Activate **Nightly recurring-charge scan**, then `docker compose up -d` to apply the new env vars.

The webhook call is best effort: a slow or failing n8n never blocks a transaction or loses the in-app alert. The alert payload includes the user's email address so n8n can address the email, so only point `N8N_ALERT_WEBHOOK_URL` at an n8n you control. The workflows read the token with `$env`, which is why compose sets `N8N_BLOCK_ENV_ACCESS_IN_NODE=false`; everyone who can edit workflows in your n8n can read its environment variables.

## Roadmap

- Weekly/monthly AI-written "where to cut" savings report in n8n
- Statement intake by email through n8n
- Bank-statement CSV import
- Spending trend charts on the dashboard
- Automated CI pipeline

## License

Released under the [MIT License](LICENSE). © 2026 Pranav Bhargav M.
