# 灵犀学伴

手机 + 手表协同的学习智能体。拍板书、录课，自动整理成个人知识图谱；在排队、通勤这类碎片时间，通过手表推送 30 秒微复习。

赛道：AI 赋能学习生产力。

## 核心创新

1. **碎片时间情境引擎**：融合心率、活动状态、前台应用类别与课表，判断「现在是否适合学、学多少」，每次判断都附带可解释的依据。见 `context-engine`。
2. **课堂内容到知识图谱**：OCR + 语音识别 + 大模型抽取知识点与关系，并做严格校验后写入图谱。见 `server/app/extraction.py`。
3. **端云协同隐私路由**：按任务类型、网络、电量、发热、隐私敏感度决定端侧小模型或云端大模型，失败自动切换。见 `core:ai`。

## 目录

```
core/model       领域模型
core/ai          端云路由与推理入口
core/review      FSRS 间隔重复
core/sync        手机与手表通信（消息协议、主备双链路、WebSocket 中继客户端）
context-engine   碎片时间情境引擎
app              手机端（Android, Jetpack Compose）
wear             手表端（Wear OS）
server           云端网关（Python FastAPI）
docs             架构、决策记录、开源组件清单
```

## 快速开始

在 Mac（Apple Silicon）上开发，需要 JDK 17、Android Studio 和 uv：

```bash
brew install uv openjdk@17
brew install --cask android-studio
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
```

Android SDK 装在 `~/Library/Android/sdk`，模拟器镜像：手机 `system-images;android-36;google_apis_playstore;arm64-v8a`，
手表 `system-images;android-36;android-wear-signed;arm64-v8a`。

```bash
# 算法模块测试（无需 Android SDK）
./gradlew test

# 手机端与手表端：用 Android Studio 打开本目录，或在配置了 ANDROID_HOME 的环境中
./gradlew :app:assembleDebug :wear:assembleDebug

# 云端网关
cd server
cp .env.example .env   # 填写大模型 API Key
uv sync
uv run uvicorn app.main:app --reload
```

没有检测到 Android SDK 时，Gradle 会自动跳过 `app` 与 `wear` 模块。

### 在模拟器上联调手机和手表

```bash
cd server && uv run uvicorn app.main:app --port 8000   # 先启动网关，中继在 /v1/relay
scripts/emulator.sh watch                               # 启动手表模拟器
scripts/emulator.sh phone                               # 启动手机模拟器
./gradlew :wear:installDebug :app:installDebug
```

两端默认通过网关中继通信（模拟器访问 Mac 本机的地址是 `10.0.2.2`），不需要 Google 账号，也不依赖 Google Play 服务。
真机调试时用 `-PgatewayUrl=http://<Mac 的局域网 IP>:8000` 重新安装。

**本机开着代理时请用 `scripts/emulator.sh` 启动模拟器。** 模拟器会继承终端里的 `http_proxy` 等变量，
把模拟器内的连接都转给代理，导致手机模拟器连不上 `10.0.2.2` 上的网关，甚至启动卡死。脚本会去掉这些变量、关闭崩溃上报弹窗后再启动。

## 当前进度

P0（立项与原型）进行中：核心算法与测试、手机端原型、手表端微复习原型、网关的对话与知识抽取接口、手机与手表经中继通信已完成，并在 Mac 模拟器上跑通。
完整路线见 `docs/architecture.md`。

## 开源说明

使用的开源组件与许可证见 [docs/open-source.md](docs/open-source.md)。
