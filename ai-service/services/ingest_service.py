import os
import tempfile
import uuid
from pathlib import Path

from fastapi import HTTPException, UploadFile, status
from langchain.text_splitter import RecursiveCharacterTextSplitter
from langchain_community.document_loaders import Docx2txtLoader, PyPDFLoader
from langchain_google_genai import GoogleGenerativeAIEmbeddings
from qdrant_client import QdrantClient
from qdrant_client.models import Distance, PointStruct, VectorParams

from core.config import settings


async def process_document(file_path: str, user_id: str, document_id: str) -> int:
    """Save an uploaded PDF/DOCX document, index its text chunks into Qdrant, and return chunk count."""
    file_extension = Path(file_path).suffix.lower()
    if file_extension not in {".pdf", ".docx"}:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Only PDF and DOCX files are supported.",
        )

    try:
        if file_extension == ".pdf":
            loader = PyPDFLoader(file_path)
        else:
            loader = Docx2txtLoader(file_path)

        documents = loader.load()
        if not documents:
            return 0

        combined_text = "\n\n".join(doc.page_content for doc in documents if getattr(doc, "page_content", None))
        if not combined_text.strip():
            return 0

        text_splitter = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)
        chunks = text_splitter.split_text(combined_text)
        if not chunks:
            return 0

        embeddings = GoogleGenerativeAIEmbeddings(
            model="models/embedding-001",
            google_api_key=settings.gemini_api_key,
        )

        client = QdrantClient(url=settings.qdrant_url)
        collection_name = "finmind_documents"
        if not client.collection_exists(collection_name):
            client.create_collection(
                collection_name=collection_name,
                vectors_config=VectorParams(size=768, distance=Distance.COSINE),
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

        return len(chunks)
    finally:
        if os.path.exists(file_path):
            os.remove(file_path)
