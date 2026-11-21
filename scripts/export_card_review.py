#!/usr/bin/env python3
"""生成卡片抽查表（FR-REV-10 人工复核）。

对 docs/03-test/fixtures/ 下的每张测试板书，用端侧 OCR 的识别结果（core/ondevice/build/ocr-eval.json，
由 scripts/ocr_eval.py 生成）按手机端相同的格式（每行一段，id 为 L1、L2…）请求本地网关 /v1/extract（with_cards），
把生成的全部卡片导出为 docs/03-test/card-review.md 与 card-review.csv，末尾三列留给人工填写。

用法：先在 server 目录启动网关（uv run uvicorn app.main:app --host 127.0.0.1 --port 8000），再运行
  python3 scripts/export_card_review.py [--gateway http://127.0.0.1:8000]
"""

import csv
import json
import sys
import time
import urllib.request
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OCR = ROOT / "core/ondevice/build/ocr-eval.json"
OUT_MD = ROOT / "docs/03-test/card-review.md"
OUT_CSV = ROOT / "docs/03-test/card-review.csv"
TYPES = {"QUESTION_ANSWER": "问答", "CLOZE": "挖空", "TRUE_FALSE": "判断", "SINGLE_CHOICE": "单选"}
HEADER = ["编号", "板书", "知识点", "题型", "题面", "答案", "手表题面", "手表答案", "来源行原文", "是否正确", "是否值得复习", "问题说明"]


def post(gateway: str, body: dict) -> dict:
    req = urllib.request.Request(
        gateway.rstrip("/") + "/v1/extract",
        data=json.dumps(body, ensure_ascii=False).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=90) as resp:
        return json.loads(resp.read())


def answer_text(card: dict) -> str:
    if card["type"] == "SINGLE_CHOICE" and card.get("options"):
        letters = "ABCD"
        opts = "；".join(
            f"{letters[i]}. {o}{' ✔' if i == card.get('correct_index') else ''}" for i, o in enumerate(card["options"])
        )
        return f"{opts}（答案：{card['answer']}）"
    return card["answer"]


def md_cell(text: str) -> str:
    return (text or "").replace("|", "\\|").replace("\n", " ")


def main() -> int:
    gateway = sys.argv[sys.argv.index("--gateway") + 1] if "--gateway" in sys.argv else "http://127.0.0.1:8000"
    pages = {p["name"]: p for p in json.loads(OCR.read_text(encoding="utf-8"))}
    rows, summary = [], []
    for name in sorted(pages):
        lines = [line["text"] for line in pages[name]["lines"]]
        segments = [{"source_id": f"L{i}", "kind": "OCR", "text": t, "page": 1} for i, t in enumerate(lines, 1)]
        body = {"course_name": "高等数学", "lesson_id": name, "segments": segments, "with_cards": True}
        start = time.perf_counter()
        result = post(gateway, body)
        seconds = time.perf_counter() - start
        titles = {p["id"]: p["title"] for p in result["points"]}
        summary.append((name, len(result["points"]), len(result["cards"]), seconds, result["rejected"]))
        for card in result["cards"]:
            sources = [lines[int(s[1:]) - 1] for s in card.get("source_ids", []) if s[1:].isdigit() and int(s[1:]) <= len(lines)]
            rows.append(
                [
                    len(rows) + 1,
                    name,
                    titles.get(card["point_id"], card["point_id"]),
                    TYPES.get(card["type"], card["type"]),
                    card["question"],
                    answer_text(card),
                    card.get("watch_prompt") or "",
                    card.get("watch_answer") or "",
                    " / ".join(sources),
                    "",
                    "",
                    "",
                ]
            )

    with OUT_CSV.open("w", encoding="utf-8-sig", newline="") as f:  # 带 BOM，Excel 打开不乱码
        writer = csv.writer(f)
        writer.writerow(HEADER)
        writer.writerows(rows)

    md = [
        "# 卡片人工抽查表",
        "",
        f"生成日期：{date.today()}。由 `scripts/export_card_review.py` 生成：3 张测试板书（`docs/03-test/fixtures/`）的端侧 OCR 结果，",
        "按手机端相同的请求格式交给本地网关，由通义千问（qwen-flash）生成，已经过网关的质量校验。",
        "同内容的表格见 `card-review.csv`（可用 Excel 或 Numbers 打开填写）。",
        "",
        "## 怎么检查",
        "",
        "每张卡填写最后三列：",
        "",
        "- **是否正确**：题面与答案在数学上是否正确、答案是否唯一（单选题 ✔ 为标注的正确项）。填「是 / 否」。",
        "- **是否值得复习**：是否考到了板书的要点，而不是无意义的细节或过于简单的问题。填「是 / 否」。",
        "- **问题说明**：不正确或不值得复习时写一句原因，例如「答案应为 1/6」「题面有歧义」「只是照抄原文」。",
        "",
        "手表题面 / 手表答案为空，表示这张卡不推送到手表（超过字数限制或不适合小屏）。",
        "来源行原文是卡片背面「📷」溯源指向的 OCR 识别文字，可以顺便看识别错误是否影响了出题。",
        "",
        "## 汇总",
        "",
        "| 板书 | 知识点 | 卡片 | 网关耗时 | 被丢弃的卡（原因） |",
        "| --- | --- | --- | --- | --- |",
    ]
    for name, points, cards, seconds, rejected in summary:
        reasons = "、".join(sorted({r["reason"] for r in rejected})) or "无"
        md.append(f"| {name} | {points} | {cards} | {seconds:.1f} 秒 | {len(rejected)}（{reasons}） |")
    md += ["", f"共 {len(rows)} 张卡片。", "", "## 卡片", "", "| " + " | ".join(HEADER) + " |", "|" + " --- |" * len(HEADER)]
    md += ["| " + " | ".join(md_cell(str(c)) for c in row) + " |" for row in rows]
    OUT_MD.write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"共 {len(rows)} 张卡片：{OUT_MD}，{OUT_CSV}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
