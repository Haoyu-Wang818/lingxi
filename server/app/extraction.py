"""从课堂笔记中抽取知识点与关系。

大模型以 JSON 输出，本模块负责提示词与严格校验：
字段缺失、关系指向不存在的知识点、自环等情况都会被过滤，保证写入图谱的数据始终合法。
"""

import json
from typing import Literal

from pydantic import BaseModel, Field, ValidationError

from .llm import LlmClient, LlmError

Relation = Literal["PREREQUISITE", "PART_OF", "EXAMPLE_OF", "RELATED"]

SYSTEM_PROMPT = """你是大学课程助教。阅读一段课堂笔记，抽取其中的知识点及其关系。
只输出 JSON，格式为：
{"points": [{"id": "英文小写短横线标识", "title": "知识点名称", "summary": "一到两句话解释"}],
 "edges": [{"from": "id", "to": "id", "relation": "PREREQUISITE|PART_OF|EXAMPLE_OF|RELATED"}]}
PREREQUISITE 表示学习 to 之前需要先掌握 from。知识点控制在 3 到 12 个。"""


class Point(BaseModel):
    id: str = Field(min_length=1, max_length=64)
    title: str = Field(min_length=1, max_length=40)
    summary: str = Field(default="", max_length=200)


class Edge(BaseModel):
    from_id: str = Field(alias="from")
    to_id: str = Field(alias="to")
    relation: Relation

    model_config = {"populate_by_name": True}


class Extraction(BaseModel):
    points: list[Point]
    edges: list[Edge]


def parse_extraction(raw: str) -> Extraction:
    """解析并清洗大模型输出。无法解析时抛出 LlmError。"""
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as e:
        raise LlmError("大模型未返回合法 JSON") from e

    points: dict[str, Point] = {}
    for item in data.get("points", []):
        try:
            p = Point.model_validate(item)
        except ValidationError:
            continue
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

    return Extraction(points=list(points.values()), edges=edges)


async def extract_knowledge(llm: LlmClient, note_text: str) -> Extraction:
    reply = await llm.chat(SYSTEM_PROMPT, note_text, max_tokens=1500, json_mode=True)
    return parse_extraction(reply.text)
