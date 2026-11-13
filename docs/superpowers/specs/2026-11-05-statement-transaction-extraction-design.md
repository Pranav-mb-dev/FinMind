# Statement Transaction Extraction — Design

Date: 2026-11-05

## Goal

When a user uploads a bank statement (PDF/DOCX), extract its transactions with an LLM, let the user review and edit them, and only on confirmation create real `transactions` rows.

**Success criteria**
- Uploading a text-based statement yields a reviewable list of pending transactions.
- Nothing reaches `transactions` (and therefore budgets/summaries) until the user confirms.
- Confirming twice, or re-uploading the same statement, does not create duplicates.
- Extraction failure never breaks existing RAG indexing.

**Out of scope:** OCR for scanned/image PDFs, multi-currency, AI auto-categorization beyond a name-matched suggestion.

## Current state

Spring `POST /documents/upload` -> AI service `POST /api/v1/ingest` -> Celery `process_document_task` -> `ingest_service.process_document` (chunk, embed, upsert to Qdrant; returns chunk count as an int). Spring polls `GET /documents/{id}/status`, which reads `/ingest/status/{task_id}` and sets `READY` / `FAILED`. No structured transactions are produced and `transactions` has no link to a document.

## Design

### 1. Database — migration `V9__create_pending_transactions.sql`

`pending_transactions`: `id UUID PK`, `user_id UUID FK users ON DELETE CASCADE`, `document_id UUID FK documents ON DELETE CASCADE`, `amount DECIMAL(12,2) > 0`, `type` CHECK IN (INCOME, EXPENSE), `description TEXT`, `transaction_date DATE`, `suggested_category VARCHAR`, `dedup_hash VARCHAR(64)`, `created_at TIMESTAMPTZ`. Index on `(user_id, document_id)`. Unique `(document_id, dedup_hash)`.

`dedup_hash` = SHA-256 of normalized `date|amount|type|description`. Duplicates are also checked at confirm time against existing `transactions` for the same user (same date, amount, type, normalized description).

### 2. AI service

- New `services/extraction_service.py`: `extract_transactions(text) -> list[ExtractedTransaction]`. Gemini call with a structured-JSON prompt (date YYYY-MM-DD, positive amount, type, description, suggested_category). Pydantic validates each row; invalid rows (bad date/amount/type) are dropped and logged. Long text is processed in batches of pages and results merged; exact duplicates within one extraction are removed.
- `ingest_service.process_document` calls extraction after chunking/embedding, inside its own try/except. Return value changes from `int` to `{"chunks_indexed": int, "transactions": [...], "extraction_error": str | None}`.
- `routers/ingest.py` status endpoint returns `chunks_indexed`, `transactions` and `extraction_error` from that dict. `reindex_service` must be checked for use of the old int return.
- Extraction is skipped for DOCX/PDF with no text; no OCR.

### 3. Spring backend

- New `PendingTransaction` entity, repository and DTOs in the `transaction` package.
- `DocumentController.getDocumentStatus`: on `ready`, if the document has no pending rows yet, persist `transactions` from the response (guarded against double-save on repeated polls), set `chunksIndexed` from the new field, and store `extraction_error` in the document's `errorMessage` as a non-fatal warning while status stays `READY`.
- Endpoints (all scoped to the current user):
  - `GET /documents/{id}/pending-transactions`
  - `PUT /pending-transactions/{id}` (edit date, amount, type, description, category)
  - `POST /documents/{id}/pending-transactions/confirm` with `{ids: [...]}`: in one DB transaction, create real transactions for the selected rows (skipping duplicates), then delete the selected and any unselected-but-dismissed pending rows for that document. Returns counts of imported and skipped.
- Category matching: map `suggested_category` to the user's categories by case-insensitive name; leave `categoryId` null if none match. The user can change it in review.

### 4. Frontend (`documents/page.tsx`)

A READY document with pending rows shows "Review N transactions". It opens an editable table: checkbox per row (default checked), editable date/amount/type/description/category, and an "Import selected" button. After import the pending list clears and a message reports imported vs skipped duplicates. A warning is shown when `extraction_error` is set.

### 5. Error handling

- Extraction failure: document stays READY (RAG works), warning shown, no pending rows.
- Gemini transient errors follow the existing Celery retry logic.
- Confirm is idempotent: unknown or already-removed IDs are ignored.
- All endpoints reject access to another user's document or rows (404).

### 6. Testing

- Python: parsing/validation (bad rows dropped, batch merge, within-run dedup) with the LLM mocked; ingest result shape; failure isolation (extraction raises, indexing still returns).
- Java: confirm creates transactions and removes pending rows; duplicate skip; ownership checks; double-poll does not double-save.
- Manual end-to-end with a sample statement through the running stack.

## Open points to verify during planning

- `reindex_service` and any other consumer of the old int return value.
- Existing category model (`categories` table) for the name-matching lookup.
