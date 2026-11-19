"""网关 HTTP 接口。

手机端不直接持有大模型 API Key，所有云端调用都经过本网关，便于统一鉴权、限流与日志。
启动：uv run uvicorn app.main:app --reload
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Path, Query, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field

from .config import Settings, get_settings
from .extraction import Extraction, extract_knowledge
from .llm import LlmClient, LlmError
from .relay import MAX_MESSAGE_CHARS, Role, hub


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    async with httpx.AsyncClient(timeout=settings.llm_timeout_seconds) as http:
        app.state.http = http
        yield


app = FastAPI(title="灵犀学伴网关", version="0.1.0", lifespan=lifespan)


SettingsDep = Annotated[Settings, Depends(get_settings)]


def get_llm(settings: SettingsDep) -> LlmClient:
    return LlmClient(app.state.http, settings.llm_base_url, settings.llm_api_key, settings.llm_model)


def require_token(
    settings: SettingsDep,
    authorization: Annotated[str, Header()] = "",
) -> None:
    if settings.gateway_token and authorization != f"Bearer {settings.gateway_token}":
        raise HTTPException(status_code=401, detail="令牌无效")


LlmDep = Annotated[LlmClient, Depends(get_llm)]


class ChatRequest(BaseModel):
    system: str = ""
    user: str = Field(min_length=1, max_length=20_000)
    max_tokens: int = Field(default=512, ge=1, le=4096)


class ChatResponse(BaseModel):
    text: str
    model: str
    latency_ms: int


class ExtractRequest(BaseModel):
    text: str = Field(min_length=1, max_length=30_000)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/chat", response_model=ChatResponse, dependencies=[Depends(require_token)])
async def chat(req: ChatRequest, llm: LlmDep) -> ChatResponse:
    try:
        reply = await llm.chat(req.system, req.user, req.max_tokens)
    except LlmError as e:
        raise HTTPException(status_code=502, detail=str(e)) from e
    return ChatResponse(text=reply.text, model=reply.model, latency_ms=reply.latency_ms)


@app.post("/v1/extract", response_model=Extraction, dependencies=[Depends(require_token)])
async def extract(req: ExtractRequest, llm: LlmDep) -> Extraction:
    try:
        return await extract_knowledge(llm, req.text)
    except LlmError as e:
        raise HTTPException(status_code=502, detail=str(e)) from e


@app.websocket("/v1/relay/{pair_code}/{role}")
async def relay(
    ws: WebSocket,
    role: Role,
    pair_code: Annotated[str, Path(pattern=r"^[A-Za-z0-9_-]{4,32}$")],
    token: Annotated[str, Query()] = "",
) -> None:
    """手机与手表的消息中继，见 relay.py。WebSocket 无法方便地带请求头，令牌放在查询参数里。"""
    settings = get_settings()
    if settings.gateway_token and token != settings.gateway_token:
        await ws.close(code=4401, reason="invalid token")
        return
    await ws.accept()
    await hub.join(pair_code, role, ws)
    try:
        while True:
            text = await ws.receive_text()
            if len(text) > MAX_MESSAGE_CHARS:
                await ws.send_json({"type": "relay_error", "detail": "message too large"})
                continue
            if not await hub.forward(pair_code, role, text):
                await ws.send_json({"type": "relay_error", "detail": "peer offline"})
    except WebSocketDisconnect:
        pass
    finally:
        await hub.leave(pair_code, role, ws)
