# ADR 0003：端侧 OCR 采用 PaddleOCR 转 ONNX + ONNX Runtime

- 状态：提议
- 日期：2026-11-21
- 关联需求：FR-NOTE-01、NFR-RES-01、NFR-PRIV-01、C1

## 背景

板书识别要求中文与数学符号、可离线、不依赖 Google 服务（排除 ML Kit），模型总体积 ≤ 150 MB。

## 备选

| 方案 | 中文效果 | 体积 | 许可证 | 无 GMS | 结论 |
| --- | --- | --- | --- | --- | --- |
| PaddleOCR PP-OCRv4 mobile → ONNX + ONNX Runtime Android | 好，含竖排与公式符号 | 检测 + 识别约 15 MB | Apache-2.0（模型与 Runtime 均为 MIT/Apache） | 是 | **采用** |
| Google ML Kit 文本识别（中文） | 好 | 随 Play 服务下载 | 专有 | 否 | 违反 C1 |
| Tesseract（tess-two） | 中文与手写板书差 | 中文包约 50 MB | Apache-2.0 | 是 | 效果不达标 |
| 纯云端 OCR（通义千问 VL） | 最好 | 0 | 商用 API | 是 | 作为路由回退，不作为主方案 |

## 决定

端侧使用 PP-OCRv4 mobile（检测 + 方向分类 + 识别）的 ONNX 版本，通过 ONNX Runtime Android 推理，封装为 `core:ondevice` 中的 `OcrBackend : ModelBackend`。置信度低于阈值时由 `InferenceRouter` 回退到云端视觉模型（需用户允许上传）。

## 影响

- 需在 `docs/open-source.md` 登记 PaddleOCR 模型与 ONNX Runtime。
- 首次使用时从 assets 解压模型，冷启动不加载，按需懒加载。
