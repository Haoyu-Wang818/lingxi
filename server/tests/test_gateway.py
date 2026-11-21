import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.extraction import parse_extraction
from app.guard import limiter
from app.llm import LlmClient, LlmError
from app.main import app, get_fast_llm, get_llm


def fake_llm(content: str, status: int = 200) -> LlmClient:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer test-key"
        if status != 200:
            return httpx.Response(status, text="upstream error")
        payload = {"model": "qwen-test", "choices": [{"message": {"content": content}}]}
        return httpx.Response(200, json=payload)

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return LlmClient(http, "https://llm.example/v1", "test-key", "qwen-test")


def use_llm(factory) -> None:
    """让普通模型与快速模型都使用同一个假客户端。"""
    app.dependency_overrides[get_llm] = app.dependency_overrides[get_fast_llm] = factory


@pytest.fixture
def client():
    app.dependency_overrides[get_settings] = lambda: Settings(gateway_token="secret", _env_file=None)
    limiter.reset()
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()


AUTH = {"Authorization": "Bearer secret"}


def test_health(client):
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert body["version"] == "0.2.0"
    assert body["llm_configured"] is False
    assert body["model"] == "qwen-plus"


def test_chat_requires_token(client):
    use_llm(lambda: fake_llm("hi"))
    assert client.post("/v1/chat", json={"user": "你好"}).status_code == 401


def test_chat_forwards_to_llm(client):
    use_llm(lambda: fake_llm("导数是瞬时变化率"))
    resp = client.post("/v1/chat", json={"user": "什么是导数"}, headers=AUTH)
    assert resp.status_code == 200
    assert resp.json()["text"] == "导数是瞬时变化率"
    assert resp.json()["model"] == "qwen-test"


def test_upstream_failure_maps_to_502(client):
    use_llm(lambda: fake_llm("", status=500))
    assert client.post("/v1/chat", json={"user": "x"}, headers=AUTH).status_code == 502


def test_extract_endpoint(client):
    content = json.dumps(
        {
            "points": [
                {"id": "derivative", "title": "导数", "summary": "瞬时变化率"},
                {"id": "taylor", "title": "泰勒展开", "summary": "多项式逼近"},
            ],
            "edges": [{"from": "derivative", "to": "taylor", "relation": "PREREQUISITE"}],
        }
    )
    use_llm(lambda: fake_llm(content))
    resp = client.post("/v1/extract", json={"text": "今天讲泰勒公式……"}, headers=AUTH)
    assert resp.status_code == 200
    body = resp.json()
    assert [p["id"] for p in body["points"]] == ["derivative", "taylor"]
    edge = {"from": "derivative", "to": "taylor", "relation": "PREREQUISITE", "confidence": 0.8}
    assert body["edges"][0] == edge
    assert body["prompt_version"] == "extract-v2+note-v1"
    assert resp.headers["X-Prompt-Version"] == "extract-v2+note-v1"


def test_parse_extraction_filters_invalid_items():
    raw = json.dumps(
        {
            "points": [
                {"id": "a", "title": "A"},
                {"id": "a", "title": "重复的 A"},
                {"id": "b", "title": ""},
                {"id": "c", "title": "C"},
            ],
            "edges": [
                {"from": "a", "to": "c", "relation": "RELATED"},
                {"from": "a", "to": "c", "relation": "RELATED"},
                {"from": "a", "to": "a", "relation": "RELATED"},
                {"from": "a", "to": "missing", "relation": "RELATED"},
                {"from": "a", "to": "c", "relation": "UNKNOWN"},
            ],
        }
    )
    result = parse_extraction(raw)
    assert [p.id for p in result.points] == ["a", "c"]
    assert result.points[0].title == "A"
    assert len(result.edges) == 1


def test_parse_extraction_rejects_non_json():
    with pytest.raises(LlmError):
        parse_extraction("这不是 JSON")
