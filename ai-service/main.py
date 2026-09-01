from fastapi import FastAPI
from routers import ingest

app = FastAPI(title="FinMind AI Service")

app.include_router(ingest.router, prefix="/api/v1")

@app.get("/health")
def health():
    return {"status": "ok"}