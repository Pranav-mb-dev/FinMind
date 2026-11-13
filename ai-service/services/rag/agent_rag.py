"""Router-shaped agentic RAG.

Instead of a ReAct loop that pays one LLM call per tool step, this pipeline:

1. Makes ONE small routing call that returns structured JSON naming which data
   sources are needed (documents / transactions / summary / budgets), a date
   range, and a retrieval query.
2. Fetches the chosen sources directly in code, concurrently, with no LLM in
   the loop.
3. Makes ONE answer call with the gathered context.

Every question therefore costs at most two Gemini calls (one if no data is
needed), regardless of how many sources are consulted.
"""

import asyncio
import json
import logging
from dataclasses import dataclass, field
from datetime import date

import httpx
from langchain_google_genai import ChatGoogleGenerativeAI

from core.config import settings
from services.rag.naive_rag import RAGResult, SYSTEM_PROMPT, _extract_text, retrieve_chunks

logger = logging.getLogger(__name__)

VALID_SOURCES = {"documents", "transactions", "summary", "budgets"}
FALLBACK_SOURCES = {"documents", "summary"}
MAX_TRANSACTION_ROWS = 60
DOCUMENT_TOP_K = 5
DOCUMENT_SECTION_TITLE = "Uploaded documents (most relevant excerpts)"
CHUNK_SEPARATOR = "\n\n---\n\n"

ROUTER_PROMPT = """You are the query router for FinMind, a personal finance assistant.
Decide which of the user's data sources are needed to answer their question. Today is {today}.

Available sources:
- "documents": semantic search over the user's uploaded bank statements and financial documents. Use for questions about statement contents, specific line items, account details, or anything the user may have uploaded.
- "transactions": the individual transactions the user has recorded in the app for a date range. Use when the user asks about specific purchases, merchants, or wants a list of items.
- "summary": total income, total expenses and net balance for a date range. Use for "how much did I spend / earn / save" questions.
- "budgets": the user's budget limits per category. Use when the question mentions budgets, limits, or overspending.

Rules:
- Pick the MINIMUM set of sources that can answer the question.
- Pick NO sources (empty list) when the question is general knowledge, advice, or small talk that does not require the user's own data.
- When "transactions" or "summary" is selected, always give start_date and end_date in YYYY-MM-DD. If the user names no period, use the current month to date.
- When "documents" is selected, give a short search_query optimised for retrieval.

Return ONLY a JSON object, no explanation, with exactly these keys:
{{"sources": [], "start_date": null, "end_date": null, "search_query": null}}

User question: {question}"""

GENERAL_SYSTEM_PROMPT = (
    "You are FinMind, a personal finance AI assistant. "
    "The user's question does not require their personal financial data. "
    "Answer helpfully and concisely with general personal-finance guidance. "
    "Do not invent any figures about the user's own finances."
)

NO_DATA_MESSAGE = (
    "I don't have any financial data on file for that yet. "
    "Please upload a bank statement or add some transactions and budgets first."
)


@dataclass
class RouteDecision:
    sources: set[str] = field(default_factory=set)
    start_date: date | None = None
    end_date: date | None = None
    search_query: str | None = None


# ---------------------------------------------------------------------------
# Step 1: routing
# ---------------------------------------------------------------------------

def _parse_date(value) -> date | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        return date.fromisoformat(value.strip()[:10])
    except ValueError:
        return None


def _strip_code_fence(text: str) -> str:
    cleaned = text.strip()
    if cleaned.startswith("```"):
        first_newline = cleaned.find("\n")
        cleaned = cleaned[first_newline + 1:] if first_newline != -1 else cleaned[3:]
        cleaned = cleaned.removesuffix("```").strip()
    return cleaned.removeprefix("json").strip()


def _parse_route(raw: str, question: str, today: date) -> RouteDecision:
    """Turn the router LLM's text into a RouteDecision, with safe fallbacks."""
    try:
        data = json.loads(_strip_code_fence(raw))
        if not isinstance(data, dict):
            raise ValueError("router output is not an object")
    except (json.JSONDecodeError, ValueError):
        logger.warning("Router returned unparseable output; falling back to %s", sorted(FALLBACK_SOURCES))
        data = {"sources": sorted(FALLBACK_SOURCES), "search_query": question}

    raw_sources = data.get("sources") or []
    if not isinstance(raw_sources, list):
        raw_sources = []
    sources = {s for s in raw_sources if isinstance(s, str) and s in VALID_SOURCES}

    search_query = data.get("search_query")
    if not isinstance(search_query, str) or not search_query.strip():
        search_query = None

    decision = RouteDecision(
        sources=sources,
        start_date=_parse_date(data.get("start_date")),
        end_date=_parse_date(data.get("end_date")),
        search_query=search_query,
    )

    if "documents" in decision.sources and not decision.search_query:
        decision.search_query = question

    if decision.sources & {"transactions", "summary"}:
        if decision.start_date is None:
            decision.start_date = today.replace(day=1)
        if decision.end_date is None:
            decision.end_date = today
        if decision.end_date < decision.start_date:
            decision.start_date, decision.end_date = decision.end_date, decision.start_date

    return decision


async def _route(llm: ChatGoogleGenerativeAI, question: str, today: date) -> RouteDecision:
    response = await llm.ainvoke([
        {"role": "user", "content": ROUTER_PROMPT.format(today=today.isoformat(), question=question)},
    ])
    return _parse_route(_extract_text(response.content), question, today)


# ---------------------------------------------------------------------------
# Step 2: retrieval (no LLM involved)
# ---------------------------------------------------------------------------

async def _spring_get(client: httpx.AsyncClient, path: str, user_id: str, params: dict | None = None):
    """GET a Spring endpoint as the user. Returns parsed JSON, or None on any failure."""
    try:
        resp = await client.get(
            path,
            params=params or {},
            headers={"X-Internal-User-Id": user_id},
            timeout=10.0,
        )
        if resp.status_code != 200:
            logger.warning("Spring %s returned %s", path, resp.status_code)
            return None
        return resp.json()
    except (httpx.HTTPError, ValueError) as exc:
        logger.warning("Spring %s failed: %s", path, exc)
        return None


def _date_params(decision: RouteDecision) -> dict:
    return {
        "startDate": decision.start_date.isoformat(),
        "endDate": decision.end_date.isoformat(),
    }


async def _gather_context(user_id: str, decision: RouteDecision) -> dict[str, str]:
    """Fetch every selected source concurrently and return formatted sections."""
    tasks: dict[str, asyncio.Future] = {}
    needs_categories = bool(decision.sources & {"transactions", "budgets"})

    async with httpx.AsyncClient(base_url=settings.spring_boot_url) as client:
        if "documents" in decision.sources:
            tasks["documents"] = asyncio.ensure_future(
                retrieve_chunks(user_id, decision.search_query, top_k=DOCUMENT_TOP_K)
            )
        if "transactions" in decision.sources:
            tasks["transactions"] = asyncio.ensure_future(
                _spring_get(client, "/transactions", user_id, _date_params(decision))
            )
        if "summary" in decision.sources:
            tasks["summary"] = asyncio.ensure_future(
                _spring_get(client, "/transactions/summary", user_id, _date_params(decision))
            )
        if "budgets" in decision.sources:
            tasks["budgets"] = asyncio.ensure_future(_spring_get(client, "/budgets", user_id))
        if needs_categories:
            tasks["categories"] = asyncio.ensure_future(_spring_get(client, "/categories", user_id))

        results = await asyncio.gather(*tasks.values(), return_exceptions=True)

    raw = {}
    for key, value in zip(tasks.keys(), results):
        if isinstance(value, Exception):
            logger.warning("Fetching %s failed: %s", key, value)
            raw[key] = None
        else:
            raw[key] = value

    categories = _category_map(raw.get("categories"))
    sections: dict[str, str] = {}

    if raw.get("documents"):
        sections[DOCUMENT_SECTION_TITLE] = CHUNK_SEPARATOR.join(raw["documents"])
    if raw.get("transactions"):
        sections[f"Transactions {decision.start_date} to {decision.end_date}"] = _format_transactions(
            raw["transactions"], categories
        )
    if raw.get("summary"):
        sections["Spending summary"] = _format_summary(raw["summary"])
    if raw.get("budgets"):
        sections["Budgets"] = _format_budgets(raw["budgets"], categories)

    return sections


def _category_map(rows) -> dict[str, str]:
    if not isinstance(rows, list):
        return {}
    return {
        str(r.get("id")): str(r.get("name", ""))
        for r in rows
        if isinstance(r, dict) and r.get("id")
    }


def _category_name(category_id, categories: dict[str, str]) -> str:
    if not category_id:
        return "Uncategorised"
    return categories.get(str(category_id), "Uncategorised")


def _format_transactions(rows, categories: dict[str, str]) -> str:
    if not isinstance(rows, list) or not rows:
        return ""
    lines = ["date | type | amount | category | description"]
    for r in rows[:MAX_TRANSACTION_ROWS]:
        if not isinstance(r, dict):
            continue
        lines.append(
            f"{r.get('transactionDate', '')} | {r.get('type', '')} | {r.get('amount', '')} | "
            f"{_category_name(r.get('categoryId'), categories)} | {r.get('description') or ''}"
        )
    if len(rows) > MAX_TRANSACTION_ROWS:
        lines.append(
            f"(showing the {MAX_TRANSACTION_ROWS} most recent of {len(rows)} transactions; "
            f"totals are available in the spending summary)"
        )
    return "\n".join(lines)


def _format_summary(summary) -> str:
    if not isinstance(summary, dict):
        return ""
    return (
        f"Period {summary.get('startDate')} to {summary.get('endDate')}: "
        f"total income {summary.get('totalIncome')}, "
        f"total expenses {summary.get('totalExpenses')}, "
        f"net balance {summary.get('netBalance')}"
    )


def _format_budgets(rows, categories: dict[str, str]) -> str:
    if not isinstance(rows, list) or not rows:
        return ""
    lines = ["category | limit | period | from | to"]
    for r in rows:
        if not isinstance(r, dict):
            continue
        lines.append(
            f"{_category_name(r.get('categoryId'), categories)} | {r.get('amount', '')} | "
            f"{r.get('period', '')} | {r.get('startDate', '')} | {r.get('endDate', '')}"
        )
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Step 3: answering
# ---------------------------------------------------------------------------

def _build_context_prompt(sections: dict[str, str], question: str, today: date) -> str:
    parts = [f"Today's date: {today.isoformat()}"]
    for title, body in sections.items():
        parts.append(f"### {title}\n{body}")
    parts.append(f"### User Question\n{question}")
    return "\n\n".join(parts)


async def run(user_id: str, message: str) -> RAGResult:
    today = date.today()

    router_llm = ChatGoogleGenerativeAI(
        model=settings.gemini_model,
        google_api_key=settings.gemini_api_key,
        temperature=0.0,
    )
    decision = await _route(router_llm, message, today)
    logger.info(
        "Router chose sources=%s range=%s..%s",
        sorted(decision.sources), decision.start_date, decision.end_date,
    )

    answer_llm = ChatGoogleGenerativeAI(
        model=settings.gemini_model,
        google_api_key=settings.gemini_api_key,
        temperature=0.2,
    )

    if not decision.sources:
        response = await answer_llm.ainvoke([
            {"role": "system", "content": GENERAL_SYSTEM_PROMPT},
            {"role": "user", "content": message},
        ])
        return RAGResult(response=_extract_text(response.content), sources=[])

    sections = await _gather_context(user_id, decision)
    if not sections:
        return RAGResult(response=NO_DATA_MESSAGE, sources=[])

    response = await answer_llm.ainvoke([
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": _build_context_prompt(sections, message, today)},
    ])

    document_sources = []
    if DOCUMENT_SECTION_TITLE in sections:
        document_sources = sections[DOCUMENT_SECTION_TITLE].split(CHUNK_SEPARATOR)
    return RAGResult(response=_extract_text(response.content), sources=document_sources[:DOCUMENT_TOP_K])
