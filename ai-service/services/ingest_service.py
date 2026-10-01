import os
import uuid
from pathlib import Path

from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_community.document_loaders import Docx2txtLoader, PyPDFLoader
from langchain_google_genai import GoogleGenerativeAIEmbeddings
from qdrant_client import QdrantClient
from qdrant_client.models import Distance, PointStruct, VectorParams

from core.config import settings


async def process_document(file_path: str, user_id: str, document_id: str) -> int:
    """Save an uploaded PDF/DOCX document, index its text chunks into Qdrant, and return chunk count."""
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
            return 0

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

        return len(chunks)
    finally:
        if os.path.exists(file_path):
            os.remove(file_path)
