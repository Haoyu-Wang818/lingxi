#!/usr/bin/env python3
"""生成需求可追溯矩阵 docs/traceability.md。

从 SRS 中解析全部需求编号，再在设计文档、源码、测试中检索引用位置，
输出「需求 → 迭代 → 设计 → 代码 → 测试」矩阵，并列出尚未被任何测试覆盖的 Must 需求。

用法：python3 scripts/trace.py [--check]
  --check  存在已进入迭代但无测试引用的 Must 需求时以非零码退出（用于功能冻结前的门禁）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRS = ROOT / "docs/01-requirements/03-srs.md"
OUT = ROOT / "docs/traceability.md"
REQ_ROW = re.compile(r"^\| ((?:FR|NFR)-[A-Z]+-\d{2}) \|(.+)$")

# 需求到迭代的分配，与开发计划保持一致；未列出的 Could 需求归入「复赛」。
ITERATION = {
    # I1 数据层与基础设施
    "I1": ["FR-COURSE-01", "FR-COURSE-02", "FR-COURSE-03", "FR-AI-01", "FR-AI-02", "FR-AI-03",
           "FR-GW-05", "FR-SET-01"],
    # I2 拍照捕获 → 笔记 → 卡片生成
    "I2": ["FR-CAP-01", "FR-CAP-02", "FR-CAP-03", "FR-CAP-06", "FR-NOTE-01", "FR-NOTE-03",
           "FR-NOTE-04", "FR-NOTE-06", "FR-KG-01", "FR-AI-04", "FR-GW-01", "FR-GW-02", "FR-GW-06",
           "FR-REV-01", "FR-REV-10", "FR-REV-11"],
    # I3 手机卡片式复习（核心交互）
    "I3": ["FR-REV-02", "FR-REV-03", "FR-REV-04", "FR-REV-05", "FR-REV-07", "FR-REV-08",
           "FR-REV-09", "FR-REV-12"],
    # I4 录音与知识图谱
    "I4": ["FR-CAP-04", "FR-NOTE-02", "FR-NOTE-05", "FR-KG-02", "FR-KG-03", "FR-KG-04",
           "FR-KG-05", "FR-KG-06", "FR-AI-05"],
    # I5 情境推送与手表（加分项）
    "I5": ["FR-CTX-01", "FR-CTX-02", "FR-CTX-03", "FR-CTX-04", "FR-CTX-05", "FR-CTX-06",
           "FR-CTX-08", "FR-CTX-09", "FR-WEAR-01", "FR-WEAR-02", "FR-WEAR-03", "FR-WEAR-04",
           "FR-WEAR-05", "FR-WEAR-07", "FR-WEAR-08", "FR-SYNC-01", "FR-SYNC-02", "FR-SYNC-03",
           "FR-SYNC-04", "FR-GW-04", "FR-SET-02", "FR-SET-03", "FR-SET-04"],
}
ITER_OF = {req: it for it, reqs in ITERATION.items() for req in reqs}

SKIP_DIRS = {".git", ".gradle", ".idea", "build", ".venv", "node_modules", ".pytest_cache", ".ruff_cache"}


def collect(kind: str) -> list[Path]:
    """按类别收集待检索文件：design 设计文档、code 产品代码、test 测试代码。"""
    files = []
    for path in walk(ROOT):
        rel = path.relative_to(ROOT).as_posix()
        if kind == "design" and rel.startswith("docs/02-design/") and rel.endswith(".md"):
            files.append(path)
        elif kind == "code" and (("/src/main/" in rel and rel.endswith(".kt")) or
                                 (rel.startswith("server/app/") and rel.endswith(".py"))):
            files.append(path)
        elif kind == "test" and ((("/src/test/" in rel or "/src/androidTest/" in rel) and rel.endswith(".kt")) or
                                 (rel.startswith("server/tests/") and rel.endswith(".py"))):
            files.append(path)
    return files


def walk(directory: Path):
    """深度优先遍历仓库，跳过构建产物与缓存目录。"""
    for entry in directory.iterdir():
        if entry.is_dir():
            if entry.name not in SKIP_DIRS:
                yield from walk(entry)
        elif entry.is_file():
            yield entry


def parse_requirements() -> list[tuple[str, str, str]]:
    """返回 (编号, 简述, 优先级)。功能需求优先级在第 3 列，非功能需求标记为 NFR。"""
    reqs = []
    for line in SRS.read_text(encoding="utf-8").splitlines():
        m = REQ_ROW.match(line)
        if not m:
            continue
        cells = [c.strip() for c in m.group(2).split("|")]
        if m.group(1).startswith("FR"):
            reqs.append((m.group(1), cells[0], cells[1]))
        else:
            reqs.append((m.group(1), cells[1], "NFR"))
    return reqs


def find_refs(files: dict[Path, str], req_id: str) -> list[str]:
    """返回内容中引用了需求编号的文件相对路径。"""
    pattern = re.compile(re.escape(req_id) + r"(?!\d)")
    return sorted(str(p.relative_to(ROOT)) for p, text in files.items() if pattern.search(text))


def main() -> int:
    reqs = parse_requirements()
    corpus = {k: {p: p.read_text(encoding="utf-8", errors="ignore") for p in collect(k)}
              for k in ("design", "code", "test")}
    rows, missing = [], []
    for req_id, title, prio in reqs:
        iteration = ITER_OF.get(req_id, "复赛" if prio == "C" else "全程" if prio == "NFR" else "待定")
        design = find_refs(corpus["design"], req_id)
        code = find_refs(corpus["code"], req_id)
        tests = find_refs(corpus["test"], req_id)
        if prio == "M" and not tests:
            missing.append((req_id, iteration))
        fmt = lambda xs: "<br>".join(f"`{x}`" for x in xs) or "—"  # noqa: E731
        rows.append(f"| {req_id} | {title} | {prio} | {iteration} | {fmt(design)} | {fmt(code)} | {fmt(tests)} |")

    lines = [
        "# 需求可追溯矩阵",
        "",
        "> 本文件由 `scripts/trace.py` 生成，请勿手工修改。",
        "",
        f"需求共 {len(reqs)} 条；Must 需求中尚无测试引用的 {len(missing)} 条。",
        "",
        "| 需求 | 简述 | 优先级 | 迭代 | 设计 | 代码 | 测试 |",
        "| --- | --- | --- | --- | --- | --- | --- |",
        *rows,
        "",
    ]
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"已生成 {OUT.relative_to(ROOT)}：{len(reqs)} 条需求，{len(missing)} 条 Must 尚无测试")

    if "--check" in sys.argv:
        due = [r for r, it in missing if it in ITERATION]
        if due:
            print("以下 Must 需求已分配迭代但没有测试：", ", ".join(due))
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
