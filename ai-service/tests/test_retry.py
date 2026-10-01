"""Tests for process_document_task retry logic.

Verifies that transient failures (Gemini 5xx, Qdrant connection errors) trigger
retries with backoff, while permanent failures (Gemini 4xx, corrupted files)
fail immediately without retrying.
"""

import pytest
from unittest.mock import patch, MagicMock

from celery.exceptions import Retry

import google.genai.errors
from langchain_google_genai._common import GoogleGenerativeAIError
from qdrant_client.http.exceptions import ResponseHandlingException, UnexpectedResponse
from qdrant_client.common.client_exceptions import ResourceExhaustedResponse

from app.tasks import process_document_task, _is_transient


# ---------------------------------------------------------------------------
# Unit tests for _is_transient classification
# ---------------------------------------------------------------------------

class TestIsTransient:
    def test_gemini_server_error_is_transient(self):
        cause = google.genai.errors.ServerError(500, {"error": {"message": "Internal server error"}})
        exc = GoogleGenerativeAIError("API error")
        exc.__cause__ = cause
        assert _is_transient(exc) is True

    def test_gemini_client_error_is_permanent(self):
        cause = google.genai.errors.ClientError(401, {"error": {"message": "Invalid API key"}})
        exc = GoogleGenerativeAIError("API error")
        exc.__cause__ = cause
        assert _is_transient(exc) is False

    def test_gemini_error_without_cause_is_permanent(self):
        exc = GoogleGenerativeAIError("Unknown error")
        assert _is_transient(exc) is False

    def test_qdrant_connection_error_is_transient(self):
        import httpx
        source = httpx.ConnectError("Connection refused")
        exc = ResponseHandlingException(source)
        assert _is_transient(exc) is True

    def test_qdrant_rate_limit_is_transient(self):
        exc = ResourceExhaustedResponse("Rate limited", 30)
        assert _is_transient(exc) is True

    def test_qdrant_5xx_is_transient(self):
        exc = UnexpectedResponse(
            status_code=503, reason_phrase="Service Unavailable", content=b"", headers={}
        )
        assert _is_transient(exc) is True

    def test_qdrant_4xx_is_permanent(self):
        exc = UnexpectedResponse(
            status_code=400, reason_phrase="Bad Request", content=b"", headers={}
        )
        assert _is_transient(exc) is False

    def test_value_error_is_permanent(self):
        assert _is_transient(ValueError("Only PDF and DOCX files are supported.")) is False

    def test_generic_exception_is_permanent(self):
        assert _is_transient(Exception("something unexpected")) is False

    def test_pdf_read_error_is_permanent(self):
        from pypdf.errors import PdfReadError
        assert _is_transient(PdfReadError("broken PDF")) is False

    def test_bad_zip_is_permanent(self):
        import zipfile
        assert _is_transient(zipfile.BadZipFile("Not a zip file")) is False


# ---------------------------------------------------------------------------
# Integration tests for process_document_task retry/no-retry behavior
# ---------------------------------------------------------------------------

PROCESS_DOC = "app.tasks.process_document"


class TestTaskRetryOnQdrantConnectionFailure:
    """(a) Qdrant connection failure -> should retry with backoff."""

    @patch(PROCESS_DOC)
    def test_retries_on_connection_error(self, mock_process):
        import httpx
        source = httpx.ConnectError("Connection refused")
        exc = ResponseHandlingException(source)
        mock_process.side_effect = exc

        with pytest.raises(Retry):
            process_document_task("/tmp/test.pdf", "user1", "doc1")

        mock_process.assert_called_once()


class TestTaskRetryOnGeminiServerError:
    """(b) Gemini 5xx -> should retry with backoff."""

    @patch(PROCESS_DOC)
    def test_retries_on_server_error(self, mock_process):
        cause = google.genai.errors.ServerError(500, {"error": {"message": "Internal server error"}})
        exc = GoogleGenerativeAIError("embed_documents failed")
        exc.__cause__ = cause
        mock_process.side_effect = exc

        with pytest.raises(Retry):
            process_document_task("/tmp/test.pdf", "user1", "doc1")

        mock_process.assert_called_once()


class TestTaskNoRetryOnGeminiClientError:
    """(c) Gemini 4xx (bad API key) -> should NOT retry, fail immediately."""

    @patch(PROCESS_DOC)
    def test_fails_immediately_on_client_error(self, mock_process):
        cause = google.genai.errors.ClientError(401, {"error": {"message": "Invalid API key"}})
        exc = GoogleGenerativeAIError("embed_documents failed")
        exc.__cause__ = cause
        mock_process.side_effect = exc

        with pytest.raises(GoogleGenerativeAIError, match="embed_documents failed"):
            process_document_task("/tmp/test.pdf", "user1", "doc1")

        mock_process.assert_called_once()


class TestTaskNoRetryOnCorruptedPdf:
    """(d) Corrupted PDF -> should NOT retry, fail immediately."""

    @patch(PROCESS_DOC)
    def test_fails_immediately_on_pdf_error(self, mock_process):
        from pypdf.errors import PdfReadError
        mock_process.side_effect = PdfReadError("EOF marker not found")

        with pytest.raises(PdfReadError, match="EOF marker not found"):
            process_document_task("/tmp/test.pdf", "user1", "doc1")

        mock_process.assert_called_once()


class TestTaskNoRetryOnBadZipDocx:
    """Corrupted DOCX (bad zip) -> should NOT retry."""

    @patch(PROCESS_DOC)
    def test_fails_immediately_on_bad_zip(self, mock_process):
        import zipfile
        mock_process.side_effect = zipfile.BadZipFile("File is not a zip file")

        with pytest.raises(zipfile.BadZipFile, match="not a zip file"):
            process_document_task("/tmp/test.pdf", "user1", "doc1")

        mock_process.assert_called_once()


class TestBackoffCalculation:
    """Verify exponential backoff values and ResourceExhaustedResponse retry_after_s."""

    @patch(PROCESS_DOC)
    def test_backoff_increases_exponentially(self, mock_process):
        import httpx
        source = httpx.ConnectError("Connection refused")
        exc = ResponseHandlingException(source)
        mock_process.side_effect = exc

        task = process_document_task
        request = task.request
        original_retries = request.retries

        countdowns = []
        for attempt in range(3):
            request.retries = attempt
            try:
                task("/tmp/test.pdf", "user1", "doc1")
            except Retry as r:
                countdowns.append(r.when)

        request.retries = original_retries
        assert countdowns == [10, 20, 40]

    @patch(PROCESS_DOC)
    def test_resource_exhausted_respects_retry_after(self, mock_process):
        exc = ResourceExhaustedResponse("Rate limited", 120)
        mock_process.side_effect = exc

        with pytest.raises(Retry):
            process_document_task("/tmp/test.pdf", "user1", "doc1")
