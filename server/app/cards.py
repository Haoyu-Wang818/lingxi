"""卡片质量校验（FR-REV-01、FR-REV-10、FR-REV-11、FR-WEAR-08，04-api.md 1.5 节）。

规则与手机端 core:quality 一致，两端各校验一次。字数按 Unicode 码点计数，中英文都算 1。
不合规的卡片丢弃并记录原因；手表版本超长只清空手表版本，卡片保留。
"""

import re
from typing import Literal

from pydantic import BaseModel

CardType = Literal["QUESTION_ANSWER", "CLOZE", "TRUE_FALSE", "SINGLE_CHOICE"]
WATCH_TYPES = {"TRUE_FALSE", "SINGLE_CHOICE"}

MAX_QUESTION = 120
MAX_ANSWER = 200
MAX_WATCH_PROMPT = 20
MAX_WATCH_ANSWER = 12
MAX_CARDS_PER_POINT = 3
CLOZE = re.compile(r"\{\{c1::(.+?)\}\}")


class Card(BaseModel):
    point_id: str
    type: CardType
    question: str
    answer: str
    options: list[str] | None = None
    correct_index: int | None = None
    watch_prompt: str | None = None
    watch_answer: str | None = None
    source_ids: list[str] = []


class Rejection(BaseModel):
    point_id: str
    reason: str


def _text(value) -> str:
    return value.strip() if isinstance(value, str) else ""


def validate_card(raw: dict, sources: dict[str, list[str]]) -> tuple[Card | None, str | None]:
    """校验一张卡。返回 (卡片, None) 或 (None, 拒绝原因代码)。

    [sources] 是知识点 id 到来源 id 的映射：卡片必须属于其中一个知识点，并继承它的来源（sourceRef）。
    """
    if not isinstance(raw, dict):
        return None, "MALFORMED"
    point_id = _text(raw.get("point_id"))
    if point_id not in sources:
        return None, "UNKNOWN_POINT"
    if not sources[point_id]:
        return None, "NO_SOURCE"
    card_type = raw.get("type")
    question, answer = _text(raw.get("question")), _text(raw.get("answer"))
    if card_type not in ("QUESTION_ANSWER", "CLOZE", "TRUE_FALSE", "SINGLE_CHOICE"):
        return None, "BAD_TYPE"
    if not question or not answer:
        return None, "EMPTY"
    if len(question) > MAX_QUESTION:
        return None, "QUESTION_TOO_LONG"
    if len(answer) > MAX_ANSWER:
        return None, "ANSWER_TOO_LONG"

    options: list[str] | None = None
    correct: int | None = None
    if card_type == "SINGLE_CHOICE":
        opts = raw.get("options")
        if not isinstance(opts, list) or not all(isinstance(o, str) and o.strip() for o in opts):
            return None, "BAD_OPTIONS"
        options = [o.strip() for o in opts]
        if not 2 <= len(options) <= 4:
            return None, "BAD_OPTIONS"
        if len(set(options)) != len(options):
            return None, "DUPLICATE_OPTIONS"
        correct = raw.get("correct_index")
        if not isinstance(correct, int) or isinstance(correct, bool) or not 0 <= correct < len(options):
            # 模型偶尔漏给下标，按答案文字找回；找不到说明答案不唯一或不在选项里。
            matches = [i for i, o in enumerate(options) if o == answer]
            if len(matches) != 1:
                return None, "BAD_CORRECT_INDEX"
            correct = matches[0]
        answer = options[correct]
    elif card_type == "CLOZE":
        holes = CLOZE.findall(question)
        if len(holes) != 1:
            return None, "BAD_CLOZE"
        answer = holes[0].strip()
    elif card_type == "TRUE_FALSE" and not answer.startswith(("正确", "错误")):
        return None, "BAD_TRUE_FALSE"

    watch_prompt = _text(raw.get("watch_prompt")) or None
    watch_answer = _text(raw.get("watch_answer")) or None
    # 手表版本：两项都要有且不超长，否则一起清空，这张卡只在手机上出现（FR-WEAR-08）。
    if (
        watch_prompt is None
        or "{{" in watch_prompt
        or watch_answer is None
        or len(watch_prompt) > MAX_WATCH_PROMPT
        or len(watch_answer) > MAX_WATCH_ANSWER
    ):
        watch_prompt = watch_answer = None

    card = Card(
        point_id=point_id,
        type=card_type,
        question=question,
        answer=answer,
        options=options,
        correct_index=correct,
        watch_prompt=watch_prompt,
        watch_answer=watch_answer,
        source_ids=sources[point_id],
    )
    return card, None


def select_cards(raw_cards: list, sources: dict[str, list[str]]) -> tuple[list[Card], list[Rejection]]:
    """校验一批卡片：去掉不合规与重复题面，每个知识点最多 3 张，并检查每个知识点至少 1 张手表题型。"""
    kept: list[Card] = []
    rejected: list[Rejection] = []
    seen: set[tuple[str, str]] = set()
    for raw in raw_cards if isinstance(raw_cards, list) else []:
        card, reason = validate_card(raw, sources)
        if card is None:
            pid = _text(raw.get("point_id")) if isinstance(raw, dict) else ""
            rejected.append(Rejection(point_id=pid, reason=reason or "MALFORMED"))
            continue
        key = (card.point_id, card.question)
        if key in seen:
            rejected.append(Rejection(point_id=card.point_id, reason="DUPLICATE"))
            continue
        if sum(1 for c in kept if c.point_id == card.point_id) >= MAX_CARDS_PER_POINT:
            rejected.append(Rejection(point_id=card.point_id, reason="TOO_MANY"))
            continue
        seen.add(key)
        kept.append(card)
    return kept, rejected


def points_missing_watch_type(cards: list[Card], point_ids: list[str]) -> list[str]:
    """没有判断或单选卡的知识点（FR-REV-01：每个知识点至少 1 张手表题型）。"""
    have = {c.point_id for c in cards if c.type in WATCH_TYPES}
    return [p for p in point_ids if p not in have]
