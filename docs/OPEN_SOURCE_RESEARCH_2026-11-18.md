# 同类开源项目调研与可落地建议（2026-11-18）

> 应用户要求，调研 GitHub 同类项目，寻找值得借鉴的新功能。所有 star 数为本次实测（shields.io），未编造。
> **本报告只是候选清单，本版未实施任何一条**——用户明确要求本轮「只恢复 UI，其他不变」，新增能力需单独确认。

## 0. 网络与数据来源

- `api.github.com/search/repositories` 返回 403（限流），`github.com/search` 返回 429（二次限流）。
- 改用仓库主页（HTTP 200）确认存在，用 shields.io `/github/stars/<repo>.json` 读取真实 star 数。
- `home-assistant/assist`、`OHF-voice/snowboy` 经查询为 repo not found，不作为推荐。`k2-fsa/sherpa-onnx` 页面 fetch 失败但 shields 拿到 star=16k。

## 1. 项目清单

### 类别1 · Android 语音助手 / 离线 STT/TTS

| 项目 | Star | 链接 | 对 Eta 的价值 |
|---|---|---|---|
| k2-fsa/sherpa-onnx | 16k | https://github.com/k2-fsa/sherpa-onnx | 端侧离线 ASR/TTS/VAD/Speaker Diarization，Android AAR。其 Silero VAD 可作为现有 RMS 端点的鲁棒升级（建议5）。不整体移植。 |
| DicioTeam/dicio-android | 1.6k | https://github.com/DicioTeam/dicio-android | 开源语音助手、类型化技能结果。`docs/VOICE_ASSISTANT_GEMINI_RESEARCH.md` 已列为参考并吸收，**勿重复借鉴**。 |
| alphacep/vosk-api | 15k | — | Eta 现用离线识别引擎，已在用。 |
| ggml-org/whisper.cpp | 55k | — | 端侧 STT 标杆，仅了解。 |
| rhasspy/rhasspy · wyoming | 2.8k / 416 | — | 本地优先语音生态/协议层，与单机架构不契合。 |

### 类别2 · LLM Agent 框架

| 项目 | Star | 链接 | 对 Eta 的价值 |
|---|---|---|---|
| langchain-ai/langgraph | 45k | https://github.com/langchain-ai/langgraph | 持久化状态图、节点级 checkpoint、interrupt/resume 人类在环。借鉴建议1、2。 |
| modelcontextprotocol/kotlin-sdk | 1.5k | https://github.com/modelcontextprotocol/kotlin-sdk | MCP 官方 Kotlin SDK 的 elicitation/sampling 规范。借鉴建议1。 |
| microsoft/autogen · crewAI | 62k / 61k | — | 多 Agent 编排。Eta 已有 `SubagentExecutor`（resume/角色/工具过程面板），**重复，不引入**。 |
| TEN-framework/TEN-Agent | 11k | — | 实时多模态管线。其端点/打断表达 Eta 已在 `DuplexVoiceProcessor` 实现，仅对照。 |

### 类别3 · Compose UI / 主题

| 项目 | Star | 链接 | 对 Eta 的价值 |
|---|---|---|---|
| android/nowinandroid | 22k | https://github.com/android/nowinandroid | 官方 Baseline Profile 自动化（建议3）。其 :core/:feature 模块化对单模块 Eta 属过度工程，不照搬。 |
| compose-miuix-ui/miuix | 1.7k | — | Eta 当前 UI 库。`AgentAppTheme` 已用 MiuixTheme + Monet + 9 种配色，**已是先进实现，无新增空间**。 |

### 类别4 · 天气 / 位置

| 项目 | Star | 链接 | 对 Eta 的价值 |
|---|---|---|---|
| open-meteo/open-meteo | 6.7k | https://github.com/open-meteo/open-meteo | Eta 仅用 current+daily，其 minutely_15 降水/AQI 未用（建议4）。 |
| komoot/photon | 3.2k | — | `WeatherPlaces.photon()` 已接入，无新增空间。 |

## 2. 可落地建议（候选，本版未实施）

### 建议1【高】MCP 执行中 elicitation（向用户要输入）

- **现状**：`agent/mcp/McpRunContext.kt:144-146` 明确返回 `MCP_INPUT_REQUIRED_UNSUPPORTED`；第三方 MCP server 一要求补充输入就失败。
- **落地**：`McpHttpClient.kt` 增加 `notifications/elicitation/request` 收发与 id 关联；`McpRunContext.kt` 接到现有审批通道 `AgentConfirmGuard`；复用 `AgentRunController.pause()/resume()` 与 `AgentRunCheckpointStore` 安全挂起；沿用 `AgentSensitiveToolPolicy` 的 mcp_ token 校验。
- **价值**：需要 OAuth/选参的 MCP server 不再"一问就崩"，复用现成审批 UI。

### 建议2【高】工具级 checkpoint，恢复后不重跑有副作用的工具

- **现状**：`runtime/AgentRunCheckpointRecorder.kt` 只合并文本增量；恢复走 `planning/TaskContinuationRuntime.kt` 把未完成步骤重置 pending 重跑。`model/AgentPromptBuilder.kt:115` 明确 completed 仅为历史报告。发消息/写文件类工具恢复后会重复执行。
- **落地**：`model/AgentLoop.kt` 的 `executeToolBatch()` 每个 outcome 落地即写 checkpoint；Recorder 增加 tool-result 结构化边界；PromptBuilder 区分"本 run 已提交回执（可复用）"与"旧 run 历史报告（仅参考）"。
- **价值**：断网/杀进程恢复不再重跑已成功的写操作。参考 LangGraph durable execution。

### 建议3【高】用 Macrobenchmark 自动生成 Baseline Profile

- **现状**：`app/src/main/baseline-prof.txt` 仅 11 行，自述"人工整理、无法证明加速"，覆盖不到 Compose 首帧/会话列表滚动/语音面板展开。
- **落地**：新增 `:baselineprofile` 模块，`StartupMode.COLD` 冷启动+唤醒+滚动场景；接入 `androidx.baselineprofile` 插件；替换 baseline-prof.txt；CI 加 `:app:generateReleaseBaselineProfile`。
- **价值**：从"手工猜测"变成"实测生成、可回归验证"，优化语音唤醒首帧与会话滚动。参考 Now in Android。

### 建议4【中高】天气加"分钟级降水/AQI"语音意图

- **现状**：`OpenMeteoWeatherService.kt:100-101` 只请求 current+daily；`DailyIntentRouter.kt:23` 只认今天/明天/三天。
- **落地**：forecast 请求加 `minutely_15`；Codec 解析新字段；Router 加"两小时内会下雨吗/空气质量"意图；Briefing 生成播报。
- **价值**：从"每日播报"升级为"出门决策级问答"，复用现有双源容错/限流/stale/城市选择，改 4 个文件，零新依赖。

### 建议5【可选】Silero VAD 融合自研 RMS 端点

- **现状**：`agent/voice/SemanticVoiceEndpoint.kt:3` 明确"uses rules rather than a neural model"；`NativeVoiceActivityDetector` 是自适应 RMS。低信噪比下端点误判、尾句截断是已知风险。
- **落地**：引入 `sherpa-onnx-android` AAR；`NativeVoiceActivityDetector.kt` 加"Silero VAD 模式"开关，NN speechProb 融合进 Observation，保留 RMS 回退；`DuplexVoiceProcessor.kt` 接口不变。
- **价值**：噪声环境端点更准，减少误插话与尾句截断。代价是评估包体/内存增量（现有 Vosk 模型已 44MB），故列可选。

## 3. 结论

Eta 在语音管线、MCP schema 压缩、虚拟屏、会话/多Agent/审批/断点续跑方面已相当完整；Miuix 主题、Photon+Open-Meteo 天气、Vosk 离线识别均已到位，多数知识库条目近乎重复实现。真正缺口集中在 **MCP elicitation、工具级 durable checkpoint、Baseline Profile 自动化、天气分钟级意图**，可选神经 VAD。全部定位到具体文件，待用户确认后再逐项实施。
