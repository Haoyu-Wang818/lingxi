#!/usr/bin/env python3
"""OCR 字准确率评测（FR-NOTE-01：自建板书集字准确率 ≥ 90%）。

流程：在已连接的模拟器上运行 core:ondevice 的仪器测试 OcrEvalTest（真实 PP-OCRv4 模型），
取回识别结果，与 docs/03-test/fixtures/board-N.txt 的标准答案比较，结果写入 docs/03-test/ocr-eval.md。

字准确率 = 1 − 编辑距离 / 标准答案字数。比较前统一做 NFKC 归一化（全角半角统一）并去掉所有空白，
因为板书中的空格与换行不影响内容，识别结果的行切分也可能与标准答案不同。

用法：python3 scripts/ocr_eval.py [--skip-run]
"""

import json
import os
import statistics
import subprocess
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "docs/03-test/fixtures"
REPORT = ROOT / "docs/03-test/ocr-eval.md"
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
TARGET = 0.90


def normalize(text: str) -> str:
    return "".join(unicodedata.normalize("NFKC", text).split())


def edit_distance(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def main() -> int:
    if "--skip-run" not in sys.argv:
        subprocess.run([ADB, "logcat", "-c"], check=True)
        test = "com.lingxi.core.ondevice.OcrEvalTest"
        subprocess.run(
            ["./gradlew", ":core:ondevice:connectedDebugAndroidTest",
             f"-Pandroid.testInstrumentationRunnerArguments.class={test}", "-q"],
            cwd=ROOT, check=True,
        )
    log = subprocess.run([ADB, "logcat", "-d", "-v", "raw", "-s", "OcrEval"], check=True, capture_output=True, text=True)
    body = log.stdout.split("BEGIN", 1)[1].split("END", 1)[0]
    payload = "\n".join(line[1:] for line in body.splitlines() if line.startswith("|"))
    results = json.loads(payload)
    (ROOT / "core/ondevice/build/ocr-eval.json").write_text(payload, encoding="utf-8")

    rows, total_ref, total_err, warm = [], 0, 0, []
    for r in results:
        ref = normalize((FIXTURES / f"{r['name']}.txt").read_text(encoding="utf-8"))
        hyp = normalize(r["text"])
        err = edit_distance(ref, hyp)
        total_ref += len(ref)
        total_err += err
        warm += r["elapsed_ms"][1:]
        rows.append((r["name"], len(ref), err, 1 - err / len(ref), r["elapsed_ms"][0], statistics.median(r["elapsed_ms"][1:])))
    overall = 1 - total_err / total_ref

    lines = [
        "# OCR 字准确率评测（FR-NOTE-01）",
        "",
        "由 `scripts/ocr_eval.py` 生成：在 Android 模拟器上用端侧 PP-OCRv4 mobile（ONNX Runtime）识别",
        "`docs/03-test/fixtures/` 中的板书图，与逐行标准答案比较。字准确率 = 1 − 编辑距离 / 标准答案字数，",
        "比较前做 NFKC 归一化并去掉空白。",
        "",
        "| 图片 | 标准字数 | 编辑距离 | 字准确率 | 首次耗时（含加载模型） | 常规耗时中位数 |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for name, n, err, acc, cold, med in rows:
        lines.append(f"| {name} | {n} | {err} | {acc:.1%} | {cold} ms | {med:.0f} ms |")
    lines += [
        f"| **合计** | {total_ref} | {total_err} | **{overall:.1%}** | | {statistics.median(warm):.0f} ms |",
        "",
        f"目标 ≥ {TARGET:.0%}：{'达标' if overall >= TARGET else '未达标'}。",
        "",
        "## 识别结果与标准答案对照",
        "",
    ]
    for r in results:
        ref = (FIXTURES / f"{r['name']}.txt").read_text(encoding="utf-8").strip()
        lines += [f"### {r['name']}", "", "标准答案：", "", "```", ref, "```", "", "识别结果：", "", "```", r["text"], "```", ""]
    REPORT.write_text("\n".join(lines), encoding="utf-8")
    print(f"字准确率 {overall:.1%}（{total_ref - total_err}/{total_ref}），报告：{REPORT}")
    return 0 if overall >= TARGET else 1


if __name__ == "__main__":
    sys.exit(main())
