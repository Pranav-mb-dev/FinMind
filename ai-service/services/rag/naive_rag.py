from dataclasses import dataclass

from langchain_google_genai import ChatGoogleGenerativeAI, GoogleGenerativeAIEmbeddings
from qdrant_client import QdrantClient
from qdrant_client.models import Filter, FieldCondition, MatchValue

from core.config import settings


SYSTEM_PROMPT = (
    "You are FinMind, a personal finance AI assistant. "
    "Answer the user's question based ONLY on the financial data provided below. "
    "If the provided context does not contain enough information to answer, say so clearly. "
    "Be concise, specific, and cite numbers from the data when possible."
)


@dataclass
class RAGResult:
    response: str
    sources: list[str]


def _extract_text(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(
            part.get("text", "") if isinstance(part, dict) else str(part)
            for part in content
        )
    return str(content)


def _build_prompt(context_chunks: list[str], user_message: str) -> str:
    context = "\n\n---\n\n".join(context_chunks)
    return (
        f"### Financial Data Context\n{context}\n\n"
        f"### User Question\n{user_message}"
    )


async def retrieve_chunks(user_id: str, query: str, top_k: int = 5) -> list[str]:
    embeddings = GoogleGenerativeAIEmbeddings(
        model=settings.embedding_model,
        google_api_key=settings.gemini_api_key,
        output_dimensionality=settings.embedding_dimensions,
    )
    query_vector = embeddings.embed_query(query)

    client = QdrantClient(url=settings.qdrant_url)
    response = client.query_points(
        collection_name="finmind_documents",
        query=query_vector,
        limit=top_k,
        query_filter=Filter(
            must=[FieldCondition(key="user_id", match=MatchValue(value=user_id))]
        ),
    )
    return [hit.payload.get("chunk_text", "") for hit in response.points if hit.payload]


async def run(user_id: str, message: str) -> RAGResult:
    chunks = await retrieve_chunks(user_id, message, top_k=5)

    if not chunks:
        return RAGResult(
            response="I don't have any financial documents on file for you yet. Please upload a bank statement or financial document first.",
            sources=[],
        )

    llm = ChatGoogleGenerativeAI(
        model=settings.gemini_model,
        google_api_key=settings.gemini_api_key,
        temperature=0.2,
    )
    prompt = _build_prompt(chunks, message)
    response = await llm.ainvoke([
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": prompt},
    ])

    return RAGResult(response=_extract_text(response.content), sources=chunks)
