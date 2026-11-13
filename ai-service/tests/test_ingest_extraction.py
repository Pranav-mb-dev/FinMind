import asyncio
import json
import logging
from datetime import date
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from services import ingest_service
from services.extraction_service import ExtractedTransaction
from routers import ingest as ingest_router


def _tx(desc, amt="10.5"):
    return ExtractedTransaction(date=date(2025, 3, 4), amount=Decimal(amt),
                                type="EXPENSE", description=desc, suggested_category="Food")


def _run(extract_mock, tmp_path):
    f = tmp_path / "a.pdf"
    f.write_bytes(b"x")
    doc = MagicMock(page_content="Some statement text. " * 10)
    with patch.object(ingest_service, "PyPDFLoader") as loader, \
         patch.object(ingest_service, "GoogleGenerativeAIEmbeddings") as emb, \
         patch.object(ingest_service, "QdrantClient") as qc, \
         patch.object(ingest_service, "extract_transactions", extract_mock):
        loader.return_value.load.return_value = [doc]
        emb.return_value.embed_documents.side_effect = lambda c: [[0.0] * 3 for _ in c]
        qc.return_value.collection_exists.return_value = True
        return asyncio.run(ingest_service.process_document(str(f), "u", "d"))


def test_returns_dict_with_transactions(tmp_path):
    res = _run(AsyncMock(return_value=[_tx("a"), _tx("b")]), tmp_path)
    assert res["chunks_indexed"] >= 1
    assert res["extraction_error"] is None
    assert len(res["transactions"]) == 2
    assert res["transactions"][0] == {"date": "2025-03-04", "amount": 10.5, "type": "EXPENSE",
                                      "description": "a", "suggested_category": "Food"}
    json.dumps(res)


def test_extraction_failure_does_not_fail_indexing(tmp_path, caplog):
    secret = "boom: 403 API key AIza-secret invalid"
    with caplog.at_level(logging.ERROR, logger=ingest_service.logger.name):
        res = _run(AsyncMock(side_effect=RuntimeError(secret)), tmp_path)
    assert res["chunks_indexed"] >= 1
    assert res["transactions"] == []
    assert res["extraction_error"] == "Transaction extraction failed; document text was still indexed."
    assert secret not in res["extraction_error"]
    # The full exception is logged server-side instead of being returned to the user.
    records = [r for r in caplog.records if r.name == ingest_service.logger.name]
    assert records and records[0].exc_info is not None
    assert secret in caplog.text


def _client():
    app = FastAPI()
    app.include_router(ingest_router.router)
    return TestClient(app)


def _status(result):
    with patch.object(ingest_router, "AsyncResult") as ar:
        ar.return_value.state = "SUCCESS"
        ar.return_value.result = result
        return _client().get("/ingest/status/t1").json()


def test_status_ready_returns_transactions():
    tx = [{"date": "2025-03-04", "amount": 1.0, "type": "EXPENSE",
           "description": "x", "suggested_category": None}]
    body = _status({"chunks_indexed": 5, "transactions": tx, "extraction_error": None})
    assert body["status"] == "ready"
    assert body["chunks_indexed"] == 5
    assert body["transactions"] == tx
    assert body["extraction_error"] is None


def test_status_ready_accepts_legacy_int():
    body = _status(7)
    assert body["chunks_indexed"] == 7
    assert body["transactions"] == []
    assert body["extraction_error"] is None
