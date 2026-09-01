import tempfile

from fastapi import APIRouter, File, Form, HTTPException, UploadFile, status

from app.tasks import process_document_task

router = APIRouter()

ALLOWED_EXTENSIONS = {".pdf", ".docx"}


@router.post("/ingest")
async def ingest_document(
    file: UploadFile = File(...),
    user_id: str = Form(...),
    document_id: str = Form(...),
):
    file_extension = "." + (file.filename or "").split(".")[-1].lower()

    if file_extension not in ALLOWED_EXTENSIONS:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Only PDF and DOCX files are allowed.",
        )

    try:
        contents = await file.read()
        with tempfile.NamedTemporaryFile(delete=False, suffix=file_extension) as temp_file:
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
