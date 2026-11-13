# Statement Transaction Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract transactions from uploaded statements with Gemini, store them as pending, and let the user review, edit and confirm them into `transactions`.

**Architecture:** The Celery ingest task additionally calls an extraction service and returns the rows in its result dict. Spring persists them as `pending_transactions` when the status poll sees `ready`, and exposes list, edit and confirm endpoints. The documents page gets a review table.

**Tech Stack:** FastAPI, Celery, langchain-google-genai, Pydantic, pytest (ai-service); Spring Boot, JPA, Flyway, JUnit 5 + Mockito (backend); Next.js (frontend).

**Spec:** `docs/superpowers/specs/2026-11-05-statement-transaction-extraction-design.md`

## Global Constraints

- `pending_transactions` is separate from `transactions`; nothing reaches `transactions` before confirm.
- `type` is `INCOME` or `EXPENSE`; `amount` is positive, `DECIMAL(12,2)`; dates are `YYYY-MM-DD`.
- `dedup_hash` = SHA-256 hex of `date|amount|type|description`, with description lower-cased and whitespace-collapsed and amount scaled to 2 dp. Unique on `(document_id, dedup_hash)`.
- Extraction failure must never fail indexing. Document stays `READY` and the error is stored in `errorMessage`.
- All endpoints are scoped to the current user; another user's rows or documents return 404.
- No OCR, no multi-currency.
- The Celery task result changes from `int` to `{"chunks_indexed": int, "transactions": list[dict], "extraction_error": str | None}`. Nothing else consumes the old int (`reindex_service` does not use it).

## Review Focus

- Debit/credit columns, negative numbers and parenthesised amounts: the model must output a positive `amount` and the right `type`. The prompt states this rule, and the parser drops `amount <= 0` rows.
- Ambiguous dates such as `03/04/2025`: the prompt tells the model to output ISO dates and to skip rows it cannot date. Rows with unparseable dates are dropped, not guessed.
- The model wraps its JSON in markdown fences or adds prose: the parser must still extract the array, and return `[]` (not raise) on garbage.
- Repeated status polls, or two browser tabs, must not double-save pending rows. Confirming twice must not double-import.
- Confirm with IDs belonging to another user or another document must import nothing.

---

### Task 1: Extraction service (AI service)

**Files:**
- Create: `ai-service/services/extraction_service.py`
- Test: `ai-service/tests/test_extraction_service.py`

**Interfaces:**
- Produces:
  - `class ExtractedTransaction(BaseModel)` with `date: date`, `amount: Decimal`, `type: Literal["INCOME","EXPENSE"]`, `description: str`, `suggested_category: str | None`.
  - `def parse_transactions(raw: str) -> list[ExtractedTransaction]`. Pure. Strips code fences, finds the JSON array, validates each row, drops invalid rows (bad date, `amount <= 0`, bad type), removes exact duplicates within the result, and returns `[]` on unparseable input.
  - `async def extract_transactions(text: str) -> list[ExtractedTransaction]`. Splits `text` into batches of at most 12000 characters on line boundaries, calls Gemini once per batch with `EXTRACTION_PROMPT`, runs `parse_transactions` on each reply, and merges and de-duplicates the results.

- [ ] **Step 1: Write failing tests** in `test_extraction_service.py` for `parse_transactions`:
  - `test_parses_plain_json_array`: input `[{"date":"2025-03-04","amount":120.5,"type":"EXPENSE","description":"Swiggy","suggested_category":"Food"}]` gives one row with `amount == Decimal("120.50")`.
  - `test_strips_markdown_fences_and_prose`: the same array wrapped in ```` ```json ```` fences with a leading sentence still parses.
  - `test_drops_invalid_rows`: rows with date `"03/04/2025"`, `amount: 0`, `amount: -5` and `type: "TRANSFER"` are all dropped, and one valid row is kept.
  - `test_garbage_returns_empty`: `"sorry I can't"` and `""` both return `[]`.
  - `test_dedups_exact_duplicates_within_run`: two identical rows give one.
  - and for `extract_transactions` with `ChatGoogleGenerativeAI.ainvoke` patched: `test_batches_long_text` (text of 30000 chars produces 3 model calls and merged results) and `test_empty_text_makes_no_call`.
- [ ] **Step 2:** Run `cd ai-service && python -m pytest tests/test_extraction_service.py -v`. Expected: FAIL (module missing).
- [ ] **Step 3:** Implement the module. Use the model from `settings.gemini_model` with temperature 0, and reuse `_extract_text` from `services.rag.naive_rag` to read the reply. `EXTRACTION_PROMPT` must state: output only a JSON array; keys as above; `amount` always positive, with `type` EXPENSE for debits/withdrawals and INCOME for credits/deposits; ISO dates; skip rows whose date is unclear; skip balance and opening/closing lines.
- [ ] **Step 4:** Re-run the tests. Expected: all PASS.
- [ ] **Step 5:** Commit: `git add ai-service/services/extraction_service.py ai-service/tests/test_extraction_service.py && git commit -m "feat(ai): add statement transaction extraction service"`.

### Task 2: Wire extraction into ingest and the status endpoint

**Files:**
- Modify: `ai-service/services/ingest_service.py` (`process_document`), `ai-service/routers/ingest.py` (SUCCESS branch of `get_ingest_status`)
- Test: `ai-service/tests/test_ingest_extraction.py`

**Interfaces:**
- Consumes: `extract_transactions` from Task 1.
- Produces: `process_document(...) -> dict` with keys `chunks_indexed`, `transactions` (list of dicts with ISO date string, amount as float, type, description, suggested_category) and `extraction_error`. The status endpoint returns those three keys on `ready`. It must still accept a legacy int result by returning it as `chunks_indexed` with `transactions: []`.

- [ ] **Step 1: Write failing tests.** With Qdrant, embeddings and the loader mocked: `test_returns_dict_with_transactions` (extraction returns 2 rows, so the result has `chunks_indexed` and 2 serialisable dicts) and `test_extraction_failure_does_not_fail_indexing` (extraction raises, so `chunks_indexed` is still returned, `transactions == []` and `extraction_error` is non-empty). For the router, `test_status_ready_returns_transactions` (patch `AsyncResult`) and `test_status_ready_accepts_legacy_int`.
- [ ] **Step 2:** Run `python -m pytest tests/test_ingest_extraction.py -v`. Expected: FAIL.
- [ ] **Step 3:** In `process_document`, after the Qdrant upsert, call `extract_transactions(combined_text)` inside `try/except Exception`, and serialise the rows. The early `return 0` for no chunks becomes the dict with empty transactions. Keep the `finally` file cleanup. Update the router's SUCCESS branch.
- [ ] **Step 4:** Run `python -m pytest tests -v`. Expected: all PASS, including existing `test_retry.py` and `test_agent_rag.py`.
- [ ] **Step 5:** Commit: `feat(ai): extract transactions during ingest`.

### Task 3: Pending transactions schema, entity and repositories

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__create_pending_transactions.sql`
- Create: `backend/src/main/java/com/smartspend/transaction/PendingTransaction.java`, `PendingTransactionRepository.java`
- Modify: `backend/src/main/java/com/smartspend/transaction/TransactionRepository.java`

**Interfaces:**
- Produces:
  - Entity `PendingTransaction`, mirroring the `Transaction` entity style, with fields `id`, `userId`, `documentId`, `categoryId` (nullable), `amount`, `type`, `description`, `transactionDate`, `suggestedCategory`, `dedupHash`, `createdAt`.
  - `PendingTransactionRepository extends JpaRepository<PendingTransaction, UUID>` with `List<PendingTransaction> findByDocumentIdAndUserIdOrderByTransactionDateDesc(UUID documentId, UUID userId)`, `Optional<PendingTransaction> findByIdAndUserId(UUID id, UUID userId)`, `boolean existsByDocumentId(UUID documentId)`, and `void deleteByDocumentIdAndUserId(UUID documentId, UUID userId)`.
  - `TransactionRepository.existsDuplicate(UUID userId, LocalDate date, BigDecimal amount, String type, String description)` as a `@Query` comparing `lower(trim(description))` and returning `boolean`.
- Schema per spec section 1, plus `category_id UUID REFERENCES categories(id) ON DELETE SET NULL`, and the unique constraint `(document_id, dedup_hash)`.

- [ ] **Step 1:** Write the migration, entity and repository methods.
- [ ] **Step 2:** Run `cd backend && ./mvnw -q -DskipTests compile` (Windows: `mvnw.cmd`). Expected: BUILD SUCCESS. Migration application is verified in Task 5's end-to-end run.
- [ ] **Step 3:** Commit: `feat(backend): add pending_transactions schema and repositories`.

### Task 4: PendingTransactionService (core logic)

**Files:**
- Create: `backend/src/main/java/com/smartspend/transaction/PendingTransactionService.java`, `PendingTransactionResponse.java`, `UpdatePendingTransactionRequest.java`, `ConfirmRequest.java`, `ConfirmResult.java`
- Test: `backend/src/test/java/com/smartspend/transaction/PendingTransactionServiceTest.java` (Mockito, no Spring context)

**Interfaces:**
- Consumes: the repositories from Task 3 and `CategoryRepository.findByUserIdOrIsSystemTrue(UUID)`.
- Produces `@Service PendingTransactionService` with:
  - `void saveExtracted(UUID userId, UUID documentId, List<Map<String,Object>> rows)`. It returns immediately if `existsByDocumentId` is true. It computes `dedupHash`, drops rows that repeat a hash within the batch, matches `suggested_category` to a category by case-insensitive name, and saves.
  - `List<PendingTransactionResponse> list(UUID userId, UUID documentId)`.
  - `PendingTransactionResponse update(UUID userId, UUID id, UpdatePendingTransactionRequest req)`. It throws `ResourceNotFoundException` for a missing or foreign row, and validates `amount > 0` and `type` in (INCOME, EXPENSE).
  - `ConfirmResult confirm(UUID userId, UUID documentId, List<UUID> ids)` with `ConfirmResult(int imported, int skipped)`. It runs `@Transactional`. It loads only rows with the given IDs that belong to the user and the document, skips any with `existsDuplicate` or repeated within the batch, saves the rest as `Transaction`, then calls `deleteByDocumentIdAndUserId`. It is idempotent: with no matching rows it returns `(0, 0)`.
  - `static String computeDedupHash(LocalDate date, BigDecimal amount, String type, String description)` per Global Constraints.

- [ ] **Step 1: Write failing tests:**
  - `computeDedupHash_isCaseAndWhitespaceInsensitive`, and `computeDedupHash_treatsEqualAmountsAtDifferentScaleAsSame` (`12` and `12.00`).
  - `saveExtracted_skipsWhenDocumentAlreadyHasPending` (verify `saveAll` is never called).
  - `saveExtracted_matchesCategoryByNameIgnoringCase`, and `saveExtracted_leavesCategoryNullWhenNoMatch`.
  - `confirm_importsSelectedAndDeletesPending` (verify `Transaction` saves and the delete call).
  - `confirm_skipsDuplicateOfExistingTransaction` (`existsDuplicate` returns true, giving `skipped == 1`).
  - `confirm_ignoresRowsOfOtherUser` (the repository returns nothing for the foreign IDs, so the result is `(0, 0)` and no saves happen).
  - `confirm_twiceImportsOnce` (the second call finds no rows).
  - `update_rejectsNonPositiveAmount`, and `update_foreignRowThrowsNotFound`.
- [ ] **Step 2:** Run `./mvnw -q test -Dtest=PendingTransactionServiceTest`. Expected: FAIL (class missing).
- [ ] **Step 3:** Implement the service. Parse `date` with `LocalDate.parse` and `amount` via `new BigDecimal(String.valueOf(...)).setScale(2, RoundingMode.HALF_UP)`. Skip rows that fail to parse.
- [ ] **Step 4:** Re-run. Expected: all PASS.
- [ ] **Step 5:** Commit: `feat(backend): add pending transaction service with dedup and confirm`.

### Task 5: Controller endpoints and status hook

**Files:**
- Create: `backend/src/main/java/com/smartspend/transaction/PendingTransactionController.java`
- Modify: `backend/src/main/java/com/smartspend/document/DocumentController.java` (`getDocumentStatus`)
- Test: `backend/src/test/java/com/smartspend/document/DocumentStatusExtractionTest.java`

**Interfaces:**
- Consumes: `PendingTransactionService` from Task 4.
- Produces the endpoints from spec section 3: `GET /documents/{id}/pending-transactions`, `PUT /pending-transactions/{id}`, and `POST /documents/{id}/pending-transactions/confirm` with body `ConfirmRequest{ids}`. Each resolves the current user as `DocumentController.getCurrentUserId()` does. GET and confirm must first verify the document belongs to the user (404 otherwise).
- `DocumentResponse` gains `int pendingTransactionCount`, filled in `toResponse` from the repository count, so the frontend can show "Review N transactions".

- [ ] **Step 1: Write a failing test.** Mockito-based `DocumentStatusExtractionTest`: with the AI status response `{status: "ready", chunks_indexed: 5, transactions: [row], extraction_error: null}`, `getDocumentStatus` calls `saveExtracted` once and sets `READY` and `chunksIndexed = 5`. A second case has `extraction_error: "boom"`, so the status is `READY`, `errorMessage` is `"boom"` and `saveExtracted` is not called with rows. A third has `chunks_indexed` as a plain legacy int-free response and checks that it does not throw.
- [ ] **Step 2:** Run it. Expected: FAIL.
- [ ] **Step 3:** Implement the controller and the hook. In the `ready` branch, read `transactions` (a list of maps) and call `saveExtracted` when it is non-empty. Wrap it in try/catch so a save failure is stored as the warning in `errorMessage` rather than failing the status call.
- [ ] **Step 4:** Run `./mvnw -q test`. Expected: all PASS.
- [ ] **Step 5:** Commit: `feat(backend): expose pending transaction endpoints and persist extraction results`.

### Task 6: Frontend review UI

**Files:**
- Modify: `frontend/app/(dashboard)/documents/page.tsx`
- Create: `frontend/components/PendingTransactionsModal.tsx`

**Interfaces:**
- Consumes: the endpoints from Task 5 via `apiJson`/`apiFetch` in `frontend/lib/api.ts`, and the existing categories endpoint for the dropdown. Check `frontend/app/(dashboard)/transactions/page.tsx` for how categories are fetched.
- Produces: `PendingTransactionsModal({ documentId, onClose, onImported })`. A READY document with `pendingTransactionCount > 0` shows a "Review N transactions" button.

- [ ] **Step 1:** Add `pendingTransactionCount: number` and `errorMessage?: string` to the `Document` interface, and the button and warning line to the row.
- [ ] **Step 2:** Build the modal. It loads the pending rows and shows a table with one checkbox per row (default checked) and inputs for date, amount, type, description and category. Edits `PUT` on blur. "Import selected" `POST`s the checked IDs, then shows "Imported X, skipped Y duplicates" and calls `onImported`, which refreshes the documents list.
- [ ] **Step 3:** Run `cd frontend && npm run build`. Expected: builds with no type errors.
- [ ] **Step 4:** Commit: `feat(frontend): review and import extracted transactions`.

### Task 7: End-to-end verification

- [ ] **Step 1:** Start the stack with `docker compose up --build`. Check the backend logs for `Migrating schema ... to version "9"`.
- [ ] **Step 2:** Upload a text-based sample statement PDF containing at least one debit, one credit and a duplicate line. Wait for READY and confirm "Review N transactions" appears.
- [ ] **Step 3:** Edit one row, untick one, and import. Check the transactions page and the summary. Re-upload the same file and import, and confirm the duplicates are skipped.
- [ ] **Step 4:** Upload a non-statement PDF and confirm the document is READY with no review button and no errors.
- [ ] **Step 5:** Report results. Do not claim success without having run these steps.
