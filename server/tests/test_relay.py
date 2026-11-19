import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.config import get_settings
from app.main import app

CARD = '{"type":"push_card","id":"m1","cardId":"c1","question":"q","options":["对","错"],"reason":"r"}'


@pytest.fixture
def client():
    get_settings.cache_clear()
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()
    get_settings.cache_clear()


def test_relay_forwards_between_phone_and_watch(client):
    with (
        client.websocket_connect("/v1/relay/demo1/phone") as phone,
        client.websocket_connect("/v1/relay/demo1/watch") as watch,
    ):
        phone.send_text(CARD)
        assert watch.receive_text() == CARD
        watch.send_text('{"type":"review_result","id":"r1"}')
        assert phone.receive_text() == '{"type":"review_result","id":"r1"}'


def test_relay_reports_offline_peer(client):
    with client.websocket_connect("/v1/relay/demo2/phone") as phone:
        phone.send_text(CARD)
        assert phone.receive_json() == {"type": "relay_error", "detail": "peer offline"}


def test_relay_isolates_pair_codes(client):
    with (
        client.websocket_connect("/v1/relay/roomA/phone") as phone,
        client.websocket_connect("/v1/relay/roomB/watch"),
    ):
        phone.send_text(CARD)
        assert phone.receive_json()["detail"] == "peer offline"


def test_relay_rejects_bad_token(monkeypatch):
    monkeypatch.setenv("GATEWAY_TOKEN", "secret")
    get_settings.cache_clear()
    with (
        TestClient(app) as c,
        pytest.raises(WebSocketDisconnect) as exc,
        c.websocket_connect("/v1/relay/demo3/phone?token=wrong") as ws,
    ):
        ws.receive_text()
    assert exc.value.code == 4401
    get_settings.cache_clear()
