"""从课堂笔记中抽取知识点与关系。

大模型以 JSON 输出，本模块负责提示词与严格校验：
字段缺失、关系指向不存在的知识点、自环等情况都会被过滤，保证写入图谱的数据始终合法。
"""

import json
from typing import Literal

from pydantic import BaseModel, Field, ValidationError

from . import prompts
from .llm import LlmClient, LlmInvalidOutputError

Relation = Literal["PREREQUISITE", "PART_OF", "EXAMPLE_OF", "RELATED"]

class Point(BaseModel):
    id: str = Field(min_length=1, max_length=64)
    title: str = Field(min_length=1, max_length=40)
    summary: str = Field(default="", max_length=200)
    source_ids: list[str] = []


class Edge(BaseModel):
    from_id: str = Field(alias="from")
    to_id: str = Field(alias="to")
    relation: Relation
    confidence: float = Field(default=0.8, ge=0.0, le=1.0)

    model_config = {"populate_by_name": True}


PROMPT_VERSION = prompts.EXTRACT


class Extraction(BaseModel):
    points: list[Point]
    edges: list[Edge]
    prompt_version: str = PROMPT_VERSION


def parse_extraction(raw: str, allowed_sources: set[str] | None = None) -> Extraction:
    """解析并清洗大模型输出。不是 JSON 对象或一个合法知识点都没有时抛出 LlmInvalidOutputError。

    [allowed_sources] 给出时，知识点的 source_ids 只保留请求中出现过的来源 id（04-api.md 1.4 节）。
    """
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as e:
        raise LlmInvalidOutputError("大模型未返回合法 JSON") from e
    if not isinstance(data, dict):
        raise LlmInvalidOutputError("大模型输出不是 JSON 对象")

    points: dict[str, Point] = {}
    for item in data.get("points", []):
        try:
            p = Point.model_validate(item)
        except ValidationError:
            continue
        if allowed_sources is not None:
            p.source_ids = [s for s in dict.fromkeys(p.source_ids) if s in allowed_sources]
        points.setdefault(p.id, p)

    edges: list[Edge] = []
    seen: set[tuple[str, str, str]] = set()
    for item in data.get("edges", []):
        try:
            e = Edge.model_validate(item)
        except ValidationError:
            continue
        key = (e.from_id, e.to_id, e.relation)
        if e.from_id == e.to_id or e.from_id not in points or e.to_id not in points or key in seen:
            continue
        seen.add(key)
        edges.append(e)

    if not points:
        raise LlmInvalidOutputError("大模型输出中没有合法的知识点")
    return Extraction(points=list(points.values()), edges=edges)


async def extract_knowledge(
    llm: LlmClient, note_text: str, allowed_sources: set[str] | None = None
) -> Extraction:
    """调用大模型抽取；输出不合规时重试一次，仍失败抛出 LlmInvalidOutputError（FR-GW-01）。"""
    last: LlmInvalidOutputError | None = None
    for _ in range(2):
        reply = await llm.chat(
            prompts.load(prompts.EXTRACT), note_text, max_tokens=1200, json_mode=True, temperature=0.2
        )
        try:
            return parse_extraction(reply.text, allowed_sources)
        except LlmInvalidOutputError as e:
            last = e
    assert last is not None
    raise last
