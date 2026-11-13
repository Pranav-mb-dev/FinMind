import logging
import os
import uuid
from pathlib import Path

from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_community.document_loaders import Docx2txtLoader, PyPDFLoader
from langchain_google_genai import GoogleGenerativeAIEmbeddings
from qdrant_client import QdrantClient
from qdrant_client.models import Distance, PointStruct, VectorParams

from core.config import settings
from services.extraction_service import extract_transactions

logger = logging.getLogger(__name__)

EXTRACTION_FAILED_MESSAGE = "Transaction extraction failed; document text was still indexed."


async def process_document(file_path: str, user_id: str, document_id: str) -> dict:
    """Save an uploaded PDF/DOCX document, index its text chunks into Qdrant, and extract transactions. Returns a dict."""
    try:
        file_extension = Path(file_path).suffix.lower()
        if file_extension not in {".pdf", ".docx"}:
            raise ValueError("Only PDF and DOCX files are supported.")

        if not os.path.exists(file_path) or os.path.getsize(file_path) == 0:
            raise ValueError("Uploaded file is empty or missing.")

        if file_extension == ".pdf":
            loader = PyPDFLoader(file_path)
        else:
            loader = Docx2txtLoader(file_path)

        documents = loader.load()
        if not documents:
            raise ValueError("Document contains no readable text.")

        pages = [doc.page_content for doc in documents if getattr(doc, "page_content", None)]
        parts = []
        for i, page in enumerate(pages):
            parts.append(page)
            if i < len(pages) - 1:
                if page.rstrip()[-1:] in '.!?':
                    parts.append('\n\n')
                else:
                    parts.append(' ')
        combined_text = ''.join(parts)
        if not combined_text.strip():
            raise ValueError("Document contains no readable text.")

        text_splitter = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)
        chunks = text_splitter.split_text(combined_text)
        if not chunks:
            return {"chunks_indexed": 0, "transactions": [], "extraction_error": None}

        embeddings = GoogleGenerativeAIEmbeddings(
            model=settings.embedding_model,
            google_api_key=settings.gemini_api_key,
            output_dimensionality=settings.embedding_dimensions,
        )

        client = QdrantClient(url=settings.qdrant_url)
        collection_name = "finmind_documents"
        if not client.collection_exists(collection_name):
            client.create_collection(
                collection_name=collection_name,
                vectors_config=VectorParams(size=settings.embedding_dimensions, distance=Distance.COSINE),
            )

        vectors = embeddings.embed_documents(chunks)
        points = []
        for index, (chunk, vector) in enumerate(zip(chunks, vectors)):
            point_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{user_id}:{document_id}:{index}"))
            points.append(
                PointStruct(
                    id=point_id,
                    vector=vector,
                    payload={
                        "user_id": user_id,
                        "document_id": document_id,
                        "chunk_text": chunk,
                    },
                )
            )

        client.upsert(collection_name=collection_name, points=points)

        transactions = []
        extraction_error = None
        try:
            rows = await extract_transactions(combined_text)
            transactions = [
                {
                    "date": r.date.isoformat(),
                    "amount": float(r.amount),
                    "type": r.type,
                    "description": r.description,
                    "suggested_category": r.suggested_category,
                }
                for r in rows
            ]
        except Exception:
            # Raw Gemini/langchain error text may contain internal details; log it, return a safe message.
            logger.exception("Transaction extraction failed for document %s", document_id)
            extraction_error = EXTRACTION_FAILED_MESSAGE

        return {
            "chunks_indexed": len(chunks),
            "transactions": transactions,
            "extraction_error": extraction_error,
        }
    finally:
        if os.path.exists(file_path):
            os.remove(file_path)
