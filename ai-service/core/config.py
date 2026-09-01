from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    gemini_api_key: str
    qdrant_url: str = "http://localhost:6333"
    CELERY_BROKER_URL: str = "redis://redis:6379/1"
    CELERY_RESULT_BACKEND: str = "redis://redis:6379/2"
    class Config:
        env_file = ".env"

settings = Settings()