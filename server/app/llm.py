"""对兼容 OpenAI Chat Completions 协议的大模型服务的最小封装。"""

import time
from dataclasses import dataclass

import httpx


class LlmError(Exception):
    """上游大模型调用失败。"""


@dataclass(frozen=True)
class LlmReply:
    text: str
    model: str
    latency_ms: int


class LlmClient:
    def __init__(self, http: httpx.AsyncClient, base_url: str, api_key: str, model: str) -> None:
        self._http = http
        self._url = base_url.rstrip("/") + "/chat/completions"
        self._api_key = api_key
        self._model = model

    async def chat(
        self,
        system: str,
        user: str,
        max_tokens: int = 512,
        json_mode: bool = False,
    ) -> LlmReply:
        body: dict = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "max_tokens": max_tokens,
        }
        if json_mode:
            body["response_format"] = {"type": "json_object"}

        start = time.perf_counter()
        try:
            resp = await self._http.post(
                self._url, json=body, headers={"Authorization": f"Bearer {self._api_key}"}
            )
        except httpx.HTTPError as e:
            raise LlmError(f"无法连接大模型服务：{e}") from e
        if resp.status_code != 200:
            raise LlmError(f"大模型服务返回 {resp.status_code}：{resp.text[:200]}")

        data = resp.json()
        try:
            text = data["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as e:
            raise LlmError("大模型返回格式无法解析") from e
        latency = int((time.perf_counter() - start) * 1000)
        return LlmReply(text=text, model=data.get("model", self._model), latency_ms=latency)
