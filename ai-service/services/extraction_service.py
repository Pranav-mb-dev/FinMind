import json
import re
from datetime import date
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from typing import Literal

from langchain_google_genai import ChatGoogleGenerativeAI
from pydantic import BaseModel, ValidationError, field_validator

from core.config import settings
from services.rag.naive_rag import _extract_text

MAX_BATCH_CHARS = 12000

EXTRACTION_PROMPT = (
    "You extract transactions from bank statement text. "
    "Output ONLY a JSON array, with no prose and no markdown. "
    "Each element has the keys: date, amount, type, description, suggested_category. "
    "date is an ISO date (YYYY-MM-DD); skip any row whose date is unclear. "
    "amount is always a positive number. "
    'type is "EXPENSE" for debits/withdrawals and "INCOME" for credits/deposits. '
    "description is the merchant or narration. "
    "suggested_category is a short category name or null. "
    "Skip balance lines and opening/closing balance lines. "
    "If there are no transactions, output []."
)


class ExtractedTransaction(BaseModel):
    date: date
    amount: Decimal
    type: Literal["INCOME", "EXPENSE"]
    description: str
    suggested_category: str | None = None

    @field_validator("type", mode="before")
    @classmethod
    def _normalize_type(cls, v):
        if isinstance(v, str):
            return v.strip().upper()
        return v

    @field_validator("amount")
    @classmethod
    def _quantize_amount(cls, v: Decimal) -> Decimal:
        if not v.is_finite() or abs(v) >= Decimal(10) ** 10:
            raise ValueError("amount out of range")
        try:
            return v.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
        except InvalidOperation as exc:
            raise ValueError("amount cannot be quantized") from exc


def _key(tx: ExtractedTransaction) -> tuple:
    return (tx.date, tx.amount, tx.type, tx.description, tx.suggested_category)


def parse_transactions(raw: str) -> list[ExtractedTransaction]:
    if not raw:
        return []
    text = re.sub(r"```(?:json)?", "", raw)
    decoder = json.JSONDecoder()
    data = None
    pos = text.find("[")
    while pos != -1:
        try:
            obj, _ = decoder.raw_decode(text, pos)
            if isinstance(obj, list) and all(isinstance(i, dict) for i in obj):
                data = obj
                break
        except ValueError:
            pass
        pos = text.find("[", pos + 1)
    if data is None:
        return []

    result: list[ExtractedTransaction] = []
    seen = set()
    for item in data:
        try:
            tx = ExtractedTransaction.model_validate(item)
        except (ValidationError, TypeError):
            continue
        if tx.amount <= 0:
            continue
        key = _key(tx)
        if key in seen:
            continue
        seen.add(key)
        result.append(tx)
    return result


def _split_batches(text: str) -> list[str]:
    batches: list[str] = []
    current = ""
    for line in text.splitlines(keepends=True):
        while len(line) > MAX_BATCH_CHARS:
            if current:
                batches.append(current)
                current = ""
            batches.append(line[:MAX_BATCH_CHARS])
            line = line[MAX_BATCH_CHARS:]
        if len(current) + len(line) > MAX_BATCH_CHARS:
            batches.append(current)
            current = ""
        current += line
    if current.strip():
        batches.append(current)
    return batches


async def extract_transactions(text: str) -> list[ExtractedTransaction]:
    if not text or not text.strip():
        return []
    llm = ChatGoogleGenerativeAI(
        model=settings.gemini_model,
        google_api_key=settings.gemini_api_key,
        temperature=0,
    )
    merged: list[ExtractedTransaction] = []
    seen = set()
    for batch in _split_batches(text):
        response = await llm.ainvoke([
            {"role": "system", "content": EXTRACTION_PROMPT},
            {"role": "user", "content": batch},
        ])
        for tx in parse_transactions(_extract_text(response.content)):
            key = _key(tx)
            if key not in seen:
                seen.add(key)
                merged.append(tx)
    return merged
