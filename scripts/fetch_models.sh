#!/usr/bin/env bash
# 下载端侧 OCR 模型（PaddleOCR PP-OCRv4 mobile 的 ONNX 版本，Apache-2.0）到 core/ondevice 的 assets。
#
# 为什么不放进 git：模型约 16 MB，二进制文件会让仓库迅速膨胀；这里固定下载地址与 SHA-256，
# 任何人重新下载都得到完全相同的文件。下载慢时可设置代理，例如：
#   HTTPS_PROXY=http://127.0.0.1:7890 scripts/fetch_models.sh
#
# 来源：
#   检测 / 识别模型：huggingface.co/SWHL/RapidOCR（RapidOCR 项目把 PaddleOCR 官方模型转为 ONNX，Apache-2.0）
#   字典：github.com/PaddlePaddle/PaddleOCR release/2.7 ppocr/utils/ppocr_keys_v1.txt（Apache-2.0）
set -euo pipefail

DEST="$(cd "$(dirname "$0")/.." && pwd)/core/ondevice/src/main/assets/ocr"
mkdir -p "$DEST"

fetch() {
  local name="$1" url="$2" sha="$3"
  local out="$DEST/$name"
  if [[ -f "$out" ]] && [[ "$(shasum -a 256 "$out" | cut -d' ' -f1)" == "$sha" ]]; then
    echo "已存在：$name"
    return
  fi
  echo "下载：$name"
  curl -fL --retry 5 --retry-delay 3 -C - -o "$out.part" "$url"
  local got
  got="$(shasum -a 256 "$out.part" | cut -d' ' -f1)"
  if [[ "$got" != "$sha" ]]; then
    echo "校验失败：$name（期望 $sha，实际 $got）" >&2
    rm -f "$out.part"
    exit 1
  fi
  mv "$out.part" "$out"
}

HF=https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv4
fetch det.onnx  "$HF/ch_PP-OCRv4_det_infer.onnx" d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9
fetch rec.onnx  "$HF/ch_PP-OCRv4_rec_infer.onnx" 48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b
fetch keys.txt  "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/release/2.7/ppocr/utils/ppocr_keys_v1.txt" \
  28b2362ad4ab2dc38769aa72feb535e3a9ddb3fd2a7585a05920e6393b1dc7f7

echo "完成：$DEST"
