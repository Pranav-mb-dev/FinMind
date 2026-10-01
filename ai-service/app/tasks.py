import asyncio

import google.genai.errors
from langchain_google_genai._common import GoogleGenerativeAIError
from qdrant_client.http.exceptions import ResponseHandlingException, UnexpectedResponse
from qdrant_client.common.client_exceptions import ResourceExhaustedResponse

from app.celery_app import celery_app
from services.ingest_service import process_document


def _is_transient(exc: Exception) -> bool:
    if isinstance(exc, GoogleGenerativeAIError):
        return isinstance(exc.__cause__, google.genai.errors.ServerError)
    if isinstance(exc, ResponseHandlingException):
        return True
    if isinstance(exc, ResourceExhaustedResponse):
        return True
    if isinstance(exc, UnexpectedResponse):
        return exc.status_code >= 500
    return False


@celery_app.task(bind=True, max_retries=3)
def process_document_task(self, file_path, user_id, document_id):
    try:
        return asyncio.run(process_document(file_path, user_id, document_id))
    except Exception as exc:
        if _is_transient(exc):
            backoff = 2 ** self.request.retries * 10
            if isinstance(exc, ResourceExhaustedResponse) and exc.retry_after_s:
                backoff = max(backoff, exc.retry_after_s)
            raise self.retry(exc=exc, countdown=backoff)
        raise


@celery_app.task
def reindex_all_documents():
    from services.reindex_service import reindex_all
    return asyncio.run(reindex_all())
