from celery import Celery

from core.config import settings

celery_app = Celery(
    "finmind",
    broker=settings.celery_broker_url,
    backend=settings.celery_result_backend,
    include=["app.tasks"],
)

celery_app.conf.update(
    task_track_started=True,
    result_expires=86400,
)
