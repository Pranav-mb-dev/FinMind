import json
import re

from langchain_google_genai import ChatGoogleGenerativeAI, GoogleGenerativeAIEmbeddings
from qdrant_client import QdrantClient
from qdrant_client.models import Filter, FieldCondition, MatchText, MatchValue

from core.config import settings
from services.rag.naive_rag import RAGResult, SYSTEM_PROMPT, _build_prompt, _extract_text


REWRITE_PROMPT = (
    "Rewrite the following user question into a concise search query optimized "
    "for retrieving relevant financial documents. Output ONLY the rewritten query, "
    "nothing else.\n\nUser question: {question}"
)

RERANK_PROMPT = (
    "You are a relevance judge. Given a user's financial question and a list of "
    "text chunks, score each chunk's relevance from 1 (irrelevant) to 10 (highly "
    "relevant). Return a JSON array of objects with keys \"index\" (0-based) and "
    "\"score\". Output ONLY valid JSON, no explanation.\n\n"
    "Question: {question}\n\n"
    "Chunks:\n{chunks}"
)


async def _rewrite_query(llm: ChatGoogleGenerativeAI, question: str) -> str:
    response = await llm.ainvoke([
        {"role": "user", "content": REWRITE_PROMPT.format(question=question)},
    ])
    rewritten = _extract_text(response.content).strip()
    return rewritten if rewritten else question


def _extract_keywords(query: str) -> list[str]:
    stop_words = {
        "i", "me", "my", "the", "a", "an", "is", "was", "are", "were", "do",
        "did", "does", "have", "has", "had", "what", "where", "when", "how",
        "which", "who", "why", "in", "on", "at", "to", "for", "of", "with",
        "and", "or", "not", "this", "that", "it", "be", "been", "am", "much",
        "too", "most", "very", "can", "could", "would", "should",
    }
    words = re.findall(r"[a-zA-Z]+", query.lower())
    return [w for w in words if w not in stop_words and len(w) > 2]


async def _vector_search(
    client: QdrantClient, query_vector: list[float], user_id: str, top_k: int = 10
) -> list[dict]:
    response = client.query_points(
        collection_name="finmind_documents",
        query=query_vector,
        limit=top_k,
        query_filter=Filter(
            must=[FieldCondition(key="user_id", match=MatchValue(value=user_id))]
        ),
    )
    return [
        {"id": str(hit.id), "text": hit.payload.get("chunk_text", ""), "score": hit.score}
        for hit in response.points
        if hit.payload
    ]


async def _keyword_search(
    client: QdrantClient, keywords: list[str], user_id: str, top_k: int = 10
) -> list[dict]:
    all_results = {}
    for keyword in keywords[:5]:
        try:
            results, _offset = client.scroll(
                collection_name="finmind_documents",
                scroll_filter=Filter(
                    must=[
                        FieldCondition(key="user_id", match=MatchValue(value=user_id)),
                        FieldCondition(key="chunk_text", match=MatchText(text=keyword)),
                    ]
                ),
                limit=top_k,
                with_payload=True,
            )
            for point in results:
                pid = str(point.id)
                if pid not in all_results:
                    all_results[pid] = {
                        "id": pid,
                        "text": point.payload.get("chunk_text", ""),
                        "score": 0.5,
                    }
        except Exception:
            continue
    return list(all_results.values())


async def _rerank_chunks(
    llm: ChatGoogleGenerativeAI, question: str, chunks: list[dict]
) -> list[dict]:
    if len(chunks) <= 5:
        return chunks

    numbered = "\n".join(
        f"[{i}] {c['text'][:300]}" for i, c in enumerate(chunks)
    )
    response = await llm.ainvoke([
        {"role": "user", "content": RERANK_PROMPT.format(question=question, chunks=numbered)},
    ])

    try:
        raw = _extract_text(response.content).strip()
        raw = raw.removeprefix("```json").removesuffix("```").strip()
        scores = json.loads(raw)
        score_map = {item["index"]: item["score"] for item in scores}
        for i, chunk in enumerate(chunks):
            chunk["rerank_score"] = score_map.get(i, 0)
        chunks.sort(key=lambda c: c.get("rerank_score", 0), reverse=True)
    except (json.JSONDecodeError, KeyError, TypeError):
        pass

    return chunks[:5]


async def retrieve_and_rerank(user_id: str, message: str) -> list[str]:
    """Run the full advanced retrieval pipeline, returning top-5 chunk texts."""
    llm = ChatGoogleGenerativeAI(
        model=settings.gemini_model,
        google_api_key=settings.gemini_api_key,
        temperature=0.0,
    )

    rewritten_query = await _rewrite_query(llm, message)
    keywords = _extract_keywords(message)

    embeddings = GoogleGenerativeAIEmbeddings(
        model=settings.embedding_model,
        google_api_key=settings.gemini_api_key,
        output_dimensionality=settings.embedding_dimensions,
    )
    query_vector = embeddings.embed_query(rewritten_query)

    client = QdrantClient(url=settings.qdrant_url)

    vector_results = await _vector_search(client, query_vector, user_id)
    keyword_results = await _keyword_search(client, keywords, user_id) if keywords else []

    merged = {r["id"]: r for r in vector_results}
    for r in keyword_results:
        if r["id"] not in merged:
            merged[r["id"]] = r
    all_chunks = list(merged.values())

    if not all_chunks:
        return []

    reranked = await _rerank_chunks(llm, message, all_chunks)
    return [c["text"] for c in reranked]


async def run(user_id: str, message: str) -> RAGResult:
    chunks = await retrieve_and_rerank(user_id, message)

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
