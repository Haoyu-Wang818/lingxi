"""通义千问（DashScope 兼容模式）适配器与网关防护的测试。全部使用 httpx.MockTransport，不调用真实接口。"""

import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.guard import RateLimiter, device_key, limiter
from app.llm import LlmClient, LlmError, LlmNotConfiguredError, is_configured
from app.main import app, get_llm

DASHSCOPE = "https://dashscope.aliyuncs.com/compatible-mode/v1"


def qwen(handler, api_key: str = "sk-test") -> LlmClient:
    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return LlmClient(http, DASHSCOPE, api_key, "qwen-plus")


def ok(content: str, model: str = "qwen-plus"):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"model": model, "choices": [{"message": {"content": content}}]})

    return handler


async def test_request_follows_dashscope_compatible_protocol():
    """[FR-AI-02] 请求发往兼容模式的 /chat/completions，带 Bearer key、模型名与 JSON 输出模式。"""
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["auth"] = request.headers["Authorization"]
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"model": "qwen-plus", "choices": [{"message": {"content": "{}"}}]})

    reply = await qwen(handler).chat("系统", "用户", max_tokens=99, json_mode=True, temperature=0.2)
    assert seen["url"] == DASHSCOPE + "/chat/completions"
    assert seen["auth"] == "Bearer sk-test"
    body = seen["body"]
    assert body["model"] == "qwen-plus"
    assert body["messages"] == [{"role": "system", "content": "系统"}, {"role": "user", "content": "用户"}]
    assert body["max_tokens"] == 99
    assert body["response_format"] == {"type": "json_object"}
    assert body["temperature"] == 0.2
    assert reply.text == "{}"


async def test_empty_system_prompt_is_omitted():
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"choices": [{"message": {"content": "好"}}]})

    reply = await qwen(handler).chat("", "你好")
    assert seen["body"]["messages"] == [{"role": "user", "content": "你好"}]
    assert "response_format" not in seen["body"]
    assert reply.model == "qwen-plus"


@pytest.mark.parametrize("key", ["", "sk-your-key", "  "])
async def test_missing_key_raises_not_configured_without_calling_upstream(key):
    """没有 key 时不发请求，直接报未配置。"""

    def handler(request: httpx.Request) -> httpx.Response:  # pragma: no cover - 不应被调用
        raise AssertionError("不应调用上游")

    with pytest.raises(LlmNotConfiguredError):
        await qwen(handler, api_key=key).chat("", "x")
    assert not is_configured(key)


async def test_dashscope_error_body_is_summarised():
    """DashScope 的 401 错误体提取错误码，不回显请求内容。"""

    def handler(request: httpx.Request) -> httpx.Response:
        error = {"code": "invalid_api_key", "message": "Incorrect API key"}
        return httpx.Response(401, json={"error": error})

    with pytest.raises(LlmError, match="401：invalid_api_key Incorrect API key"):
        await qwen(handler).chat("", "x")


async def test_timeout_and_connection_errors_map_to_llm_error():
    def timeout(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("slow", request=request)

    with pytest.raises(LlmError, match="超时"):
        await qwen(timeout).chat("", "x")

    def refused(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    with pytest.raises(LlmError, match="ConnectError"):
        await qwen(refused).chat("", "x")


@pytest.mark.parametrize(
    "payload",
    [{"choices": []}, {"choices": [{"message": {}}]}, {"choices": [{"message": {"content": None}}]}],
)
async def test_malformed_reply_maps_to_llm_error(payload):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=payload)

    with pytest.raises(LlmError, match="无法解析"):
        await qwen(handler).chat("", "x")


async def test_non_json_reply_maps_to_llm_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="<html>bad gateway</html>")

    with pytest.raises(LlmError):
        await qwen(handler).chat("", "x")


# ---- 网关接口：错误格式、限流、未配置 ----


@pytest.fixture
def client():
    app.dependency_overrides[get_settings] = lambda: Settings(
        gateway_token="secret", rate_limit_per_minute=3, llm_api_key="", _env_file=None
    )
    limiter.reset()
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()


AUTH = {"Authorization": "Bearer secret"}


def test_not_configured_returns_503_with_error_body(client):
    """[FR-GW-05] 没有 key 时 /v1/chat 返回 503 LLM_NOT_CONFIGURED，错误体带请求 id。"""
    app.dependency_overrides[get_llm] = lambda: qwen(ok("x"), api_key="")
    resp = client.post("/v1/chat", json={"user": "你好"}, headers={**AUTH, "X-Request-Id": "req-42"})
    assert resp.status_code == 503
    assert resp.json() == {
        "error": {
            "code": "LLM_NOT_CONFIGURED",
            "message": "网关未配置大模型 API Key，云端功能未启用",
            "request_id": "req-42",
        }
    }
    assert resp.headers["X-Request-Id"] == "req-42"


def test_unauthorized_uses_error_format(client):
    resp = client.post("/v1/chat", json={"user": "x"})
    assert resp.status_code == 401
    assert resp.json()["error"]["code"] == "UNAUTHORIZED"
    assert resp.headers["X-Request-Id"]


def test_validation_error_maps_to_400(client):
    resp = client.post("/v1/chat", json={"user": ""}, headers=AUTH)
    assert resp.status_code == 400
    assert resp.json()["error"]["code"] == "BAD_REQUEST"


def test_rate_limit_per_device_returns_429(client):
    """[FR-GW-05] 同一设备超过每分钟上限返回 429 与 Retry-After；其他设备不受影响。"""
    app.dependency_overrides[get_llm] = lambda: qwen(ok("好"))
    phone = {**AUTH, "X-Device-Id": "phone-1"}
    for _ in range(3):
        assert client.post("/v1/chat", json={"user": "x"}, headers=phone).status_code == 200
    resp = client.post("/v1/chat", json={"user": "x"}, headers=phone)
    assert resp.status_code == 429
    assert resp.json()["error"]["code"] == "RATE_LIMITED"
    assert int(resp.headers["Retry-After"]) >= 1
    other = {**AUTH, "X-Device-Id": "phone-2"}
    assert client.post("/v1/chat", json={"user": "x"}, headers=other).status_code == 200


def test_extract_accepts_segments_and_retries_invalid_output(client):
    """[FR-GW-01] segments 格式请求；第一次输出不合规时重试一次。"""
    valid = json.dumps({"points": [{"id": "taylor", "title": "泰勒公式"}], "edges": []})
    replies = iter(["不是 JSON", valid])
    prompts: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        prompts.append(json.loads(request.content)["messages"][-1]["content"])
        return httpx.Response(200, json={"choices": [{"message": {"content": next(replies)}}]})

    app.dependency_overrides[get_llm] = lambda: qwen(handler)
    body = {
        "course_name": "高等数学",
        "segments": [{"source_id": "cap-1", "text": "泰勒公式 f(x)=…"}],
        "existing_titles": ["导数"],
    }
    resp = client.post("/v1/extract", json=body, headers=AUTH)
    assert resp.status_code == 200
    assert resp.json()["points"][0]["id"] == "taylor"
    assert len(prompts) == 2
    assert "[cap-1] 泰勒公式" in prompts[0] and "课程：高等数学" in prompts[0] and "导数" in prompts[0]


def test_extract_invalid_twice_returns_422(client):
    app.dependency_overrides[get_llm] = lambda: qwen(ok("[]"))
    resp = client.post("/v1/extract", json={"text": "笔记"}, headers=AUTH)
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "LLM_INVALID_OUTPUT"


def test_extract_rejects_empty_and_too_long(client):
    app.dependency_overrides[get_llm] = lambda: qwen(ok("{}"))
    assert client.post("/v1/extract", json={}, headers=AUTH).status_code == 400
    resp = client.post("/v1/extract", json={"text": "字" * 30_001}, headers=AUTH)
    assert resp.status_code == 413
    assert resp.json()["error"]["code"] == "TEXT_TOO_LONG"


def test_call_log_has_no_content(client, caplog):
    """[FR-GW-05] 调用日志只有路径、设备哈希、状态码与耗时，不含用户文本与令牌（NFR-SEC-02）。"""
    app.dependency_overrides[get_llm] = lambda: qwen(ok("导数是瞬时变化率"))
    with caplog.at_level("INFO", logger="lingxi.gateway"):
        client.post("/v1/chat", json={"user": "我的私密笔记"}, headers=AUTH)
    text = caplog.text
    assert "path=/v1/chat" in text and "status=200" in text
    assert "私密笔记" not in text and "瞬时变化率" not in text and "secret" not in text


def test_rate_limiter_window_slides():
    t = [0.0]
    rl = RateLimiter(window_seconds=60, clock=lambda: t[0])
    assert rl.check("d", 2) is None
    assert rl.check("d", 2) is None
    assert rl.check("d", 2) == 60
    t[0] = 30.0
    assert rl.check("d", 2) == 30
    t[0] = 60.0
    assert rl.check("d", 2) is None
    assert device_key("Bearer a", "x") != device_key("Bearer a", "y")


def test_dashscope_api_key_alias_is_accepted(monkeypatch):
    """百炼文档使用 DASHSCOPE_API_KEY，网关也接受这个变量名。"""
    monkeypatch.delenv("LLM_API_KEY", raising=False)
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-from-alias")
    assert Settings(_env_file=None).llm_api_key == "sk-from-alias"
    monkeypatch.setenv("LLM_API_KEY", "sk-primary")
    assert Settings(_env_file=None).llm_api_key == "sk-primary"
