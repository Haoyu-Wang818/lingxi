"""大模型客户端：通义千问（DashScope OpenAI 兼容模式）适配器。

DashScope 的兼容模式与 OpenAI Chat Completions 协议一致，地址为
https://dashscope.aliyuncs.com/compatible-mode/v1 。API Key 只从 server/.env 读取，绝不进入手机端或版本库。
换成其他兼容服务（如 DeepSeek）只需改 .env 中的地址和模型名。
"""

import time
from dataclasses import dataclass

import httpx

# .env.example 中的占位值，视同未配置。
PLACEHOLDER_KEYS = {"", "sk-your-key", "change-me"}


class LlmError(Exception):
    """上游大模型调用失败（网络、超时、非 200、返回格式无法解析），映射为 502 LLM_UPSTREAM。"""


class LlmNotConfiguredError(LlmError):
    """网关没有配置 API Key，映射为 503 LLM_NOT_CONFIGURED。"""


class LlmInvalidOutputError(LlmError):
    """大模型返回了内容，但重试后仍无法通过校验，映射为 422 LLM_INVALID_OUTPUT。"""


@dataclass(frozen=True)
class LlmReply:
    text: str
    model: str
    latency_ms: int


def is_configured(api_key: str) -> bool:
    return api_key.strip() not in PLACEHOLDER_KEYS


def _upstream_detail(resp: httpx.Response) -> str:
    """提取 DashScope / OpenAI 风格的错误码与说明，不回显请求内容。"""
    try:
        err = resp.json().get("error", {})
        code = err.get("code") or err.get("type") or ""
        message = err.get("message", "")
        return f"{code} {message}".strip()[:200]
    except (ValueError, AttributeError):
        return resp.text[:200]


class LlmClient:
    def __init__(self, http: httpx.AsyncClient, base_url: str, api_key: str, model: str) -> None:
        self._http = http
        self._url = base_url.rstrip("/") + "/chat/completions"
        self._api_key = api_key
        self._model = model

    @property
    def model(self) -> str:
        return self._model

    async def chat(
        self,
        system: str,
        user: str,
        max_tokens: int = 512,
        json_mode: bool = False,
        temperature: float | None = None,
    ) -> LlmReply:
        if not is_configured(self._api_key):
            raise LlmNotConfiguredError("网关未配置大模型 API Key，云端功能未启用")
        messages = [{"role": "user", "content": user}]
        if system:
            messages.insert(0, {"role": "system", "content": system})
        body: dict = {"model": self._model, "messages": messages, "max_tokens": max_tokens}
        if json_mode:
            # 通义千问兼容模式支持 JSON 输出模式（04-api.md 第 3 节）。
            body["response_format"] = {"type": "json_object"}
        if temperature is not None:
            body["temperature"] = temperature

        start = time.perf_counter()
        try:
            resp = await self._http.post(
                self._url, json=body, headers={"Authorization": f"Bearer {self._api_key}"}
            )
        except httpx.TimeoutException as e:
            raise LlmError("大模型服务超时") from e
        except httpx.HTTPError as e:
            raise LlmError(f"无法连接大模型服务：{type(e).__name__}") from e
        if resp.status_code != 200:
            raise LlmError(f"大模型服务返回 {resp.status_code}：{_upstream_detail(resp)}")

        try:
            data = resp.json()
            text = data["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as e:
            raise LlmError("大模型返回格式无法解析") from e
        if not isinstance(text, str):
            raise LlmError("大模型返回格式无法解析")
        latency = int((time.perf_counter() - start) * 1000)
        return LlmReply(text=text, model=data.get("model", self._model), latency_ms=latency)
