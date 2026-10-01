import logging
import uuid

from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field

from services.rag import naive_rag, advanced_rag, agent_rag

logger = logging.getLogger(__name__)
router = APIRouter()

VALID_MODES = {"naive", "advanced", "agent"}


class ChatRequest(BaseModel):
    user_id: str
    session_id: str | None = None
    message: str
    mode: str = Field(default="naive")


class ChatResponse(BaseModel):
    response: str
    session_id: str
    mode: str
    sources: list[str]


@router.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):
    if request.mode not in VALID_MODES:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Invalid mode '{request.mode}'. Must be one of: {', '.join(sorted(VALID_MODES))}",
        )

    session_id = request.session_id or str(uuid.uuid4())

    try:
        if request.mode == "naive":
            result = await naive_rag.run(request.user_id, request.message)
        elif request.mode == "advanced":
            result = await advanced_rag.run(request.user_id, request.message)
        else:
            result = await agent_rag.run(request.user_id, request.message)
    except Exception as exc:
        logger.exception("RAG pipeline error (mode=%s)", request.mode)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="The AI service encountered an error. Please try again later.",
        ) from exc

    return ChatResponse(
        response=result.response,
        session_id=session_id,
        mode=request.mode,
        sources=result.sources,
    )
