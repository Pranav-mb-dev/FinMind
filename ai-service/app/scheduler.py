from apscheduler.schedulers.background import BackgroundScheduler

from app.tasks import reindex_all_documents

scheduler = BackgroundScheduler()


def start_scheduler():
    scheduler.add_job(
        reindex_all_documents.delay,
        trigger="cron",
        hour=2,
        minute=0,
        id="nightly_reindex",
        replace_existing=True,
    )
    scheduler.start()


def shutdown_scheduler():
    scheduler.shutdown(wait=False)
