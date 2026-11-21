#!/usr/bin/env python3
"""生成 OCR 评测用的板书风格测试图（FR-NOTE-01 验收）。

用电脑字体在深绿色「黑板」上渲染高数板书，加入粉笔色差、噪点、轻微倾斜与模糊，
同时写出逐行标准答案（board-N.txt），供 scripts/ocr_eval.py 计算字准确率。
用法：uv run --with pillow python3 scripts/make_board_fixtures.py
"""

import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = Path(__file__).resolve().parent.parent / "docs/03-test/fixtures"
FONT = "/System/Library/Fonts/Hiragino Sans GB.ttc"

BOARDS = {
    "board-1": [
        "第五讲 泰勒公式",
        "设f(x)在x0处有n阶导数，则",
        "f(x)=f(x0)+f'(x0)(x-x0)+…+Rn(x)",
        "佩亚诺余项：Rn(x)=o((x-x0)^n)",
        "拉格朗日余项的中值介于x0与x之间",
        "当x0=0时称为麦克劳林公式",
    ],
    "board-2": [
        "常用的麦克劳林展开",
        "e^x=1+x+x^2/2!+x^3/3!+…",
        "sin x=x-x^3/3!+x^5/5!-…",
        "cos x=1-x^2/2!+x^4/4!-…",
        "ln(1+x)=x-x^2/2+x^3/3-…",
        "例：lim(x→0)(x-sin x)/x^3=1/6",
    ],
    "board-3": [
        "导数的定义与连续性",
        "f'(x0)=lim(h→0)[f(x0+h)-f(x0)]/h",
        "可导一定连续，连续不一定可导",
        "反例：y=|x|在x=0处连续但不可导",
        "洛必达法则：0/0型或∞/∞型未定式",
        "若lim f'(x)/g'(x)存在，则极限相等",
    ],
}


def render(name: str, lines: list[str], seed: int) -> None:
    rnd = random.Random(seed)
    w, h = 1600, 1000
    img = Image.new("RGB", (w, h), (36, 65, 47))
    px = img.load()
    # 黑板底色的明暗起伏与噪点。
    for y in range(h):
        for x in range(0, w, 2):
            shade = int(8 * ((x / w) - 0.5)) + rnd.randint(-6, 6)
            c = (36 + shade, 65 + shade, 47 + shade)
            px[x, y] = c
            if x + 1 < w:
                px[x + 1, y] = c
    draw = ImageDraw.Draw(img)
    font_title = ImageFont.truetype(FONT, 64)
    font = ImageFont.truetype(FONT, 52)
    y = 70
    for i, text in enumerate(lines):
        f = font_title if i == 0 else font
        chalk = tuple(rnd.randint(225, 245) for _ in range(3))
        x = 90 + (0 if i == 0 else rnd.randint(0, 40))
        draw.text((x, y), text, font=f, fill=chalk)
        y += 135 if i == 0 else 128
    img = img.rotate(rnd.uniform(-0.8, 0.8), resample=Image.BICUBIC, fillcolor=(36, 65, 47))
    img = img.filter(ImageFilter.GaussianBlur(0.7))
    img.save(OUT / f"{name}.png")
    (OUT / f"{name}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for i, (name, lines) in enumerate(BOARDS.items()):
        render(name, lines, seed=i + 1)
        print("生成", OUT / f"{name}.png")
