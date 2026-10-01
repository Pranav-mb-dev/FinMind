import os
import tempfile
from pathlib import Path

from celery.result import AsyncResult
from fastapi import APIRouter, File, Form, HTTPException, UploadFile, status

from app.celery_app import celery_app
from app.tasks import process_document_task

router = APIRouter()

ALLOWED_EXTENSIONS = {".pdf", ".docx"}
UPLOAD_DIR = "/tmp/uploads"


@router.post("/ingest", status_code=status.HTTP_202_ACCEPTED)
async def ingest_document(
    file: UploadFile = File(...),
    user_id: str = Form(...),
    document_id: str = Form(...),
):
    file_extension = Path(file.filename or "").suffix.lower()

    if file_extension not in ALLOWED_EXTENSIONS:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Only PDF and DOCX files are allowed.",
        )

    try:
        contents = await file.read()
        os.makedirs(UPLOAD_DIR, exist_ok=True)
        with tempfile.NamedTemporaryFile(delete=False, suffix=file_extension, dir=UPLOAD_DIR) as temp_file:
            temp_file.write(contents)
            temp_file_path = temp_file.name

        task = process_document_task.delay(temp_file_path, user_id, document_id)
        return {"task_id": task.id, "status": "processing"}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Document ingestion failed: {str(exc)}",
        ) from exc


@router.get("/ingest/status/{task_id}")
async def get_ingest_status(task_id: str):
    result = AsyncResult(task_id, app=celery_app)

    if result.state == "PENDING":
        return {"task_id": task_id, "status": "pending"}
    elif result.state == "STARTED":
        return {"task_id": task_id, "status": "processing"}
    elif result.state == "SUCCESS":
        return {
            "task_id": task_id,
            "status": "ready",
            "chunks_indexed": result.result,
        }
    elif result.state == "FAILURE":
        return {
            "task_id": task_id,
            "status": "failed",
            "error": str(result.result),
        }
    else:
        return {"task_id": task_id, "status": result.state.lower()}
