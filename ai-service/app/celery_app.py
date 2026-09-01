from celery import Celery

from core.config import settings

celery_app = Celery(
    "finmind",
    broker=settings.celery_broker_url,
    backend=settings.celery_result_backend,
    include=["app.tasks"],
)
