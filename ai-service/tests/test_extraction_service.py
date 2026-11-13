import json
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from services import extraction_service as ex

ROW = {"date": "2025-03-04", "amount": 120.5, "type": "EXPENSE",
       "description": "Swiggy", "suggested_category": "Food"}


def test_parses_plain_json_array():
    rows = ex.parse_transactions(json.dumps([ROW]))
    assert len(rows) == 1
    assert rows[0].amount == Decimal("120.50")
    assert rows[0].type == "EXPENSE"


def test_strips_markdown_fences_and_prose():
    raw = "Here you go:\n```json\n" + json.dumps([ROW]) + "\n```\n"
    assert len(ex.parse_transactions(raw)) == 1


def test_drops_invalid_rows():
    bad = [
        {**ROW, "date": "03/04/2025"},
        {**ROW, "amount": 0},
        {**ROW, "amount": -5},
        {**ROW, "type": "TRANSFER"},
    ]
    rows = ex.parse_transactions(json.dumps(bad + [ROW]))
    assert len(rows) == 1


def test_garbage_returns_empty():
    assert ex.parse_transactions("sorry I can't") == []
    assert ex.parse_transactions("") == []


def test_dedups_exact_duplicates_within_run():
    assert len(ex.parse_transactions(json.dumps([ROW, dict(ROW)]))) == 1


@pytest.mark.asyncio
async def test_batches_long_text():
    text = "\n".join(["x" * 99] * 300)  # ~30000 chars
    calls = []

    async def fake(self, messages):
        calls.append(messages)
        n = len(calls)
        return MagicMock(content=json.dumps([{**ROW, "description": f"d{n}"}]))

    with patch.object(ex.ChatGoogleGenerativeAI, "ainvoke", fake):
        out = await ex.extract_transactions(text)
    assert len(calls) == 3
    assert len(out) == 3


@pytest.mark.asyncio
async def test_empty_text_makes_no_call():
    mock = AsyncMock()
    with patch.object(ex.ChatGoogleGenerativeAI, "ainvoke", mock):
        assert await ex.extract_transactions("  \n ") == []
    mock.assert_not_called()


def test_amount_quantized_half_up():
    rows = ex.parse_transactions(json.dumps([{**ROW, "amount": 120.555}]))
    assert str(rows[0].amount) == "120.56"
    rows = ex.parse_transactions(json.dumps([ROW]))
    assert str(rows[0].amount) == "120.50"


def test_nan_amount_dropped():
    raw = json.dumps([{**ROW, "amount": "NaN"}, {**ROW, "amount": "Infinity"}, ROW])
    assert len(ex.parse_transactions(raw)) == 1


def test_dedup_after_quantize():
    raw = json.dumps([{**ROW, "amount": 10.5}, {**ROW, "amount": 10.50}, {**ROW, "amount": "10.500"}])
    assert len(ex.parse_transactions(raw)) == 1


def test_bracket_in_prose():
    raw = "Note [1]: here you go [ " + json.dumps(ROW) + " ] done"
    assert len(ex.parse_transactions(raw)) == 1


@pytest.mark.asyncio
async def test_cross_batch_dedup_and_batch_size():
    text = "\n".join(["x" * 99] * 300)
    sizes = []

    async def fake(self, messages):
        sizes.append(len(messages[1]["content"]))
        return MagicMock(content=json.dumps([ROW]))

    with patch.object(ex.ChatGoogleGenerativeAI, "ainvoke", fake):
        out = await ex.extract_transactions(text)
    assert len(sizes) == 3
    assert all(s <= 12000 for s in sizes)
    assert len(out) == 1


def test_type_is_case_and_whitespace_insensitive():
    raw = json.dumps([
        {**ROW, "type": "Expense", "description": "a"},
        {**ROW, "type": " income ", "description": "b"},
        {**ROW, "type": "TRANSFER", "description": "c"},
    ])
    rows = ex.parse_transactions(raw)
    assert [(r.description, r.type) for r in rows] == [("a", "EXPENSE"), ("b", "INCOME")]


def test_huge_amount_row_dropped_not_raised():
    raw = json.dumps([ROW, {**ROW, "description": "big", "amount": 1e30}])
    rows = ex.parse_transactions(raw)
    assert len(rows) == 1
    assert rows[0].description == "Swiggy"
