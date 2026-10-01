from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    gemini_api_key: str
    qdrant_url: str = "http://localhost:6333"
    celery_broker_url: str = "redis://redis:6379/1"
    celery_result_backend: str = "redis://redis:6379/2"
    spring_boot_url: str = "http://localhost:8080"
    gemini_model: str = "gemini-3.8-flash"
    embedding_model: str = "models/gemini-embedding-001"
    embedding_dimensions: int = 768
    cors_origins: str = "http://localhost:3000"

    class Config:
        env_file = ".env"


settings = Settings()
