"""网关的统一错误格式、按设备限流与调用日志（FR-GW-05，04-api.md 1.1、1.2 节）。

日志只记路径、设备标识哈希、状态码与耗时，不记录请求或响应正文（NFR-SEC-02）。
"""

import hashlib
import logging
import time
from collections import deque

from fastapi import HTTPException

logger = logging.getLogger("lingxi.gateway")


class GatewayError(HTTPException):
    """带错误码的 HTTP 错误，按 04-api.md 1.2 节输出 `{"error": {"code", "message", "request_id"}}`。"""

    def __init__(self, status: int, code: str, message: str, headers: dict[str, str] | None = None) -> None:
        super().__init__(status_code=status, detail=message, headers=headers)
        self.code = code


# 状态码到默认错误码，用于框架自身抛出的 HTTPException（如 404）。
DEFAULT_CODES = {
    400: "BAD_REQUEST",
    401: "UNAUTHORIZED",
    404: "NOT_FOUND",
    413: "TEXT_TOO_LONG",
    422: "LLM_INVALID_OUTPUT",
    429: "RATE_LIMITED",
    502: "LLM_UPSTREAM",
    503: "LLM_NOT_CONFIGURED",
}


def error_body(code: str, message: str, request_id: str) -> dict:
    return {"error": {"code": code, "message": message, "request_id": request_id}}


def device_key(authorization: str, device_id: str) -> str:
    """设备标识：令牌 + 设备 id 的哈希前 12 位，日志与限流都只用它，不出现原始令牌。"""
    return hashlib.sha256(f"{authorization}|{device_id}".encode()).hexdigest()[:12]


class RateLimiter:
    """滑动窗口限流：每个设备在 [window_seconds] 内最多 [limit] 次。"""

    def __init__(self, window_seconds: float = 60.0, clock=time.monotonic) -> None:
        self._window = window_seconds
        self._clock = clock
        self._hits: dict[str, deque[float]] = {}

    def check(self, key: str, limit: int) -> int | None:
        """允许则记一次并返回 None；超限返回需要等待的秒数（向上取整，至少 1）。"""
        now = self._clock()
        hits = self._hits.setdefault(key, deque())
        while hits and now - hits[0] >= self._window:
            hits.popleft()
        if len(hits) >= limit:
            return max(1, int(self._window - (now - hits[0]) + 0.999))
        hits.append(now)
        return None

    def reset(self) -> None:
        self._hits.clear()


limiter = RateLimiter()


def log_call(path: str, device: str, status: int, latency_ms: int, request_id: str) -> None:
    logger.info(
        "path=%s device=%s status=%d latency_ms=%d request_id=%s",
        path,
        device,
        status,
        latency_ms,
        request_id,
    )
