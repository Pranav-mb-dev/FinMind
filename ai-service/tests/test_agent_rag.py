"""Tests for the router-shaped agent RAG pipeline.

Covers the pure routing/formatting helpers and the three run() paths
(no data needed, data fetched, nothing found) with the LLM and fetchers mocked.
"""

import asyncio
import os
from datetime import date
from unittest.mock import AsyncMock, MagicMock, patch


os.environ.setdefault("GEMINI_API_KEY", "test-key")

from services.rag import agent_rag
from services.rag.agent_rag import (
    MAX_TRANSACTION_ROWS,
    NO_DATA_MESSAGE,
    RouteDecision,
    _format_transactions,
    _parse_route,
)

TODAY = date(2026, 11, 3)


# ---------------------------------------------------------------------------
# _parse_route
# ---------------------------------------------------------------------------

class TestParseRoute:
    def test_valid_json_is_parsed(self):
        raw = '{"sources": ["summary", "budgets"], "start_date": "2026-10-01", "end_date": "2026-10-31", "search_query": null}'
        d = _parse_route(raw, "how much did I spend in October?", TODAY)
        assert d.sources == {"summary", "budgets"}
        assert d.start_date == date(2026, 10, 1)
        assert d.end_date == date(2026, 10, 31)
        assert d.search_query is None

    def test_code_fences_are_stripped(self):
        raw = '```json\n{"sources": ["documents"], "search_query": "zorblax budget code"}\n```'
        d = _parse_route(raw, "What is the Zorblax budget code?", TODAY)
        assert d.sources == {"documents"}
        assert d.search_query == "zorblax budget code"

    def test_empty_sources_means_no_retrieval(self):
        d = _parse_route('{"sources": []}', "what is an index fund?", TODAY)
        assert d.sources == set()
        assert d.start_date is None

    def test_unparseable_output_falls_back(self):
        d = _parse_route("I think you need documents and a summary.", "spending?", TODAY)
        assert d.sources == {"documents", "summary"}
        assert d.search_query == "spending?"
        assert d.start_date == date(2026, 11, 1)
        assert d.end_date == TODAY

    def test_unknown_sources_are_dropped(self):
        d = _parse_route('{"sources": ["documents", "crypto", 42]}', "q", TODAY)
        assert d.sources == {"documents"}

    def test_missing_dates_default_to_current_month(self):
        d = _parse_route('{"sources": ["transactions"]}', "recent purchases", TODAY)
        assert d.start_date == date(2026, 11, 1)
        assert d.end_date == TODAY

    def test_reversed_dates_are_swapped(self):
        raw = '{"sources": ["summary"], "start_date": "2026-10-31", "end_date": "2026-10-01"}'
        d = _parse_route(raw, "q", TODAY)
        assert d.start_date == date(2026, 10, 1)
        assert d.end_date == date(2026, 10, 31)

    def test_documents_without_query_uses_question(self):
        d = _parse_route('{"sources": ["documents"]}', "What is my account number?", TODAY)
        assert d.search_query == "What is my account number?"


# ---------------------------------------------------------------------------
# _format_transactions
# ---------------------------------------------------------------------------

class TestFormatTransactions:
    def test_maps_category_names(self):
        rows = [{"transactionDate": "2026-11-01", "type": "EXPENSE", "amount": 12.5,
                 "categoryId": "c1", "description": "Coffee"}]
        out = _format_transactions(rows, {"c1": "Food"})
        assert "Food" in out
        assert "Coffee" in out

    def test_caps_rows_and_notes_truncation(self):
        rows = [{"transactionDate": "2026-11-01", "type": "EXPENSE", "amount": i, "categoryId": None}
                for i in range(MAX_TRANSACTION_ROWS + 10)]
        out = _format_transactions(rows, {})
        assert out.count("\n") == MAX_TRANSACTION_ROWS + 1  # header + rows + truncation note
        assert f"of {MAX_TRANSACTION_ROWS + 10} transactions" in out


# ---------------------------------------------------------------------------
# run()
# ---------------------------------------------------------------------------

def _fake_llm(*responses):
    """Return a ChatGoogleGenerativeAI stand-in whose ainvoke yields the given texts in order."""
    llm = MagicMock()
    llm.ainvoke = AsyncMock(side_effect=[MagicMock(content=r) for r in responses])
    return llm



def test_run_general_question_skips_retrieval():
    asyncio.run(_run_general_question_skips_retrieval())


async def _run_general_question_skips_retrieval():
    router = _fake_llm('{"sources": []}')
    answerer = _fake_llm("An index fund tracks a market index.")
    with patch.object(agent_rag, "ChatGoogleGenerativeAI", side_effect=[router, answerer]), \
         patch.object(agent_rag, "_gather_context", new=AsyncMock()) as gather:
        result = await agent_rag.run("u1", "what is an index fund?")

    assert result.response == "An index fund tracks a market index."
    assert result.sources == []
    gather.assert_not_awaited()
    assert answerer.ainvoke.await_count == 1



def test_run_fetches_sources_and_answers_once():
    asyncio.run(_run_fetches_sources_and_answers_once())


async def _run_fetches_sources_and_answers_once():
    router = _fake_llm('{"sources": ["documents", "summary"], "search_query": "october spending"}')
    answerer = _fake_llm("You spent 120 in October.")
    sections = {
        "Uploaded documents (most relevant excerpts)": "chunk A\n\n---\n\nchunk B",
        "Spending summary": "Period 2026-10-01 to 2026-10-31: total income 0, total expenses 120, net balance -120",
    }
    with patch.object(agent_rag, "ChatGoogleGenerativeAI", side_effect=[router, answerer]), \
         patch.object(agent_rag, "_gather_context", new=AsyncMock(return_value=sections)) as gather:
        result = await agent_rag.run("u1", "how much did I spend in October?")

    assert result.response == "You spent 120 in October."
    assert result.sources == ["chunk A", "chunk B"]
    decision: RouteDecision = gather.await_args.args[1]
    assert decision.sources == {"documents", "summary"}
    assert decision.search_query == "october spending"
    # Exactly two LLM calls in total: route + answer.
    assert router.ainvoke.await_count == 1
    assert answerer.ainvoke.await_count == 1
    prompt = answerer.ainvoke.await_args.args[0][1]["content"]
    assert "chunk A" in prompt and "total expenses 120" in prompt



def test_run_returns_no_data_message_without_llm_answer():
    asyncio.run(_run_returns_no_data_message_without_llm_answer())


async def _run_returns_no_data_message_without_llm_answer():
    router = _fake_llm('{"sources": ["budgets"]}')
    answerer = _fake_llm("should not be called")
    with patch.object(agent_rag, "ChatGoogleGenerativeAI", side_effect=[router, answerer]), \
         patch.object(agent_rag, "_gather_context", new=AsyncMock(return_value={})):
        result = await agent_rag.run("u1", "am I over budget?")

    assert result.response == NO_DATA_MESSAGE
    assert result.sources == []
    assert answerer.ainvoke.await_count == 0
