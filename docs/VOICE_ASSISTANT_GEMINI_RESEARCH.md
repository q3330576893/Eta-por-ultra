# 底部胶囊与语音助手实现对照

本轮按用户实测反馈，将旧的全屏渐变界面改为透明底部胶囊，并在既有 Agent Runtime 上补齐语音与任务反馈。系统助理唤醒直接走 VoiceInteractionSession；移除中转 Activity 和 Eta 的 Activity fallback。发布包的设备行为以实际 ROM 为准，当前维护环境没有连接 Android 设备。

## 已落地

| 方向 | 实现 |
| --- | --- |
| 胶囊 | TYPE_APPLICATION_OVERLAY、WRAP_CONTENT、底部居中、最大600dp，无全屏背景／遮罩；回复和选项有高度上限，键盘留白从触摸区域排除，上拉进入完整对话、下拉关闭 |
| 默认语音 | AUTO／系统优先设备识别服务，启动失败一次回退到内置中文 Vosk；本机路径跳过云凭据。系统 TTS 优先选择已安装离线音色 |
| 连续对话 | 可见窗口内显式开启；16kHz PCM16／20ms帧持续采集，跨轮保留 Model／Recognizer；自适应 RMS VAD 与文字后缀端点提示；60秒单句、120秒空闲上限 |
| 插话 | 仅实际 AEC 启用或已确认耳机路由，加持续语音及非空识别预览；扬声器且 AEC 失效时隔离回声，恢复后重置证据 |
| 字幕 | Vosk result／partial_result 的真实逐词偏移与可选置信度；时间相对识别器本轮。系统 TTS 使用 onRangeStart；PCM 分段采用实际 playback head，缺少边界时显示未知 |
| 时间线 | 有界32条用户终句、插话、播报开始／结束事件，使用单调时间；采样累计时间独立保存，不把两者混作墙钟 |
| 日常结果 | 两个入口均支持时间、日期、计算、今日日程、城市天气，闹钟与计时器交系统确认；复制、分享、重试、刷新与来源操作按结果类型显示 |
| 文件模板 | 继承 Markdown 文件卡、工作区预览、打开、分享与长按复制；未新增任意工具摘要到文件路径的猜测解析 |
| 可观测性 | 真实请求的轮次／attempt、HTTP响应头、首非空增量、状态码、重试，以及工具／MCP／Skill／子智能体摘要；每类最多60条，参数／结果预览有界，缺失值未知 |
| 计划与经验 | 六种步骤状态，本轮成功工具回执与完成说明；应用／版本精确匹配、显式选卡、最多3卡／4000字只读参考；实际工具记录可保存待核验草稿 |
| 审批与恢复 | owner／run／token 精确匹配、持久化发件箱和ACK；人工结束要求重新读屏。中断记录跨多次启动保留，用户显式继续，旧计划未完成步骤重置pending且旧回执保持历史身份 |
| 屏幕上下文 | 来源、采集时间、截图预览、重新采集与开关；系统 AssistStructure 补全前台应用身份 |
| 设置与性能 | LLM服务商置顶；低内存设备跳过波形Shader并限频，软件画布Canvas回退、系统动画关闭静态呈现；提供人工整理的启动Baseline Profile规则 |

本地大模型中心及 LiteRT 依赖按用户要求移除。Vosk 是语音识别模型，HTTP／Ollama 服务配置继续保留。捐赠页面和专用支付资源保持删除。

## 调研来源与采用范围

| 项目 | 采用方向 |
| --- | --- |
| [RikkaHub](https://github.com/rikkahub/rikkahub)、[PocketPal](https://github.com/a-ghorbani/pocketpal-ai)、[Dicio](https://github.com/DicioTeam/dicio-android) | 可读表面、语义状态、免费本机识别与类型化结果 |
| [TEN-Agent](https://github.com/TEN-framework/TEN-Agent)、[LiveKit Agents](https://github.com/livekit/agents)、[Pipecat](https://github.com/pipecat-ai/pipecat) | 管线状态、端点提示、插话与可观测指标的产品表达 |
| [OpenAI Realtime Agents](https://github.com/openai/openai-realtime-agents)、[Moshi](https://github.com/kyutai-labs/moshi)、[Unmute](https://github.com/kyutai-labs/unmute) | 流式转录、播放生命周期与时间线表达 |
| [Vapi Android](https://github.com/VapiAI/client-sdk-android)、[GetStream Gemini Android](https://github.com/GetStream/gemini-android) | 事件驱动状态与启动性能配置 |
| [prompt-bar](https://github.com/NadeemIqbal/prompt-bar)、[zafiro](https://github.com/niki914/zafiro)、[opendroid](https://github.com/JMAN730/opendroid) | 快捷输入、运行详情、计划与人工交接 |
| [goose](https://github.com/aaif-goose/goose)、[01](https://github.com/openinterpreter/01)、[OmniParser](https://github.com/microsoft/OmniParser) | 可复用经验、按住说话与屏幕来源表达 |

以上为设计参考；各项目本身的 RTC、服务器或模型没有被整体移植。Eta 已有上下文压缩、分支／回溯、MCP、Skills、子智能体、终端、虚拟屏和自动任务继续复用，不列作本轮原创。

## 边界与验证

持续语音是本机 STT → 用户配置模型 → TTS 管线。端点检测为解释明确的 RMS／文字后缀规则；神经语义端点、全双工端到端模型服务、WebRTC房间、说话人分离与连续后台唤醒不属于本版实现。启动Profile规则属于静态配置；设备启动时间、帧率、耗电、中文麦克风识别率、AEC效果和OEM唤醒需实机测量。

实际测试、跳过项和发布检查记录见 [本轮验证](ASSISTANT_VALIDATION_2026-11-17.md)。
