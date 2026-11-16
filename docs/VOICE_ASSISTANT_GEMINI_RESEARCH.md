# 语音助手浮窗改造与开源项目调研

本记录对应本轮工作树改动：将系统助理唤起的 Eta 浮窗改为 Gemini 风格的全屏语音界面，并把两轮 GitHub 调研中适合本轮落地的建议落到界面与行为。

## 本轮实现

- 全屏低饱和蓝紫渐变背景，按浅色／深色主题分别取色；保留原有边缘入场光效。
- 四色底部流动声波：蓝 `#4285F4`、红 `#EA4335`、黄 `#FBBC04`、绿 `#34A853`。优先复用原有 `RuntimeShader` 与纹理数值轨道；软件画布、资源加载失败或 shader 绘制异常时自动切换为 Compose Canvas 四色路径，系统动画关闭时显示静态波形。
- 胶囊输入框统一为 60dp 高、32dp 圆角；语音聆听态也使用完整胶囊，左侧四色星光、键盘切换、实时识别文本／阶段提示、右侧完成录音按钮。
- 顶部状态区映射真实语音／运行状态：连接麦克风、聆听、识别、思考、执行工具、朗读、完成、失败；状态文字使用语义色并通过 `liveRegion` 播报。
- 顶部增加可展开的屏幕来源胶囊，显示前台应用包名、采集时间和“唤起时快照、非实时状态”说明；底部增加“使用当前屏幕上下文”开关，关闭后本轮请求不附加快照文本与截图。
- 增加关闭与进入完整对话按钮；原拖拽把手、上拉接管、下拉关闭、建议胶囊、IME Insets 与按住说话手势保持原有工作方式。

## 第一轮调研：成熟助手与产品表达

| 项目 | 已核验证据 | 对 Eta 的采用 |
| --- | --- | --- |
| [RikkaHub](https://github.com/rikkahub/rikkahub) | Kotlin/Compose、Material 3 Expressive、动态色／AMOLED；[Theme.kt](https://raw.githubusercontent.com/rikkahub/rikkahub/master/app/src/main/java/me/rerere/rikkahub/ui/theme/Theme.kt)、[Color.kt](https://raw.githubusercontent.com/rikkahub/rikkahub/master/app/src/main/java/me/rerere/rikkahub/ui/theme/Color.kt) 展示表面／悬浮工具栏分层 | 氛围渐变只放背景，正文、输入和结果区维持稳定表面色 |
| [PocketPal AI](https://github.com/a-ghorbani/pocketpal-ai) | 本地 GGUF、Whisper ASR、TTS、Voice mode、Pals；[颜色 token](https://raw.githubusercontent.com/a-ghorbani/pocketpal-ai/main/src/theme/tokens/colors.ts) 给 thinking/ready/download 等语义色 | 聆听／转写／思考／执行／朗读状态映射到文字与声波运动 |
| [01](https://github.com/openinterpreter/01) 与 [01 App](https://github.com/openinterpreter/01-app) | 移动端文档确认 push-to-talk/VAD、Wearable 全屏按钮、扫码连接电脑 | 强输入按钮和清晰聆听／键盘切换；沿用 Eta 已有按住说话 |
| [goose](https://github.com/aaif-goose/goose) | Recipe 将目标、工具、模型、参数打包，`activities` 为可点击任务气泡 | 后续建议为已有 Skills／预设增加轻量参数入口，不重复建设任务系统 |
| [Leon](https://github.com/leon-ai/leon/tree/develop) | Aurora 视觉回答、Vibes 环境场景、计划执行轨迹 | 借鉴克制氛围和结构化结果表达；本轮只采用低饱和渐变 |
| [OmniParser](https://github.com/microsoft/OmniParser) | 截图→带编号 UI 元素＋结构化文本；Demo 使用 CUDA | 使用 Eta 已有 Assist 快照数据，新增来源／时间／时效展示，不移植视觉模型 |
| [Dicio](https://github.com/DicioTeam/dicio-android) | 语音＋图形双反馈、计时／天气／计算等类型化技能、Vosk 离线 ASR | 后续可补少量类型化结果卡；本轮不替换 Eta 通用 Agent／ASR |

Eta 本身已有 Runtime、屏幕读取、ASR/TTS、多供应商、自动播报、按住说话、建议胶囊、可展开工作时间线、Skills、MCP、预设和自动任务；上述能力不计为新增。

## 第二轮调研：语音管线与实时状态表达

| 项目 | 已核验证据 | 本轮可落地部分 |
| --- | --- | --- |
| [TEN-Agent](https://github.com/TEN-framework/TEN-Agent) / [ten-vad](https://github.com/TEN-framework/ten-vad) / [ten-turn-detection](https://github.com/TEN-framework/ten-turn-detection) | RTC/WebSocket、Memory/VAD/Turn Detection、转录与说话人分离；VAD 提供 Android JNI；Turn Detection 输出 finished/wait/unfinished | 先呈现管线状态；语义判停、本地 VAD 归入需音频管线改造的后续项 |
| [LiveKit Agents](https://github.com/livekit/agents) / [agent-starter-android](https://github.com/livekit-examples/agent-starter-android) / [components-android](https://github.com/livekit/components-android) | server participant、STT/LLM/TTS 编排、AgentSession；Android Compose VoiceAssistantScreen 的 agentState、音频 bar、控制条、chat log 淡出 | 借状态语义、等待态、控制条与状态色；不引入 Room/token/WebRTC |
| [Pipecat](https://github.com/pipecat-ai/pipecat) / [pipecat-ui](https://raw.githubusercontent.com/pipecat-ai/pipecat-ui/main/README.md) | voice-first pipeline、Whisker/Tail；UI 的 connect 状态、bar/radial/wave visualizer、duplex timeline、karaoke transcript、function-call cards、data-state 语义 token、TTFB/TTFA 指标 | 本轮采用状态条、实时识别文本与语义色；指标抽屉、双轨时间线、word-level caption 留待事件接口扩展 |
| [OpenAI Realtime Agents](https://github.com/openai/openai-realtime-agents) / [Realtime Console](https://github.com/openai/openai-realtime-console) | PTT/VAD、audio playback/logs、tool call/agent handoff transcript、guardrail IN_PROGRESS/PASS/FAIL、事件日志 | 后续可将现有工具时间线升级为可展开 tool call／breadcrumb；不引入 WebRTC 与 OpenAI 协议 |
| [Kyutai Moshi](https://github.com/kyutai-labs/moshi) / [delayed-streams-modeling](https://github.com/kyutai-labs/delayed-streams-modeling) / [Unmute](https://github.com/kyutai-labs/unmute) | full-duplex、inner monologue、word-level timestamps、subtitle/dev overlay、流式 STT→LLM→TTS | 实时双工、逐词高亮需 GPU/模型服务；本轮只保留实时识别文本和状态表达 |
| [Vapi Android SDK](https://github.com/VapiAI/client-sdk-android) | CallDidStart/End、Transcript、FunctionCall、SpeechUpdate、ConversationUpdate、Error 事件与网络质量 | 本轮从 Eta 现有回调派生会话状态；call-start progress／网络质量为后续轻量事件接口 |
| [GetStream/gemini-android](https://github.com/GetStream/gemini-android) | 100% Compose、流式聊天、实时事件、Room/DataStore、Baseline Profiles | 后续可做流式结果卡与性能基线；本轮不引入 Stream Chat |
| [NadeemIqbal/prompt-bar](https://github.com/NadeemIqbal/prompt-bar) | slash 命令、@mention、attachment chip、Send→Sending→Stop、模板、token 计数 | 后续建议胶囊可升级为快捷 composer；本轮保持现有胶囊与输入状态机 |
| [ahmedeltaher/Agnentic Voice AI SDK](https://github.com/ahmedeltaher/Agnentic-Voice-AI-SDK) | LiveCaptionBanner、VoiceStatusIndicator、WaveformVisualizer、PTT/HandsFree、waveform/permission gate | 本轮落实 LiveCaption 式识别文本、状态头和波形；权限门保留原系统权限流程 |
| [souravanand001/ai-assistant-android](https://github.com/souravanand001/ai-assistant-android) | floating orb、IDLE/LISTENING/PROCESSING/BOT_SPEAKING、屏幕快照来源、隐私暂停 | 本轮落实四态语义与快照来源；连续截屏、barge-in 留后续 |
| [zafiro](https://github.com/niki914/zafiro) | context usage、branch/rewind、human-in-the-loop 审批卡、当前步骤指针 | 后续可把已有工作时间线扩展为审批／压缩入口；本轮不新增第二套过程卡 |
| [opendroid](https://github.com/JMAN730/opendroid) | self-planning、plan card、provider latency、pulsing orb、深色霓虹主题 | 本轮采用语义状态色与低频波形；强主题与 Monet 冲突，计划卡留后续 |

## 后续优先级

1. **P1**：把 Runtime 的 tool/MCP/Skill start/end/result/error 事件接入已有折叠工作时间线，增加一句当前动作、可展开参数／结果、复制／重试、耗时与网络质量。
2. **P1**：建议胶囊关联当前屏幕与能力，提供少量参数化快捷任务；复用现有预设、Skills 和工具范围。
3. **P1**：结果卡增加计时、日程、天气、计算、文件等类型化 Compose 模板与一个明确下一动作。
4. **P2**：word-level caption、duplex timeline、真实 barge-in、语义 Turn Detection、本地 VAD、离线 ASR/TTS、全双工模型服务。需要新增音频／模型／RTC 管线并实测中文、延迟、耗电和内存。
5. **P2**：context usage/compress、branch/rewind、human-in-the-loop 审批、Baseline Profile 与低端机性能降级。

## 验证范围

- 已安装项目声明的 JDK 25、Android SDK 37、Build Tools 与 Gradle 9.7.1；`local.properties` 指向本地 SDK。
- 构建命令使用项目自己的 Gradle 配置执行；Kotlin 编译与现有 `EtaVoicePanelTouchTest` 的结果以控制台输出为准。
- 设备上的 Overlay、系统助理、厂商 ROM、RuntimeShader 硬件加速与 TalkBack 表现需在 Android 13+ 实机复核。
