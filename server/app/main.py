"""网关 HTTP 接口。

手机端不直接持有大模型 API Key，所有云端调用都经过本网关，便于统一鉴权、限流与日志。
启动：uv run uvicorn app.main:app --reload
"""

import time
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated

import httpx
from fastapi import Depends, FastAPI, Header, Path, Query, Request, Response, WebSocket, WebSocketDisconnect
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from .config import Settings, get_settings
from .extraction import PROMPT_VERSION, Extraction, extract_knowledge
from .guard import DEFAULT_CODES, GatewayError, device_key, error_body, limiter, log_call
from .llm import LlmClient, LlmError, LlmInvalidOutputError, LlmNotConfiguredError, is_configured
from .relay import MAX_MESSAGE_CHARS, Role, hub

VERSION = "0.2.0"
MAX_TEXT_CHARS = 30_000


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    async with httpx.AsyncClient(timeout=settings.llm_timeout_seconds) as http:
        app.state.http = http
        yield


app = FastAPI(title="灵犀学伴网关", version=VERSION, lifespan=lifespan)


@app.middleware("http")
async def request_context(request: Request, call_next) -> Response:
    """请求 id 原样返回（没有则生成），并记一条不含正文的调用日志（FR-GW-05）。"""
    request_id = request.headers.get("x-request-id") or uuid.uuid4().hex
    request.state.request_id = request_id
    start = time.perf_counter()
    response = await call_next(request)
    response.headers["X-Request-Id"] = request_id
    device = device_key(request.headers.get("authorization", ""), request.headers.get("x-device-id", ""))
    latency_ms = int((time.perf_counter() - start) * 1000)
    log_call(request.url.path, device, response.status_code, latency_ms, request_id)
    return response


def _request_id(request: Request) -> str:
    return getattr(request.state, "request_id", "")


@app.exception_handler(StarletteHTTPException)
async def http_error(request: Request, exc: StarletteHTTPException) -> JSONResponse:
    code = getattr(exc, "code", None) or DEFAULT_CODES.get(exc.status_code, "ERROR")
    return JSONResponse(
        error_body(code, str(exc.detail), _request_id(request)),
        status_code=exc.status_code,
        headers=getattr(exc, "headers", None),
    )


@app.exception_handler(RequestValidationError)
async def validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
    fields = ", ".join(".".join(str(p) for p in e["loc"][1:]) for e in exc.errors())
    body = error_body("BAD_REQUEST", f"参数非法：{fields}", _request_id(request))
    return JSONResponse(body, status_code=400)


SettingsDep = Annotated[Settings, Depends(get_settings)]


def get_llm(settings: SettingsDep) -> LlmClient:
    return LlmClient(app.state.http, settings.llm_base_url, settings.llm_api_key, settings.llm_model)


def require_token(
    settings: SettingsDep,
    authorization: Annotated[str, Header()] = "",
) -> None:
    if settings.gateway_token and authorization != f"Bearer {settings.gateway_token}":
        raise GatewayError(401, "UNAUTHORIZED", "令牌无效")


def rate_limit(
    settings: SettingsDep,
    authorization: Annotated[str, Header()] = "",
    x_device_id: Annotated[str, Header()] = "",
) -> None:
    """每设备每分钟 AI 请求上限（FR-GW-05）。设备由令牌与 X-Device-Id 共同标识。"""
    wait = limiter.check(device_key(authorization, x_device_id), settings.rate_limit_per_minute)
    if wait is not None:
        headers = {"Retry-After": str(wait)}
        raise GatewayError(429, "RATE_LIMITED", "请求过于频繁，请稍后再试", headers=headers)


LlmDep = Annotated[LlmClient, Depends(get_llm)]
AI_GUARDS = [Depends(require_token), Depends(rate_limit)]


def map_llm_error(e: LlmError) -> GatewayError:
    if isinstance(e, LlmNotConfiguredError):
        return GatewayError(503, "LLM_NOT_CONFIGURED", str(e))
    if isinstance(e, LlmInvalidOutputError):
        return GatewayError(422, "LLM_INVALID_OUTPUT", str(e))
    return GatewayError(502, "LLM_UPSTREAM", str(e))


class ChatRequest(BaseModel):
    system: str = ""
    user: str = Field(min_length=1, max_length=20_000)
    max_tokens: int = Field(default=512, ge=1, le=4096)


class ChatResponse(BaseModel):
    text: str
    model: str
    latency_ms: int


class Segment(BaseModel):
    source_id: str = Field(min_length=1, max_length=64)
    kind: str = "OCR"
    text: str = Field(min_length=1)
    page: int | None = None
    start_ms: int | None = None
    end_ms: int | None = None


class ExtractRequest(BaseModel):
    """兼容两种请求：旧版只有 `text`；04-api.md 1.4 节的新版带来源 id 的 `segments`。"""

    text: str = ""
    segments: list[Segment] = []
    course_name: str = ""
    lesson_id: str = ""
    existing_titles: list[str] = []

    def note_text(self) -> str:
        parts = [f"[{s.source_id}] {s.text}" for s in self.segments]
        if self.text:
            parts.insert(0, self.text)
        header = f"课程：{self.course_name}\n" if self.course_name else ""
        if self.existing_titles:
            header += "已有知识点（同义时请复用这些名称）：" + "、".join(self.existing_titles[:200]) + "\n"
        return header + "\n".join(parts)


@app.get("/health")
async def health(settings: SettingsDep) -> dict:
    return {
        "status": "ok",
        "version": VERSION,
        "llm_configured": is_configured(settings.llm_api_key),
        "model": settings.llm_model,
    }


@app.post("/v1/chat", response_model=ChatResponse, dependencies=AI_GUARDS)
async def chat(req: ChatRequest, llm: LlmDep) -> ChatResponse:
    try:
        reply = await llm.chat(req.system, req.user, req.max_tokens, temperature=0.3)
    except LlmError as e:
        raise map_llm_error(e) from e
    return ChatResponse(text=reply.text, model=reply.model, latency_ms=reply.latency_ms)


@app.post("/v1/extract", response_model=Extraction, dependencies=AI_GUARDS)
async def extract(req: ExtractRequest, llm: LlmDep, response: Response) -> Extraction:
    text = req.note_text()
    if not (req.text or req.segments):
        raise GatewayError(400, "BAD_REQUEST", "text 与 segments 不能同时为空")
    if len(text) > MAX_TEXT_CHARS:
        raise GatewayError(413, "TEXT_TOO_LONG", f"文本超过 {MAX_TEXT_CHARS} 字，请分段后重试")
    try:
        result = await extract_knowledge(llm, text)
    except LlmError as e:
        raise map_llm_error(e) from e
    response.headers["X-Prompt-Version"] = PROMPT_VERSION
    return result


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
