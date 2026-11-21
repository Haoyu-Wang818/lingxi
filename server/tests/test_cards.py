"""卡片质量校验与整理流水线（mock 模型）测试。

覆盖 FR-REV-01、FR-REV-10、FR-REV-11、FR-WEAR-08、FR-GW-02、FR-NOTE-03。
"""

import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app.cards import points_missing_watch_type, select_cards, validate_card
from app.config import Settings, get_settings
from app.guard import limiter
from app.llm import LlmClient
from app.main import app, get_fast_llm, get_llm

SOURCES = {"taylor": ["cap-1#L1"], "orphan": []}


def card(**kw) -> dict:
    base = {
        "point_id": "taylor",
        "type": "QUESTION_ANSWER",
        "question": "n 阶泰勒多项式中 (x-x0)^n 项的系数？",
        "answer": "f^(n)(x0)/n!",
        "watch_prompt": "泰勒第 n 项系数？",
        "watch_answer": "f⁽ⁿ⁾(x₀)/n!",
    }
    base.update(kw)
    return base


# ---- 单条规则 ----


def test_valid_card_inherits_point_sources():
    """[FR-REV-10] 合规卡片保留，并继承知识点的来源（sourceRef）。"""
    c, reason = validate_card(card(), SOURCES)
    assert reason is None
    assert c.source_ids == ["cap-1#L1"]
    assert c.watch_prompt == "泰勒第 n 项系数？"


@pytest.mark.parametrize(
    ("override", "reason"),
    [
        ({"point_id": "missing"}, "UNKNOWN_POINT"),
        ({"point_id": "orphan"}, "NO_SOURCE"),
        ({"type": "ESSAY"}, "BAD_TYPE"),
        ({"question": "  "}, "EMPTY"),
        ({"question": "问" * 121}, "QUESTION_TOO_LONG"),
        ({"answer": "答" * 201}, "ANSWER_TOO_LONG"),
        ({"type": "TRUE_FALSE", "answer": "对"}, "BAD_TRUE_FALSE"),
        ({"type": "CLOZE", "question": "没有挖空"}, "BAD_CLOZE"),
        ({"type": "CLOZE", "question": "{{c1::a}} 和 {{c1::b}}"}, "BAD_CLOZE"),
        ({"type": "SINGLE_CHOICE", "options": ["只有一个"], "correct_index": 0}, "BAD_OPTIONS"),
        ({"type": "SINGLE_CHOICE", "options": ["a", "b", "c", "d", "e"], "correct_index": 0}, "BAD_OPTIONS"),
        ({"type": "SINGLE_CHOICE", "options": ["a", "a"], "correct_index": 0}, "DUPLICATE_OPTIONS"),
        (
            {"type": "SINGLE_CHOICE", "options": ["a", "b"], "correct_index": 5, "answer": "c"},
            "BAD_CORRECT_INDEX",
        ),
        ({"type": "SINGLE_CHOICE", "options": "a,b", "correct_index": 0}, "BAD_OPTIONS"),
    ],
)
def test_invalid_cards_are_dropped_with_reason(override, reason):
    """[FR-REV-10] 每条规则都会丢弃不合规的卡片并给出原因代码（TC-QA-01、TC-QA-02）。"""
    c, got = validate_card(card(**override), SOURCES)
    assert c is None
    assert got == reason


def test_single_choice_answer_normalised_from_options():
    """[FR-REV-11] 单选题：答案以正确选项为准；漏给下标时按答案文字找回。"""
    c, _ = validate_card(
        card(type="SINGLE_CHOICE", options=["1/3", "-1/3"], correct_index=0, answer="x"), SOURCES
    )
    assert c.answer == "1/3"
    c, _ = validate_card(
        card(type="SINGLE_CHOICE", options=["1/3", "-1/3"], correct_index=None, answer="-1/3"), SOURCES
    )
    assert c.correct_index == 1


def test_cloze_answer_is_the_hole():
    c, _ = validate_card(
        card(type="CLOZE", question="e^x = {{c1::1 + x + x²/2 + …}}", answer="whatever"), SOURCES
    )
    assert c.answer == "1 + x + x²/2 + …"


def test_true_false_with_reason_is_kept():
    c, reason = validate_card(
        card(type="TRUE_FALSE", question="连续一定可导。", answer="错误。|x| 是反例"), SOURCES
    )
    assert reason is None and c.type == "TRUE_FALSE"


@pytest.mark.parametrize(
    ("prompt", "answer", "kept"),
    [
        ("一" * 20, "二" * 12, True),
        ("一" * 21, "二", False),
        ("一", "二" * 13, False),
        ("", "二", False),
        ("sin x 三阶麦克劳林展开？", "x−x³/6", True),
    ],
)
def test_watch_variant_length_rules(prompt, answer, kept):
    """[FR-WEAR-08] 手表版题面 ≤ 20 字、答案 ≤ 12 字（按码点计数）。

    超长只清空手表版、卡片保留（TC-QA-03 至 05）。
    """
    c, reason = validate_card(card(watch_prompt=prompt, watch_answer=answer), SOURCES)
    assert reason is None
    assert (c.watch_prompt is not None) == kept
    assert (c.watch_answer is not None) == kept


def test_batch_selection_dedupes_caps_and_checks_watch_type():
    """[FR-REV-01] 去重、每个知识点最多 3 张、检查每个知识点至少 1 张判断或单选。"""
    raw = [
        card(),
        card(),  # 重复题面
        card(question="q2"),
        card(question="q3"),
        card(question="q4"),  # 第 4 张
        "not a card",
    ]
    kept, rejected = select_cards(raw, SOURCES)
    assert len(kept) == 3
    assert sorted(r.reason for r in rejected) == ["DUPLICATE", "MALFORMED", "TOO_MANY"]
    assert points_missing_watch_type(kept, ["taylor"]) == ["taylor"]
    tf = card(type="TRUE_FALSE", question="可导必连续。", answer="正确")
    kept2, _ = select_cards([tf], SOURCES)
    assert points_missing_watch_type(kept2, ["taylor"]) == []
    assert select_cards("garbage", SOURCES) == ([], [])


# ---- 整理流水线（mock 模型，按提示词区分调用）----


def scripted(extract: dict, note: dict, cards_by_point: dict, calls: list) -> LlmClient:
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        system, user = body["messages"][0]["content"], body["messages"][-1]["content"]
        if "抽取知识点" in system:
            content, kind = extract, "extract"
        elif "结构化笔记" in system:
            content, kind = note, "note"
        else:
            ids = [p for p in cards_by_point if f"id={p}" in user]
            content, kind = {"cards": [c for p in ids for c in cards_by_point[p]]}, "cards:" + ",".join(ids)
        calls.append(kind)
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(content)}}]})

    return LlmClient(
        httpx.AsyncClient(transport=httpx.MockTransport(handler)), "https://x/v1", "sk-test", "qwen-flash"
    )


EXTRACT = {
    "points": [
        {"id": "taylor", "title": "泰勒公式", "summary": "多项式逼近", "source_ids": ["cap-1#L1", "bogus"]},
        {"id": "maclaurin", "title": "麦克劳林公式", "summary": "x0=0", "source_ids": ["cap-1#L2"]},
        {"id": "peano", "title": "佩亚诺余项", "summary": "o((x-x0)^n)", "source_ids": ["cap-1#L3"]},
    ],
    "edges": [{"from": "maclaurin", "to": "taylor", "relation": "EXAMPLE_OF", "confidence": 0.9}],
}
NOTE = {
    "title": "泰勒公式",
    "markdown": "## 要点\n- 多项式逼近",
    "key_points": [{"text": "逼近", "source_ids": ["cap-1#L1"]}],
}


def tf(point: str, q: str) -> dict:
    return card(point_id=point, type="TRUE_FALSE", question=q, answer="正确")


@pytest.fixture
def client():
    app.dependency_overrides[get_settings] = lambda: Settings(gateway_token="", _env_file=None)
    limiter.reset()
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()


def segments():
    return [
        {"source_id": f"cap-1#L{i}", "text": t}
        for i, t in enumerate(["泰勒公式", "麦克劳林", "佩亚诺余项"], 1)
    ]


def test_organize_returns_note_points_edges_and_cards(client):
    """[FR-NOTE-03][FR-KG-01][FR-REV-01][FR-GW-06] 一次请求得到笔记、知识点、关系与卡片，响应带提示词版本。

    来源 id 只保留请求中出现过的。
    """
    calls: list[str] = []
    cards = {
        "taylor": [card(), tf("taylor", "泰勒公式用多项式逼近函数。")],
        "maclaurin": [
            tf("maclaurin", "麦克劳林是 x0=0 的泰勒公式。"),
            card(point_id="maclaurin", question="e^x 展开？"),
        ],
        "peano": [
            tf("peano", "佩亚诺余项是高阶无穷小。"),
            card(point_id="peano", question="余项写法？", watch_prompt="一" * 30),
        ],
    }
    llm = scripted(EXTRACT, NOTE, cards, calls)
    app.dependency_overrides[get_llm] = app.dependency_overrides[get_fast_llm] = lambda: llm
    resp = client.post(
        "/v1/extract", json={"segments": segments(), "with_cards": True, "course_name": "高等数学"}
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["note"]["title"] == "泰勒公式"
    assert [p["id"] for p in body["points"]] == ["taylor", "maclaurin", "peano"]
    assert body["points"][0]["source_ids"] == ["cap-1#L1"]
    assert body["edges"][0]["from"] == "maclaurin"
    assert len(body["cards"]) == 6
    assert all(c["source_ids"] for c in body["cards"])
    assert body["prompt_versions"] == {"extract": "extract-v2", "note": "note-v1", "cards": "cards-v1"}
    assert resp.headers["X-Prompt-Version"] == "extract-v2+note-v1+cards-v1"
    # 每个知识点一次出卡调用，并行进行。
    assert sorted(calls) == ["cards:maclaurin", "cards:peano", "cards:taylor", "extract", "note"]
    peano_cards = [c for c in body["cards"] if c["point_id"] == "peano"]
    assert any(c["watch_prompt"] is None for c in peano_cards)


def test_point_without_watch_type_is_retried_once_then_rejected(client):
    """[FR-REV-01] 知识点缺判断或单选时重试一次，仍缺则在 rejected 中说明。"""
    calls: list[str] = []
    extract = {"points": [EXTRACT["points"][0]], "edges": []}
    llm = scripted(extract, NOTE, {"taylor": [card()]}, calls)
    app.dependency_overrides[get_llm] = app.dependency_overrides[get_fast_llm] = lambda: llm
    body = client.post("/v1/extract", json={"segments": segments(), "with_cards": True}).json()
    assert calls.count("cards:taylor") == 2
    assert {"point_id": "taylor", "reason": "NO_WATCH_TYPE"} in body["rejected"]
    assert len(body["cards"]) == 1


def test_note_failure_degrades_gracefully(client):
    """笔记生成失败不影响知识点与卡片（笔记可稍后重试，FR-NOTE-06）。"""
    calls: list[str] = []
    llm = scripted(
        {"points": [EXTRACT["points"][0]], "edges": []}, {"oops": 1}, {"taylor": [tf("taylor", "q")]}, calls
    )
    app.dependency_overrides[get_llm] = app.dependency_overrides[get_fast_llm] = lambda: llm
    body = client.post("/v1/extract", json={"segments": segments(), "with_cards": True}).json()
    assert body["note"] is None
    assert len(body["cards"]) == 1


def test_without_cards_flag_skips_card_generation(client):
    calls: list[str] = []
    llm = scripted(EXTRACT, NOTE, {}, calls)
    app.dependency_overrides[get_llm] = app.dependency_overrides[get_fast_llm] = lambda: llm
    body = client.post("/v1/extract", json={"segments": segments()}).json()
    assert body["cards"] == []
    assert sorted(calls) == ["extract", "note"]


def test_cards_endpoint_generates_for_given_points(client):
    """[FR-GW-02] /v1/cards：为给定知识点出卡；没有来源的知识点不出卡。"""
    calls: list[str] = []
    llm = scripted({}, {}, {"taylor": [tf("taylor", "泰勒公式用多项式逼近函数。"), card()]}, calls)
    app.dependency_overrides[get_llm] = app.dependency_overrides[get_fast_llm] = lambda: llm
    req = {
        "course_name": "高等数学",
        "points": [
            {
                "id": "taylor",
                "title": "泰勒公式",
                "summary": "多项式逼近",
                "source_ids": ["cap-1"],
                "context": "原文",
            },
        ],
    }
    resp = client.post("/v1/cards", json=req)
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["cards"]) == 2
    assert body["prompt_version"] == "cards-v1"
    assert client.post("/v1/cards", json={"points": []}).status_code == 400


def test_watch_variant_with_cloze_markup_is_stripped():
    """[FR-WEAR-08] 手表不显示挖空标记，带 {{c1::}} 的手表版本被清空。"""
    c, _ = validate_card(card(watch_prompt="拉格朗日余项 {{c1::}}", watch_answer="(x-x0)^(n+1)"), SOURCES)
    assert c.watch_prompt is None and c.watch_answer is None


def test_points_without_valid_sources_fall_back_to_title_match(client):
    """[FR-REV-10] 模型给出的来源 id 无效时，按知识点名称在原文中查找来源，卡片仍可溯源。"""
    calls: list[str] = []
    extract = {
        "points": [{"id": "taylor", "title": "泰勒公式", "summary": "逼近", "source_ids": ["bogus"]}],
        "edges": [],
    }
    llm = scripted(extract, NOTE, {"taylor": [tf("taylor", "泰勒公式用多项式逼近函数。")]}, calls)
    app.dependency_overrides[get_llm] = app.dependency_overrides[get_fast_llm] = lambda: llm
    body = client.post("/v1/extract", json={"segments": segments(), "with_cards": True}).json()
    assert body["points"][0]["source_ids"] == ["cap-1#L1"]
    assert len(body["cards"]) == 1


def test_point_without_any_source_is_not_retried(client):
    """没有来源的知识点不出卡，也不白白重试。"""
    calls: list[str] = []
    extract = {
        "points": [{"id": "x", "title": "原文没有的知识点", "summary": "", "source_ids": []}],
        "edges": [],
    }
    llm = scripted(extract, NOTE, {"x": [tf("x", "q")]}, calls)
    app.dependency_overrides[get_llm] = app.dependency_overrides[get_fast_llm] = lambda: llm
    body = client.post("/v1/extract", json={"segments": segments(), "with_cards": True}).json()
    assert calls.count("cards:x") == 1
    assert body["cards"] == []
    assert {"point_id": "x", "reason": "NO_SOURCE"} in body["rejected"]
