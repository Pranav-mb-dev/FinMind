import asyncio

from app.celery_app import celery_app
from services.ingest_service import process_document


@celery_app.task
def process_document_task(file, user_id, document_id):
    return asyncio.run(process_document(file, user_id, document_id))
