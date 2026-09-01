# FinMind— AI Context & Project Reference

> **For AI assistants:** This document is the single source of truth for the SmartSpend project. Read this fully before generating any code, answering any architecture question, or making any technical decision. Every decision documented here has already been made and agreed upon — do not suggest alternatives unless explicitly asked.

---

## 0. Quick Identity

| Field | Value |
|---|---|
| **Project name** | SmartSpend (will rename to FinMind on ship) |
| **Type** | AI-powered personal finance companion |
| **Developer** | Pranav — 2nd year CSE student, Google Student Ambassador |
| **Dev environment** | GitHub Codespaces (Windows PC has no admin rights) |
| **Current phase** | Phase 2 — Auth & User API (in progress) |
| **Package root** | `com.smartspend` |

---

## 1. What SmartSpend Is

FinMind is an AI-powered personal finance app that lets users understand their financial data through natural conversation. Instead of static dashboards, users ask questions like:

- *"Where did I overspend in April?"*
- *"Am I on track for my savings goal?"*
- *"What's my biggest recurring expense?"*

The system retrieves the user's actual transaction history and uploaded documents from a vector database, injects that context into a prompt, and generates a grounded answer using Gemini. This is RAG — Retrieval-Augmented Generation.

---

## 2. Architecture Pattern

**Modular Monolith + Sidecar**

```
Spring Boot (Modular Monolith)
├── user/         ← domain: auth, profile
├── transaction/  ← domain: financial transactions
├── budget/       ← domain: spending limits
└── auth/         ← domain: JWT, security config

FastAPI (Python Sidecar) ← language-forced, NOT microservices
└── RAG pipeline, LangChain, MCP tool-calling

Next.js (Frontend)
└── UI, chat interface, dashboard
```

**Why not microservices?** FastAPI exists only because LangChain is Python and cannot run inside the Java runtime. This is a language constraint, not an architectural philosophy. Spring Boot + FastAPI together is a monolith + sidecar pattern — one main unit, one attached helper.

**Why modular monolith?** Each domain (user, transaction, budget) is a self-contained package with its own entity, repository, service, and controller. One deployable JAR, clean internal package boundaries. The `transaction` package never directly calls `budget`'s repository — it goes through the service layer.

---

## 3. Full Technology Stack

| Layer | Technology | Why |
|---|---|---|
| Core backend | Spring Boot 3.5.0 (Java 21, Maven) | Main monolith — auth, business logic, API gateway |
| AI service | FastAPI (Python 3.12) | LangChain requires Python |
| LLM | Gemini API | Generation + embeddings |
| RAG orchestration | LangChain | Chains, retrievers, agents |
| Agent tool protocol | MCP (Model Context Protocol) | LLM calls Spring Boot APIs as tools |
| Relational DB | PostgreSQL 16 | Users, transactions, budgets, chat history |
| Schema migrations | Flyway | Version-controlled schema changes |
| Vector DB | Qdrant | Document embeddings, semantic search |
| Cache + broker | Redis | Caching + Celery message queue |
| Async tasks | Celery + APScheduler | Background document indexing, scheduled jobs |
| Workflow automation | n8n | External integrations, notifications |
| Frontend | Next.js (App Router) | Dashboard, chat UI, document upload |
| Auth | JWT + BCrypt | Stateless tokens, hashed passwords |
| Containerization | Docker Compose | Local dev environment |

---

## 4. Key Architectural Decisions (DO NOT CHANGE)

| Decision | Choice | Reason |
|---|---|---|
| ID type | UUID | Unpredictable, globally unique, prevents enumeration attacks |
| ID generation | `GenerationType.UUID` | Explicit, predictable — never use AUTO |
| DDL strategy | `ddl-auto=validate` | Flyway owns schema, Hibernate only validates |
| Password storage | BCrypt hash | Never store plaintext passwords |
| Auth mechanism | Stateless JWT | Scales horizontally without shared session storage |
| Schema changes | Flyway migration files | Never alter production DB manually |
| Timestamp management | `@PrePersist` / `@PreUpdate` | Entity manages its own timestamps automatically |
| Equality check | `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` on UUID only | Avoid comparing mutable fields |
| Package structure | Domain-based (`com.smartspend.user`, `com.smartspend.transaction`) | Enforces modular monolith boundaries |

---

## 5. Database Schema

### Current migrations applied:
```
V1__create_users_table.sql ✅
```

### Users table:
```sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
```

### Planned tables (future migrations):
- `transactions` — financial transactions per user
- `categories` — transaction classification labels
- `budgets` — spending limits per category/period
- `documents` — uploaded file metadata + indexing status
- `chat_history` — persisted conversation records

> **Rule:** Never write a new migration that edits an existing one. Always create a new versioned file (V2, V3...). Flyway checksums every file — editing applied migrations causes startup failure.

---

## 6. Infrastructure (Docker Compose)

All services run via `docker/docker-compose.yml`.

```
PostgreSQL  → localhost:5432   (finmind DB, finmind_user, finmind_pass)
Redis       → localhost:6379
Qdrant      → localhost:6333
```

**Always run this first when opening Codespaces:**
```bash
cd /workspaces/FinMind/docker && docker compose up -d
```

Spring Boot will fail to start if Docker containers are not running (Flyway connects to PostgreSQL at startup before the app is ready).

---

## 7. Project Folder Structure

```
/workspaces/FinMind/
├── backend/
│   ├── src/main/java/com/smartspend/
│   │   ├── user/       → User entity, UserRepository, UserService, UserController
│   │   ├── auth/       → JwtService, AuthService, AuthController, SecurityConfig
│   │   ├── transaction/  → (Phase 3+)
│   │   └── budget/       → (Phase 3+)
│   ├── src/main/resources/
│   │   ├── application.properties
│   │   └── db/migration/   → Flyway SQL files
│   └── pom.xml
├── ai-service/         → FastAPI Python AI service (HTTP, language-forced separation)
│   ├── main.py
│   ├── routers/
│   ├── services/
│   ├── core/
│   └── requirements.txt
├── frontend/           → Next.js (Phase 6)
└── docker/
    └── docker-compose.yml
```

---

## 8. Development Phases

### ✅ Phase 1 — Project Setup & Dev Environment (COMPLETE)
**Deliverable:** All services running, first migration applied.
- Docker Compose: PostgreSQL, Redis, Qdrant all healthy
- Spring Boot bootstrapped and connected to PostgreSQL
- Flyway ran V1 migration — users table created
- FastAPI bootstrapped with `/health` endpoint on port 8000
- Redis and Qdrant verified reachable

**Skills unlocked:** Docker Compose, Spring Boot setup, FastAPI basics, Flyway migrations, project structure thinking.

---

### 🔄 Phase 2 — Core Backend: Auth & User API (IN PROGRESS)
**Deliverable:** Register, login, JWT auth, protected profile endpoint fully working.

**Spec:**
- `POST /auth/register` — email + password + fullName → BCrypt hash → save → 201
- `POST /auth/login` — verify credentials → return signed JWT (24h expiry)
- `GET /users/me` — JWT protected → return user profile

**Edge cases:**
- Duplicate email → 409 Conflict
- Wrong credentials → 401 (same message for wrong email OR wrong password — never leak which)
- Missing/expired/malformed JWT → 401
- Valid token but deleted user → 401

**Implementation layers (in order):**
1. `User.java` entity ← currently here
2. `UserRepository.java`
3. DTOs — `RegisterRequest`, `LoginRequest`, `UserResponse`
4. `JwtService.java`
5. `AuthService.java`
6. `SecurityConfig.java`
7. `AuthController.java`
8. `UserController.java`

**Current status:** User.java entity written. Package: `com.smartspend.user`. Fixed to use `GenerationType.UUID`. Column name is `password` (not `password_hash`) to match V1 migration.

**Skills to unlock:** Spring Boot REST APIs, JWT authentication, PostgreSQL schema design, Flyway migrations, API design.

---

### ⬜ Phase 3 — RAG Pipeline: Document Ingestion
**Deliverable:** User uploads a bank statement → FastAPI chunks, embeds, stores in Qdrant → chunks are semantically searchable.

**Flow:**
1. User uploads PDF/CSV via frontend
2. Spring Boot forwards to FastAPI `/ingest`
3. LangChain DocumentLoader parses the file
4. RecursiveCharacterTextSplitter chunks it
5. Gemini embedding model converts chunks to vectors
6. Qdrant stores vectors scoped to the user

> **Deep conceptual coverage required:** What embeddings are, how chunking strategy affects retrieval quality, why vector similarity finds meaning not keywords. Do not skip the concept phase for this.

**Skills to unlock:** LangChain, embeddings, vector databases, Qdrant, Gemini embedding API, FastAPI file uploads.

---

### ⬜ Phase 4 — Async Jobs: Background Processing
**Deliverable:** Document upload returns 202 instantly → Celery indexes in background → status updates to "ready".

**Components:**
- Celery tasks for document indexing
- Redis as message broker
- `/ingest/status/{job_id}` polling endpoint
- APScheduler for nightly re-indexing jobs
- Flower dashboard for monitoring

**Skills to unlock:** Celery, Redis broker, APScheduler, async system design.

---

### ⬜ Phase 5 — RAG Chat + AI Agent
**Deliverable:** "Ask your finances" chat feature with three progressive stages.

**Stage 1 — Naive RAG:**
User query → embed → Qdrant retrieval → Gemini generation → answer.

**Stage 2 — Advanced RAG:**
Hybrid search (keyword + semantic), re-ranking, query rewriting for better retrieval accuracy.

**Stage 3 — Agentic RAG:**
LLM decides whether to search Qdrant or call Spring Boot APIs as MCP tools. Multi-step reasoning. LangChain AgentExecutor.

> **Deep conceptual coverage required at each stage.** Explain Naive RAG fully before Advanced, Advanced fully before Agentic. No skipping.

**Skills to unlock:** LangChain chains + agents, prompt engineering, MCP protocol, LLM tool calling, streaming responses.

---

### ⬜ Phase 6 — Frontend + Workflow Automation
**Deliverable:** Complete Next.js web app + n8n automated workflows.

**Frontend features:**
- Login/register pages (JWT stored in cookie)
- Spending dashboard (transactions from PostgreSQL)
- Document upload UI with real-time status polling
- Streaming chat interface (token-by-token via Vercel AI SDK)

**Automation:**
- n8n: nightly transaction sync workflow
- n8n: weekly AI spending summary → email
- APScheduler: in-app scheduled jobs

**Skills to unlock:** Next.js App Router, streaming chat UI, n8n automation, full-stack integration, system debugging.

---

## 9. RAG System Overview

SmartSpend's AI is built in three escalating stages:

```
Stage 1 — Naive RAG
User query → embed query → Qdrant similarity search
→ retrieve top-K chunks → inject into prompt → Gemini answers

Stage 2 — Advanced RAG
Same as above + hybrid search (BM25 + vector)
+ re-ranking retrieved results
+ query rewriting for better retrieval

Stage 3 — Agentic RAG
LLM reasons about what to do:
"Should I search Qdrant or call get_transactions() API?"
→ calls tools via MCP → combines results → answers
```

---

## 10. Learning Methodology (Enforce Strictly)

This project is built as a learning exercise following senior SWE discipline. The AI assistant must enforce these rules every session:

1. **SKETCH FIRST** — Ask Pranav to explain what should happen before generating any code. Wait for the answer.
2. **SMALL PIECES ONLY** — One layer at a time: entity → repository → DTO → service → controller. Never generate a full feature in one shot.
3. **GUESS BEFORE EXPLAIN** — Show a new annotation/pattern, ask Pranav to guess what it does before explaining. Correct precisely: what's right, what's fuzzy, what's wrong.
4. **END-OF-FEATURE TRACE** — After each feature, Pranav must trace the full request from HTTP input to response from memory. Call it out if skipped. Do not continue until done.
5. **BREAK IT** — After each feature works, prompt Pranav to test at least one deliberate failure case and explain why that failure happens.
6. **REBUILD FROM MEMORY** — Pranav rewrites one small part himself with no AI after understanding it.
7. **NEVER HAND ANSWERS EARLY** — If Pranav asks to just generate something, remind him of the methodology. Exception: pure boilerplate with zero learning value.
8. **INTERROGATE THE CODE** — When Pranav pastes AI-generated code, cross-examine him line by line like a senior engineer in a code review. He must defend every annotation and decision.
9. **ANALOGIES FIRST** — Pranav learns best with analogies before technical depth. Always lead with an analogy when introducing a new concept.
10. **PHASE WRAP-UP** — After completing each phase: concept revision summary + mock interview (one question at a time, correct answers precisely).

---

## 11. Concepts Already Covered (Do Not Re-explain)

- Docker vs Docker Compose
- Monolith vs microservices vs modular monolith + sidecar
- PostgreSQL Driver vs Spring Data JPA
- Flyway — how migrations work locally and in production
- `ddl-auto=validate` vs `update`
- Lombok — `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Getter`, `@Setter`
- `@Entity`, `@Table`, `@Id`, `@GeneratedValue`, `@Column`
- `@PrePersist`, `@PreUpdate` — JPA lifecycle hooks
- `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` — UUID-only equality
- JWT authentication — wristband analogy, header/payload/signature, stateless vs sessions
- UUID vs integer ID — security reasoning
- `GenerationType.UUID` vs `AUTO`
- BCrypt password hashing
- DTO — what it is and why it's different from an entity

---

## 12. Ports Reference

| Service | Port | Status |
|---|---|---|
| Spring Boot | 8080 | ✅ Running |
| FastAPI | 8000 | ✅ Running |
| PostgreSQL | 5432 | ✅ Running |
| Redis | 6379 | ✅ Running |
| Qdrant | 6333 | ✅ Running |
| Next.js | 3000 | ⬜ Phase 6 |

---

## 13. Courses Being Followed

| When | Course | Platform | Cost |
|---|---|---|---|
| Phase 3 starts | LangChain: Chat With Your Data | DeepLearning.AI | Free |
| Phase 3 ends | Retrieval Augmented Generation | DeepLearning.AI | Free |
| Phase 5 starts | Functions, Tools & Agents with LangChain | DeepLearning.AI | Free |
| Phase 5 ends | LangChain Academy Essentials | LangChain Academy | Free |
| After ship | Ultimate RAG Bootcamp | Udemy | ~₹499 |

---

*Last updated: Phase 2 in progress — User.java entity complete.*
