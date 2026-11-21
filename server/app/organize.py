"""一节课的识别文本 → 笔记 + 知识点 + 关系 + 卡片（FR-NOTE-03、FR-KG-01、FR-REV-01、FR-GW-01、FR-GW-02）。

为满足「一张板书到卡片 ≤ 15 秒」（NFR-PERF-01），不让模型一次输出全部内容
（约 2000 个 token，qwen-plus 要 40 秒），而是拆成可并行的小调用：
  笔记 ──────────────────────────────┐
  知识点与关系 → 每两个知识点一组并行出卡 ┴→ 校验 → 合并
每次调用输出都很短，用速度快的模型（默认 qwen-flash）。并发数有上限，避免触发上游限流。
"""

import asyncio
import json
import logging
import time

from pydantic import BaseModel

from . import prompts
from .cards import Card, Rejection, points_missing_watch_type, select_cards
from .extraction import Edge, Extraction, Point, extract_knowledge
from .llm import LlmClient, LlmError, LlmInvalidOutputError

logger = logging.getLogger("lingxi.gateway")

POINTS_PER_CARD_CALL = 1
MAX_PARALLEL_CALLS = 6


class KeyPoint(BaseModel):
    text: str
    source_ids: list[str] = []


class Note(BaseModel):
    title: str
    markdown: str
    key_points: list[KeyPoint] = []


class OrganizeResult(BaseModel):
    note: Note | None
    points: list[Point]
    edges: list[Edge]
    cards: list[Card]
    rejected: list[Rejection]
    prompt_version: str
    prompt_versions: dict[str, str]
    latency_ms: int = 0


def _json(text: str) -> dict:
    try:
        data = json.loads(text)
    except json.JSONDecodeError as e:
        raise LlmInvalidOutputError("大模型未返回合法 JSON") from e
    if not isinstance(data, dict):
        raise LlmInvalidOutputError("大模型输出不是 JSON 对象")
    return data


async def make_note(llm: LlmClient, text: str, allowed_sources: set[str]) -> Note | None:
    """生成结构化笔记；失败时返回 None，不影响知识点与卡片（笔记可稍后重试）。"""
    try:
        prompt = prompts.load(prompts.NOTE)
        reply = await llm.chat(prompt, text, max_tokens=1500, json_mode=True, temperature=0.2)
        data = _json(reply.text)
        title, markdown = str(data.get("title", "")).strip(), str(data.get("markdown", "")).strip()
        if not title or not markdown:
            return None
        key_points = []
        for kp in data.get("key_points", []) if isinstance(data.get("key_points"), list) else []:
            if isinstance(kp, dict) and str(kp.get("text", "")).strip():
                ids = [s for s in kp.get("source_ids", []) if s in allowed_sources]
                key_points.append(KeyPoint(text=str(kp["text"]).strip(), source_ids=ids))
        return Note(title=title[:40], markdown=markdown, key_points=key_points)
    except LlmError as e:
        logger.warning("note call failed error=%s %s", type(e).__name__, str(e)[:120])
        return None


def _cards_request(points: list[Point], context: str, course: str) -> str:
    lines = [f"课程：{course}" if course else "", "知识点："]
    for p in points:
        lines.append(f"- id={p.id}；名称：{p.title}；解释：{p.summary}")
    lines += ["课堂原文：", context]
    return "\n".join(line for line in lines if line)


async def make_cards(
    llm: LlmClient, points: list[Point], context: str, course: str
) -> tuple[list[Card], list[Rejection]]:
    """为一组知识点出卡并校验；缺少手表题型的知识点重试一次（FR-REV-01）。"""
    sources = {p.id: p.source_ids for p in points}
    kept: list[Card] = []
    rejected: list[Rejection] = []
    todo = points
    for attempt in range(2):
        try:
            reply = await llm.chat(
                prompts.load(prompts.CARDS),
                _cards_request(todo, context, course),
                max_tokens=900,
                json_mode=True,
                temperature=0.5,
            )
            raw = _json(reply.text).get("cards", [])
        except LlmError as e:
            # 只记错误类型与摘要（不含用户内容），便于排查上游限流或超时。
            name = type(e).__name__
            logger.warning("cards call failed points=%d error=%s %s", len(todo), name, str(e)[:80])
            raw = []
        got, bad = select_cards(raw, {p.id: sources[p.id] for p in todo})
        kept += got
        rejected += bad
        # 没有来源的知识点重试也无法出卡，不计入重试。
        missing = points_missing_watch_type(kept, [p.id for p in points if p.source_ids])
        if not missing or attempt == 1:
            rejected += [Rejection(point_id=p, reason="NO_WATCH_TYPE") for p in missing]
            break
        todo = [p for p in points if p.id in missing]
    # 重试可能让同一知识点超过 3 张，最后再统一筛一次。
    final, extra = select_cards([c.model_dump() for c in kept], sources)
    return final, rejected + extra


async def organize(
    llm: LlmClient,
    text: str,
    allowed_sources: set[str],
    course: str = "",
    with_cards: bool = True,
    segments: dict[str, str] | None = None,
) -> OrganizeResult:
    """整理一节课。知识点抽取失败时整体失败（由调用方映射为 422 / 502）；笔记或个别出卡失败时降级返回。"""
    gate = asyncio.Semaphore(MAX_PARALLEL_CALLS)

    async def limited(fn, *args):
        # 拿到并发名额后才创建协程，被取消的任务不会留下未执行的协程。
        async with gate:
            return await fn(*args)

    start = time.perf_counter()
    note_task = asyncio.create_task(limited(make_note, llm, text, allowed_sources))
    try:
        extraction: Extraction = await limited(extract_knowledge, llm, text, allowed_sources, segments)
    except BaseException:
        note_task.cancel()
        raise
    extract_ms = int((time.perf_counter() - start) * 1000)

    cards: list[Card] = []
    rejected: list[Rejection] = []
    if with_cards:
        groups = [
            extraction.points[i : i + POINTS_PER_CARD_CALL]
            for i in range(0, len(extraction.points), POINTS_PER_CARD_CALL)
        ]
        results = await asyncio.gather(*(limited(make_cards, llm, g, text, course) for g in groups))
        for got, bad in results:
            cards += got
            rejected += bad

    note = await note_task
    # 只记各阶段耗时与数量，不记内容（NFR-SEC-02）。
    logger.info(
        "organize extract_ms=%d total_ms=%d points=%d cards=%d",
        extract_ms,
        int((time.perf_counter() - start) * 1000),
        len(extraction.points),
        len(cards),
    )
    versions = {"extract": prompts.EXTRACT, "note": prompts.NOTE}
    if with_cards:
        versions["cards"] = prompts.CARDS
    return OrganizeResult(
        note=note,
        points=extraction.points,
        edges=extraction.edges,
        cards=cards,
        rejected=rejected,
        prompt_version="+".join(versions.values()),
        prompt_versions=versions,
    )


async def cards_for_points(
    llm: LlmClient, points: list[Point], context: str, course: str
) -> tuple[list[Card], list[Rejection]]:
    """`/v1/cards`：只为给定知识点出卡（例如用户要求「再出几张」）。"""
    gate = asyncio.Semaphore(MAX_PARALLEL_CALLS)

    async def one(group: list[Point]):
        async with gate:
            return await make_cards(llm, group, context, course)

    groups = [points[i : i + POINTS_PER_CARD_CALL] for i in range(0, len(points), POINTS_PER_CARD_CALL)]
    cards: list[Card] = []
    rejected: list[Rejection] = []
    for got, bad in await asyncio.gather(*(one(g) for g in groups)):
        cards += got
        rejected += bad
    return cards, rejected
