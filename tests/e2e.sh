#!/usr/bin/env bash
set +e

API=http://localhost:8080
AI=http://localhost:8000
PASS_COUNT=0
FAIL_COUNT=0
RESULTS=()

pass() { PASS_COUNT=$((PASS_COUNT+1)); RESULTS+=("PASS|$1"); echo "PASS: $1"; }
fail() { FAIL_COUNT=$((FAIL_COUNT+1)); RESULTS+=("FAIL|$1|$2"); echo "FAIL: $1"; echo "      $2"; }

snippet() { echo "$1" | head -c 200; }

# ─── SETUP: Register + login two users ───────────────────────────────

echo "=== SETUP ==="

REG_A=$(curl -s -w "\n%{http_code}" -X POST "$API/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"email":"e2e-a-'"$$"'@test.com","password":"testpass1","fullName":"User A"}')
REG_A_CODE=$(echo "$REG_A" | tail -1)
echo "Register A: $REG_A_CODE"

REG_B=$(curl -s -w "\n%{http_code}" -X POST "$API/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"email":"e2e-b-'"$$"'@test.com","password":"testpass2","fullName":"User B"}')
REG_B_CODE=$(echo "$REG_B" | tail -1)
echo "Register B: $REG_B_CODE"

TOKEN_A=$(curl -s -X POST "$API/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"e2e-a-'"$$"'@test.com","password":"testpass1"}')
echo "Token A: ${TOKEN_A:0:20}..."

TOKEN_B=$(curl -s -X POST "$API/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"e2e-b-'"$$"'@test.com","password":"testpass2"}')
echo "Token B: ${TOKEN_B:0:20}..."

# ─── SETUP: A uploads a small PDF ────────────────────────────────────

# Create a minimal PDF containing the sentinel text
PDF_FILE=$(mktemp --suffix=.pdf)
cat > "$PDF_FILE" << 'PDFEOF'
%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj
4 0 obj<</Length 74>>stream
BT /F1 12 Tf 100 700 Td (The Zorblax budget code is 7413.) Tj ET
endstream
endobj
5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj
xref
0 6
0000000000 65535 f
0000000009 00000 n
0000000058 00000 n
0000000115 00000 n
0000000266 00000 n
0000000390 00000 n
trailer<</Size 6/Root 1 0 R>>
startxref
459
%%EOF
PDFEOF

echo "Uploading PDF for User A..."
UPLOAD_RESP=$(curl -s -w "\n%{http_code}" -X POST "$API/documents/upload" \
  -H "Authorization: Bearer $TOKEN_A" \
  -F "file=@$PDF_FILE;filename=zorblax.pdf")
UPLOAD_CODE=$(echo "$UPLOAD_RESP" | tail -1)
UPLOAD_BODY=$(echo "$UPLOAD_RESP" | sed '$d')
DOC_ID=$(echo "$UPLOAD_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
echo "Upload response ($UPLOAD_CODE): $(snippet "$UPLOAD_BODY")"
echo "Document ID: $DOC_ID"

# ─── SETUP: Wait for indexing ─────────────────────────────────────────

echo "Waiting for indexing to complete..."
for i in $(seq 1 20); do
  sleep 3
  STATUS_RESP=$(curl -s -X GET "$API/documents/$DOC_ID/status" \
    -H "Authorization: Bearer $TOKEN_A")
  DOC_STATUS=$(echo "$STATUS_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")
  CHUNKS=$(echo "$STATUS_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('chunksIndexed',0))" 2>/dev/null || echo "0")
  echo "  Poll $i: status=$DOC_STATUS chunks=$CHUNKS"
  if [ "$DOC_STATUS" = "READY" ] || [ "$DOC_STATUS" = "FAILED" ]; then
    break
  fi
done

echo ""
echo "=== TESTS ==="
echo ""

# ─── T1: Auth required (no token) ────────────────────────────────────

echo "--- T1: Auth required (no token) ---"
for ENDPOINT in "/documents" "/chat/sessions" "/transactions"; do
  RESP=$(curl -s -w "\n%{http_code}" -X GET "$API$ENDPOINT")
  CODE=$(echo "$RESP" | tail -1)
  BODY=$(echo "$RESP" | sed '$d')
  if [ "$CODE" = "401" ] || [ "$CODE" = "403" ]; then
    pass "T1 no-token $ENDPOINT => $CODE"
  else
    fail "T1 no-token $ENDPOINT => $CODE (expected 401/403)" "$(snippet "$BODY")"
  fi
done

# ─── T2: Bad token ───────────────────────────────────────────────────

echo ""
echo "--- T2: Bad token ---"
BAD_TOKEN="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWtlQHRlc3QuY29tIn0.garbage"
for ENDPOINT in "/documents" "/chat/sessions" "/transactions"; do
  RESP=$(curl -s -w "\n%{http_code}" -X GET "$API$ENDPOINT" \
    -H "Authorization: Bearer $BAD_TOKEN")
  CODE=$(echo "$RESP" | tail -1)
  BODY=$(echo "$RESP" | sed '$d')
  if [ "$CODE" = "401" ] || [ "$CODE" = "403" ]; then
    pass "T2 bad-token $ENDPOINT => $CODE"
  else
    fail "T2 bad-token $ENDPOINT => $CODE (expected 401/403)" "$(snippet "$BODY")"
  fi
done

# ─── T3: Ingest works ────────────────────────────────────────────────

echo ""
echo "--- T3: Ingest works ---"
if [ "$DOC_STATUS" = "READY" ] && [ "$CHUNKS" -gt 0 ] 2>/dev/null; then
  pass "T3 ingest status=READY chunks=$CHUNKS"
else
  fail "T3 ingest status=$DOC_STATUS chunks=$CHUNKS (expected READY, chunks>0)" "$(snippet "$STATUS_RESP")"
fi

# ─── T4: RAG answers from own doc (all 3 modes) ─────────────────────

echo ""
echo "--- T4: RAG answers from own doc ---"

USER_A_ID=$(curl -s -X GET "$API/users/me" -H "Authorization: Bearer $TOKEN_A" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")

for MODE in naive advanced agent; do
  # Call AI service directly to test each mode (requires override port 8000)
  CHAT_RESP=$(curl -s --max-time 60 -X POST "$AI/api/v1/chat" \
    -H "Content-Type: application/json" \
    -d '{"user_id":"'"$USER_A_ID"'","message":"What is the Zorblax budget code?","mode":"'"$MODE"'"}' 2>&1)
  ANSWER=$(echo "$CHAT_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('response',''))" 2>/dev/null || echo "$CHAT_RESP")
  if echo "$ANSWER" | grep -q "7413"; then
    pass "T4 mode=$MODE found 7413 in answer"
  else
    fail "T4 mode=$MODE: 7413 not in answer" "$(snippet "$ANSWER")"
  fi
done

# ─── T5: Cross-user RAG isolation ────────────────────────────────────

echo ""
echo "--- T5: Cross-user RAG isolation ---"

USER_B_ID=$(curl -s -X GET "$API/users/me" -H "Authorization: Bearer $TOKEN_B" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")

for MODE in naive advanced agent; do
  CHAT_RESP=$(curl -s --max-time 60 -X POST "$AI/api/v1/chat" \
    -H "Content-Type: application/json" \
    -d '{"user_id":"'"$USER_B_ID"'","message":"What is the Zorblax budget code?","mode":"'"$MODE"'"}' 2>&1)
  ANSWER=$(echo "$CHAT_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('response',''))" 2>/dev/null || echo "$CHAT_RESP")
  if echo "$ANSWER" | grep -q "7413"; then
    fail "T5 mode=$MODE: B got 7413 (A's data leaked)" "$(snippet "$ANSWER")"
  else
    pass "T5 mode=$MODE: B did NOT get 7413"
  fi
done

# ─── T6: Cross-user object access ───────────────────────────────────

echo ""
echo "--- T6: Cross-user object access ---"

# B tries to read A's document
RESP=$(curl -s -w "\n%{http_code}" -X GET "$API/documents/$DOC_ID/status" \
  -H "Authorization: Bearer $TOKEN_B")
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
if [ "$CODE" = "403" ] || [ "$CODE" = "404" ]; then
  pass "T6 B reads A's doc status => $CODE"
else
  fail "T6 B reads A's doc status => $CODE (expected 403/404)" "$(snippet "$BODY")"
fi

# B tries to list A's documents (should return empty, not A's docs)
RESP=$(curl -s -X GET "$API/documents" -H "Authorization: Bearer $TOKEN_B")
DOC_COUNT=$(echo "$RESP" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "?")
if [ "$DOC_COUNT" = "0" ]; then
  pass "T6 B lists documents => empty (0 docs)"
else
  fail "T6 B lists documents => got $DOC_COUNT docs (expected 0)" "$(snippet "$RESP")"
fi

# A sends a chat, then B tries to read A's session
CHAT_A=$(curl -s -X POST "$API/chat" \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"message":"Hello test"}')
SESSION_A=$(echo "$CHAT_A" | python3 -c "import sys,json; print(json.load(sys.stdin).get('sessionId',''))" 2>/dev/null || echo "")
echo "A's session: $SESSION_A"

RESP=$(curl -s -w "\n%{http_code}" -X GET "$API/chat/sessions/$SESSION_A" \
  -H "Authorization: Bearer $TOKEN_B")
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
MSG_COUNT=$(echo "$BODY" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "?")
if [ "$CODE" = "403" ] || [ "$CODE" = "404" ] || [ "$MSG_COUNT" = "0" ]; then
  pass "T6 B reads A's chat session => $CODE msgs=$MSG_COUNT"
else
  fail "T6 B reads A's chat session => $CODE msgs=$MSG_COUNT (expected 403/404 or empty)" "$(snippet "$BODY")"
fi

# ─── T7: Cross-user writes ──────────────────────────────────────────

echo ""
echo "--- T7: Cross-user writes ---"

# A creates a transaction
TXN_RESP=$(curl -s -X POST "$API/transactions" \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"amount":42.00,"type":"EXPENSE","description":"E2E test txn","transactionDate":"2026-01-15"}')
TXN_ID=$(echo "$TXN_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
echo "A's transaction: $TXN_ID"

# A creates a budget
BUD_RESP=$(curl -s -X POST "$API/budgets" \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"amount":500.00,"period":"MONTHLY","startDate":"2026-01-01","endDate":"2026-01-31"}')
BUD_ID=$(echo "$BUD_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
echo "A's budget: $BUD_ID"

# B tries to delete A's transaction
RESP=$(curl -s -w "\n%{http_code}" -X DELETE "$API/transactions/$TXN_ID" \
  -H "Authorization: Bearer $TOKEN_B")
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
if [ "$CODE" = "403" ] || [ "$CODE" = "404" ]; then
  pass "T7 B deletes A's txn => $CODE"
else
  fail "T7 B deletes A's txn => $CODE (expected 403/404)" "$(snippet "$BODY")"
fi

# B tries to update A's budget
RESP=$(curl -s -w "\n%{http_code}" -X PUT "$API/budgets/$BUD_ID" \
  -H "Authorization: Bearer $TOKEN_B" \
  -H "Content-Type: application/json" \
  -d '{"amount":9999.00,"period":"WEEKLY","startDate":"2026-01-01"}')
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
if [ "$CODE" = "403" ] || [ "$CODE" = "404" ]; then
  pass "T7 B updates A's budget => $CODE"
else
  fail "T7 B updates A's budget => $CODE (expected 403/404)" "$(snippet "$BODY")"
fi

# B tries to delete A's budget
RESP=$(curl -s -w "\n%{http_code}" -X DELETE "$API/budgets/$BUD_ID" \
  -H "Authorization: Bearer $TOKEN_B")
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
if [ "$CODE" = "403" ] || [ "$CODE" = "404" ]; then
  pass "T7 B deletes A's budget => $CODE"
else
  fail "T7 B deletes A's budget => $CODE (expected 403/404)" "$(snippet "$BODY")"
fi

# Verify A's records still exist
TXN_CHECK=$(curl -s -X GET "$API/transactions" -H "Authorization: Bearer $TOKEN_A")
TXN_STILL=$(echo "$TXN_CHECK" | python3 -c "
import sys,json
txns = json.load(sys.stdin)
print('yes' if any(t['id']=='$TXN_ID' for t in txns) else 'no')
" 2>/dev/null || echo "?")

BUD_CHECK=$(curl -s -X GET "$API/budgets" -H "Authorization: Bearer $TOKEN_A")
BUD_STILL=$(echo "$BUD_CHECK" | python3 -c "
import sys,json
buds = json.load(sys.stdin)
print('yes' if any(b['id']=='$BUD_ID' for b in buds) else 'no')
" 2>/dev/null || echo "?")

if [ "$TXN_STILL" = "yes" ] && [ "$BUD_STILL" = "yes" ]; then
  pass "T7 A's records unchanged after B's attempts"
else
  fail "T7 A's records missing (txn=$TXN_STILL budget=$BUD_STILL)" "Txn check: $(snippet "$TXN_CHECK")"
fi

# ─── T8: Bad uploads ────────────────────────────────────────────────

echo ""
echo "--- T8: Bad uploads ---"

# .txt file
TXT_FILE=$(mktemp --suffix=.txt)
echo "not a pdf" > "$TXT_FILE"
RESP=$(curl -s -w "\n%{http_code}" -X POST "$API/documents/upload" \
  -H "Authorization: Bearer $TOKEN_A" \
  -F "file=@$TXT_FILE;filename=bad.txt")
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
if [ "$CODE" -ge 400 ] && [ "$CODE" -lt 500 ] 2>/dev/null; then
  pass "T8 .txt upload => $CODE (4xx)"
else
  # Check if it's a 500 with a clean error vs stack trace
  if echo "$BODY" | grep -qi "trace\|exception\|at com\.\|at java\."; then
    fail "T8 .txt upload => $CODE with stack trace" "$(snippet "$BODY")"
  else
    fail "T8 .txt upload => $CODE (expected 4xx)" "$(snippet "$BODY")"
  fi
fi
rm -f "$TXT_FILE"

# Empty PDF (0 bytes)
EMPTY_PDF=$(mktemp --suffix=.pdf)
> "$EMPTY_PDF"
RESP=$(curl -s -w "\n%{http_code}" -X POST "$API/documents/upload" \
  -H "Authorization: Bearer $TOKEN_A" \
  -F "file=@$EMPTY_PDF;filename=empty.pdf")
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
if echo "$BODY" | grep -qi "trace\|exception\|at com\.\|at java\."; then
  fail "T8 empty PDF => $CODE with stack trace (not clean error)" "$(snippet "$BODY")"
elif [ "$CODE" -ge 400 ] && [ "$CODE" -lt 500 ] 2>/dev/null; then
  pass "T8 empty PDF => $CODE (clean 4xx)"
elif [ "$CODE" = "500" ]; then
  fail "T8 empty PDF => 500 (expected clean error, not server error)" "$(snippet "$BODY")"
else
  pass "T8 empty PDF => $CODE (accepted, will fail in worker)"
fi
rm -f "$EMPTY_PDF"

# ─── T9: AI service not reachable from outside ──────────────────────

echo ""
echo "--- T9: AI service external reachability ---"
echo "(Testing if port 8000 is exposed on host)"
RESP=$(curl -s --max-time 3 -o /dev/null -w "%{http_code}" "$AI/health" 2>&1 || true)
if [ "$RESP" = "000" ] || [ -z "$RESP" ]; then
  pass "T9 AI service not reachable from host (connection refused/timeout)"
else
  fail "T9 AI service IS reachable from host (got $RESP)" "Port 8000 is published — docker-compose.override.yml is active or ports: still in docker-compose.yml"
fi

# ─── Cleanup ─────────────────────────────────────────────────────────
rm -f "$PDF_FILE"

# ─── Summary ─────────────────────────────────────────────────────────

echo ""
echo "============================================"
echo "        RESULTS: $PASS_COUNT PASS, $FAIL_COUNT FAIL"
echo "============================================"
printf "%-6s | %s\n" "Status" "Test"
echo "-------+-------------------------------------"
for R in "${RESULTS[@]}"; do
  STATUS=$(echo "$R" | cut -d'|' -f1)
  NAME=$(echo "$R" | cut -d'|' -f2)
  DETAIL=$(echo "$R" | cut -d'|' -f3-)
  printf "%-6s | %s\n" "$STATUS" "$NAME"
  if [ -n "$DETAIL" ]; then
    echo "       | -> $DETAIL"
  fi
done
echo ""
