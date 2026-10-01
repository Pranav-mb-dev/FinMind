from langchain_google_genai import GoogleGenerativeAIEmbeddings
from qdrant_client import QdrantClient
from qdrant_client.models import Distance, VectorParams

from core.config import settings


async def reindex_all():
    """Re-embed and re-upsert all existing document chunks in Qdrant.

    Scrolls through every point in the collection, re-embeds the chunk text
    with the current embedding model, and upserts the updated vectors.
    """
    client = QdrantClient(url=settings.qdrant_url)
    collection_name = "finmind_documents"

    if not client.collection_exists(collection_name):
        return 0

    embeddings = GoogleGenerativeAIEmbeddings(
        model=settings.embedding_model,
        google_api_key=settings.gemini_api_key,
        output_dimensionality=settings.embedding_dimensions,
    )

    total_updated = 0
    offset = None

    while True:
        scroll_result = client.scroll(
            collection_name=collection_name,
            limit=100,
            offset=offset,
            with_vectors=False,
            with_payload=True,
        )
        points, next_offset = scroll_result

        if not points:
            break

        texts = [p.payload.get("chunk_text", "") for p in points]
        vectors = embeddings.embed_documents(texts)

        from qdrant_client.models import PointStruct

        updated_points = [
            PointStruct(id=p.id, vector=v, payload=p.payload)
            for p, v in zip(points, vectors)
        ]
        client.upsert(collection_name=collection_name, points=updated_points)
        total_updated += len(updated_points)

        if next_offset is None:
            break
        offset = next_offset

    return total_updated
