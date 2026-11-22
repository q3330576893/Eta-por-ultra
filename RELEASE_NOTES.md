## Eta Ultra · 按模型请求提示词 Hook（开发版，2026-11-21）

- 在服务商的模型编辑页独立配置请求提示词，显式启用后选择 System、User 或双通道。可使用原创研究模板再自行编辑，默认关闭。
- OpenAI Chat、Responses、Anthropic Messages 和 Gemini Interactions 在最终请求前统一投影，图片和工具调用链保留，注入文本不写回聊天记录；已启用策略的消息字段在自定义请求体合并后保留。
- 主模型、指定子模型与模型对比分别使用对应模型的设置。摘要、语音修正、回复改写和自动复盘不叠加普通聊天 Hook；小布文本通道使用 User 模式。
- 既有请求快照增加通道、短哈希和估计 token，省略 Hook 正文；重复请求保持同一投影，取消和技术错误重试继续沿用现有运行控制。
- 同轮修复多服务商同名模型的授权串用、编辑弹窗重建丢未保存草稿、子代理显式模型失效静默回落主模型三项问题，均为真实回归覆盖；子代理授权绑定服务商/本地模型条目，编辑草稿随 Compose saveable 状态恢复并按条目隔离，失效显式选择报 `SUBAGENT_CONFIG_ERROR` 不回落。
- 天气与先前功能精简保留。完整本地验收 2,517 项，0 失败、0 错误、71 项宿主平台跳过（2,446 项实际通过）；47 个必过套件与 72 个关键场景无跳过通过，Lint 与 Debug 构建通过。原证书签名的 ARM64 非调试预览 `3.5.6.15+model-hook` 已通过 APK 内容、身份和签名核验。此为本地预览，顶部公开下载元数据继续标识已发布版本；真机交互及远端模型开关对照待实测。

### English

This development change adds explicit per-model System/User/Both request hooks, independently persisted model settings and a user-editable research template. The four HTTP providers share a request-only projection that preserves multimodal and signed tool history, while dedicated summary/transcription/rewrite/review requests retain their own instructions. Snapshots expose channel/hash/cost metadata without retaining hook text. The same round fixed same-API-name grant misbinding across providers, editor draft loss on recreation, and silent primary-model fallback when an explicitly selected subagent model becomes unavailable, each with real regressions. Local validation discovered 2,517 tests with zero failures/errors and 71 host-platform skips (2,446 actually passed); 47 required suites and 72 mandatory cases passed without skips, as did Lint and Debug assembly. The original-certificate ARM64 nondebug Release preview is `3.5.6.15+model-hook`; signature, identity and packaged-content checks passed. Device interaction and remote-model on/off experiments remain unverified. Public download metadata continues to identify the release actually published.

## Eta Ultra · 实验逆向分析工具与可选技能（2026-11-21）

- 可选 APK 工具档案更新为 revision 2，新增 eta-reverse 的版本探测、静态概览和 JADX Java 反编译；继续复用已固定 SHA 校验的外部引擎与 JDK25，不增加 Python/Node 运行依赖。
- 按样本工作区创建输入快照、真实进程及有界工件，固定 headless CLI；输出 SHA、退出码和报告，部分／超时／空源码／超限保持不完整或失败。
- 可选 Skill ZIP 随本版发布，用户在 Skills 页面导入。客户端和 APK 分析档案都需更新；不添加默认启用的角色、不更改模型或终端权限。
- 继续保留完整天气、普通任务、长期记忆和星驰四预设；之前移除的五功能保持删除。手机 ARM64 的实际安装／交互尚未验收，现有 apktool build 限制保留。
- 本轮验收：客户端完整回归 2,429 项、0 失败、0 错误、69 项宿主平台跳过；严格必过用例、Lint 与 Debug 构建通过。另有 11 项后端契约和真实 JADX 自建 APK 烟测通过；Android ARM64 实机执行尚未进行。正式包、Skill 和 CI 样本证据按发布附件核验。

### English

Experimental, optional APK analysis adds structured probe/inspection/JADX decompilation, source fingerprints, real process outcomes and owned reports. Its Skill is an explicit user import; the optional profile uses existing pinned engines and JDK25, with rollback-aware marker publication. Weather and existing permission boundaries remain. Host contracts and a real self-built-APK smoke passed; Android ARM64 device installation/execution is still unverified. Final release evidence is recorded after execution.

## 以下为此前版本历史记录

## Eta Ultra · 功能精简，天气完整保留（2026-11-21）

- 移除本地备忘、通知栏快捷指令、文档知识库、NFC 任务和自动化规则试跑，清理相关入口、工具、组件及资源。
- 天气全部保留：预报、定位、常用地点、设置、缓存刷新与桌面组件；天气组件使用独立设置入口。纯文字分享直接进入聊天草稿。
- 长期记忆、普通附件、会话复盘、定时与非 NFC 事件任务、星驰四种手动预设继续保留。
- 旧 NFC 任务及排队记录在调度前退休；旧备忘／磁贴偏好与文档库快照在导出、解压暂存、应用旧暂存三条路径均排除。外部原文件和普通附件授权保持原状；旧知识库工具历史仍按敏感内容脱敏。
- 验证：完整本地回归 2,422 项发现、0 失败、0 错误、69 项宿主平台跳过；严格必过用例、Lint 与 Debug 构建通过。正式包与公开附件另按对应源码、版本、签名和哈希验收。

### English

Local memos, command tiles, the document library, NFC-triggered tasks and rule dry runs are removed. Forecasts, current-location resolution, saved places, weather settings, cache/refresh and the weather widget remain; the widget has a dedicated settings Activity. Ordinary attachments, memory, reviews, non-NFC scheduling and the four Xingchi presets remain. Legacy task queues and full-backup paths cannot reactivate retired features. External originals and shared attachment grants are preserved. Validation results are recorded separately after execution.

## 以下为此前版本的历史记录（不表示当前仍提供已移除功能）

## Eta Ultra · 星驰多客户端兼容预设（2026-11-21）

- 当前源码为精确星驰 HTTPS/API Key 连接新增 OpenCode、WorkBuddy、Codex、DeepSeek Harness 手动选择器，按连接保存并从请求头恢复，不新增全局开关或数据库字段。
- 切换仅替换并去重 User-Agent，URL/协议保持独立；原“补齐”继续补齐 /v1、Responses 和缺失标识，保留已有非空自定义值、密钥及其他字段。
- 2026-11-20 12:14–12:26（UTC+8）四个最终串均真实完成文本/SSE、固定函数调用和结果回放；默认 Eta 与三个无版本候选被拒。一次 WorkBuddy 请求虽收到 OK，仍以 response.failed 失败；该失败保留，单次受控复测后成功。
- 这些是带 EtaUltra 归属的 HTTP 兼容预设；WorkBuddy/DSH 的 1.0.0 是兼容候选串，不是官方当前版本声明，也没有验证四种原生客户端的完整等价性。
- 2026-11-21 00:23–00:25（UTC+8）交付复验四项全部成功：目录、流式文本、固定函数和结果回放均完成。全量重新执行 2,450 项测试，0 失败、0 错误、70 项平台跳过；Lint 与 Debug 构建通过。两个同主机连接经 Repository 保存、数据库重开与自定义值持久化的回归已加入发布门禁。真机界面生命周期测试未执行，正式 APK 的版本、签名和校验值以随包元数据及发布结果为准。

### English — manual client presets

- Exact Xingchi HTTPS/API-key connections gain manual OpenCode, WorkBuddy, Codex and DeepSeek Harness presets, saved per connection and inferred from headers without a database migration.
- An explicit switch replaces and deduplicates only User-Agent. URL/protocol remain independently editable; initial completion still fills /v1 and Responses while preserving a nonblank custom identity and unrelated fields.
- All four exact ETA HTTP configurations passed live text/SSE and fixed-function/result replay on November 20, 2026, 12:14–12:26 UTC+8. Default Eta and three versionless candidates were rejected. One WorkBuddy attempt ended with response.failed despite receiving OK; the failure remains recorded and one bounded repeat passed.
- WorkBuddy/DSH 1.0.0 are compatibility candidates, not official current-version claims. All four completed another live catalog/text/SSE/function/replay check on November 21, 2026, 00:23–00:25 UTC+8. A fresh local run discovered 2,450 tests: zero failures/errors, 70 platform skips; Lint and Debug assembly passed. Same-host connection isolation and database-reopen regressions are release gates. Native-client binaries and Android device lifecycle checks were not executed; shipped version/signature/checksum are recorded by release metadata.

## Eta Ultra · 星驰兼容配置与分组诊断（此前记录）

本补丁在 3.5.6.11 已公开发布后独立实现，保留该版全部功能。

## 星驰 API 兼容配置与 403 诊断

- 在星驰 HTTPS API Key 连接中，可手动点「补齐星驰兼容配置」，补齐 `/v1`、Responses 与可编辑的客户端标识；保留已有标识、密钥和其他自定义字段，其他服务商默认不变。
- 模型目录和聊天共用请求头；Responses 完整回放上下文与工具结果，不使用服务端存储链。
- 明确区分 `client_not_allowed` 和「Key 未分配分组」，两类错误均不盲目重试。
- **2026-11-20 新 Key 实测：模型目录返回 200；推理默认 Eta 标识返回 403 `client_not_allowed`，应用兼容标识后，真实流式回答及函数调用/结果回传均返回 200。旧 Key 的未分组错误是独立权限问题。**

[实测、配置步骤与开源来源](docs/XINGCHI_COMPATIBILITY_2026-11-20.md)

### English

## Xingchi compatibility and precise access diagnostics

- The exact Xingchi HTTPS/API-key connection exposes an explicit compatibility action: fill `/v1`, select Responses and add an editable client header when absent. Existing client selection, credentials and other fields remain; unrelated providers keep their defaults.
- Catalog and inference requests share provider headers. Responses replays context and tool results without a stored server-side chain.
- Client rejection and an unassigned key group are distinct, non-retrying failures.
- **Live retest on November 20, 2026 with the new key: the model catalog returns 200; inference with the default Eta identity returns 403 `client_not_allowed`, while the compatibility identity succeeds with real streamed text and a function-call/result round trip. The old key had a separate unassigned-group denial.**

[Captured behavior, configuration and source research](docs/XINGCHI_COMPATIBILITY_2026-11-20.md)

## Eta Ultra 3.5.6 · 复制、审批与功能接线

## 3.5.6：聊天复制、复盘审批与可用入口

本轮源码接线与修复如下；已发布版本、提交和校验值以顶部正式下载区为准。

- **自己的消息也能复制**：回复生成中仍可复制用户气泡正文；编辑、删除、分支继续遵守运行期限制。复制使用气泡实际显示的文字，跳过内部注入块。
- **复盘卡可保存、可跳过**：候选绑定原会话和唯一 token，批准在本地原子写入，重试避免重复；丢弃或跳过直接终结，不启动模型或续跑旧任务。返回前台会清理过期内容。
- **通知栏原地反馈**：在「工具与扩展 → 通知栏快捷指令」配置 1–4 条单一指令与顺序；展开面板时主标签显示结果，副标题显示下一条，结果短时保留。支持在系统组件选择器添加缓存天气与待办备忘组件。
- **本地备忘收件箱**：分享纯文字或链接先预览，可存本机或放入聊天草稿；支持完成、恢复、删除和桌面勾选。原备忘自动迁移，最多保留最近 50 条、每条 8000 字，超长明确提示。
- **同会话模型对比与采样**：会话选项可选至多三个模型并排查看，独立显示流式结果与失败，支持取消、复制和采纳；对比关闭工具执行。采样按会话保存、重置并传入请求，供应商不支持的参数明确展示。额外模型会增加供应商调用费用。
- **持久文档知识库**：管理页可建库、导入 UTF-8 文件或文字、绑定当前会话、固定来源和删除；模型仅检索本会话已选资料。回答引用包含真实版本与行号，点击后重新验权打开原文。采用本地关键词检索，未声称向量语义检索。
- **真实 NFC 与规则试跑**：NFC 通过前台主动读卡绑定精确标签，系统分发只打开提示页；未承诺锁屏后台自动触发。规则编辑页逐条件解释 true/false/unknown，试跑只读取状态；保存和执行分开。
- **定位与运行证据一致**：屏幕定位匹配最新 UI 节点文字与描述，缺树明确报告；未集成视觉 OCR/OmniParser。节点变化只表示观察证据，不能直接等同任务成功。已用灵屏的运行在撤权后停止，不将旧坐标投到主屏。
- 保持旧版设置渐变主标、图标与分类；保留默认不截屏，Token 统计、运行详情、本地大模型中心、捐赠继续移除；本轮未增加日落提醒。界面沿用现有简体中文配置。

[本轮验证记录](docs/VALIDATION_2026-11-19.md) · [更新记录](CHANGELOG.md)

### English

## 3.5.6: copy, review approvals and connected features

The implementation below is in this source revision. The download panel above identifies the version actually published.

- User-message copy remains available during generation; editing, deleting and branching retain their running-state restrictions. Only the visible user-authored text is copied.
- Memory-review candidates keep their original conversation and one-shot token. Local approval commits data with an idempotent receipt; discard/skip does not start another model turn or resume the old task. Expired cards are pruned on return.
- Quick Settings configuration accepts 1–4 strict commands. Results appear on the expanded tile itself, with the next command underneath. Cached-weather and pending-note home-screen widgets are available.
- Text/link sharing opens a local preview with save or chat-draft actions. Notes support completion, restore, deletion and widget completion; legacy data migrates. Storage keeps the latest 50 notes, up to 8,000 characters each.
- Conversation options connect sampling persistence and up to three side-by-side model answers, with independent streams/errors, cancellation, full-text copy and adoption. Comparison has no tool executor. Unsupported sampling fields are visible; extra providers incur their own usage charges.
- The persistent UTF-8 document library supports imports, conversation attachment, source pins, removal and keyword retrieval. Version/line-bound citations open the revalidated source. This is keyword search rather than claimed embedding search.
- NFC uses explicit foreground Reader Mode and exact tag bindings. Rule dry runs explain true/false/unknown conditions without scheduling or executing a task.
- Screen location matches the latest UI-tree text/descriptions, with explicit unsupported results for missing trees. There is no visual OCR/OmniParser integration. Node changes are evidence, not proof of goal completion. Revoking an established virtual route never redirects old coordinates to the main display.
- The restored settings design, default-off screen capture and removed token-statistics/run-details/local-LLM/donation features remain unchanged. Sunset reminders are excluded. App resources retain the existing Simplified Chinese policy.

[Validation record](docs/VALIDATION_2026-11-19.md) · [Changelog](CHANGELOG.md)

## Eta Ultra 3.5.5 · 历史说明

该轮恢复了设置页旧视觉，并加入第二批调研对应的声明、类型与基础接口。后续验收发现模型对比、KB、NFC 等部分入口未贯通；本轮 3.5.6 补齐实际执行与持久化，并收窄了此前对视觉解析、语义检索和目标进度的表述。历史资料不充当本轮完成证据。

## Eta Ultra 3.5.4 · 天气秒回与清爽设置

### 中文

- 天气换成国内数据源：地名与预报一次请求拿全，国内常用查询实测 0.1–0.3 秒返回；同一地区 10 分钟内复用缓存，连续追问不再联网。
- 播报改成一句话：「今天大余县小雨，气温12到24度。出门建议带伞。」多日查询按天并列；缺数据就省略或明确说明，不编造降水概率。
- 当前位置保持真实：不再把 IP 所在城市当成你的位置；坐标只来自设备定位，解析不出行政区就按坐标预报。
- 默认不再截屏：普通语音唤醒不申请应用内容与截图，只有你点「看屏幕」才采集一次；关闭开关会立即释放并撤销许可。
- 附件可发：文件夹按授权树导入私有工作区，图片/文件/压缩包保留读权限，运行中带附件发送会排队而不是丢弃。
- 设置更清爽：Token 用量统计与聊天「运行详情」整体移除；设置页改为紫色调、线条图标、分组导航，搜索仍跨全部设置项。

### 验证与范围

- 本地定向回归（天气、附件、语音会话、设置、路由）与全量 JVM 单元测试、Android Lint 均通过。
- 真机麦克风、AEC、厂商唤醒、系统截图授权行为仍需设备实测；本机无连接设备，未做真机验证。
- 外部天气工单的评审与取舍见 [天气与助理修复说明](docs/WEATHER_AND_ASSISTANT_FIX_2026-11-18.md)，UAPI 接入细节见 [天气数据源](docs/WEATHER_SOURCES.md)。

## Eta Ultra 3.5.1 · 模型按需下载与安装包压缩

- 中文 Vosk 模型改为首次使用时按需下载，APK 启用 DEX 压缩，缩小安装包体积。免费系统识别、Vosk 后备、连续语音与全部 3.5 胶囊、工具、任务能力继续保留。
- 系统识别可直接使用，无需下载 Vosk 模型；首次选择 Vosk、触发后备或开启连续语音时联网下载约 44 MB 中文模型，完成后离线识别。已有私有就绪模型直接复用。
- 模型固定从官方 HTTPS 地址下载到应用私有暂存目录，提供进度与取消；完整校验大小和 SHA-256，按文件白名单解压并校验后原子发布就绪目录。失败或取消清理本次暂存，保留原有音频任务取消与所属页面范围。
- 基础版本／versionCode 更新为 `3.5.1`／`2026111706`，CI 继续使用 `3.5.1.<run_number>` 与 `2026111706 + run_number`；最终发布身份由 CI 元数据生成。

体积基线为 3.5.0.4 签名 APK 的 59,536,691 字节（约 59.5 MB），其中模型 ZIP 条目压缩后为 43,540,544 字节，DEX 合计 9,506,388 字节且以 stored 方式存放。3.5.1 本地同证书签名 APK 实测为 11,184,675 字节（约 10.7 MB），缩小约 81%；最终 CI 产物体积以发布元数据为准。按需模型首次使用时约 44 MB，展开后仍占约 68 MB 私有存储；这是存储和下载体积，并非运行内存，运行内存需另行实测。模型固定来源、大小及 SHA-256 见 [语音配置](docs/VOICE.md)。

### 3.5.1 生活便利与界面
- 设置页新增「天气预报」入口：保存精确到区县乡镇的坐标、开启「询问天气使用当前位置」后直接问天气即按定位预报；地点搜索按行政层级过滤，同名地点显示所属区县供核对，不自动用市级地点冒充乡镇。
- 新增生活快捷指令（严格整句匹配、零模型）：时间／日期／计算／日程／天气／闹钟／倒计时之外，补齐手电筒、音量、响铃、打开 App、导航、电量与存储，以及播放控制、勿扰、系统面板、闹钟查询、记事备忘与计算历史。拨号、短信、日历等动作只拉起系统界面由用户最终确认，不自动发送或写入。
- 「设置闹钟」缺少时间时进入本地追问补全（如「明天早上 7 点」），不再触发模型思考；应用经验卡功能整体移除。
- 全量 UI 改版：新增 EtaDesign 设计 token，卡片／偏好项／设置搜索栏／语音胶囊／聊天空状态统一接入；语音胶囊强调色跟随主题主色，圆角与动效收敛到统一梯度；设置页搜索索引独立成模块。

### 3.5.1 验证
- 本地集中回归 362 项测试全部通过（0 失败、1 跳过），其中生活快捷 25 项、快捷路由 16 项、模型下载 6 项；编译与 Android Lint 均无错误。真机麦克风、AEC、厂商唤醒与触摸穿透仍需设备实测。

## Eta Ultra 3.5 · 胶囊助手与免费语音

### 中文

- 系统助理唤醒改为直接展示透明底部胶囊，移除中转 Activity、全屏渐变与遮罩；Eta 的系统按键入口只使用助理会话与有界后台修复，避免 Activity fallback 拉走当前应用。
- 默认免费系统识别，启动失败一次回退内置中文 Vosk；系统 TTS 优先已安装离线音色，本机语音路径跳过云密钥读取。
- 增加可见窗口内连续语音、RMS VAD／文字后缀端点提示，以及实际 AEC／耳机路由共同约束的插话。键盘、熄屏、关闭窗口释放采集；单句和空闲时长有界。
- 接入实际 Vosk 逐词时间、系统 TTS range／实际 PCM 播放进度、准确续读位置和有界语音时间线；缺少时间信息时保持未知。
- 聊天和胶囊增加无需模型的时间、日期、十进制计算、今日日程和天气结果；天气使用明确城市与有时效标签的缓存，闹钟／计时器交系统确认并标为待核对交接。
- 运行详情展示真实工具／MCP／Skill／子智能体摘要、请求尝试、首响应头、首非空增量、HTTP 状态、重试、上下文变化与已观测用量。
- 任务计划保存六种步骤状态与本轮工具证据；经验卡按应用版本筛选并显式选择，实际任务记录可保存待核验草稿。恢复记录跨重启保留，用户显式继续，历史证据与本轮分离。
- 审批绑定原会话／run／单次 token，事务发件箱与精确 ACK 避免重复投递；人工接管完成要求重新读屏。
- 截图来源、采集时间、预览、重新采集和上下文开关接入胶囊。键盘与小屏布局有高度约束，透明留白从触摸区域排除。
- LLM 服务商置顶；按用户要求移除本地大模型中心与 LiteRT，保留 Vosk 语音识别及 HTTP／Ollama；捐赠页面保持删除。
- 提供启动 Baseline Profile 规则、低内存波形限频与 Canvas 回退。已有 Markdown 文件卡与工作区的预览／打开／分享／长按复制继续复用。

### 验证与范围

详细执行结果见 [本轮验证记录](docs/ASSISTANT_VALIDATION_2026-11-17.md)。持续语音使用 STT → 当前模型 → TTS；RMS／文字规则属于本地端点提示。当前环境的 JVM／Robolectric 与主机 Vosk 测试不代替 Android 麦克风、AEC、厂商唤醒、触摸穿透与耗电实测。Startup Profile 是配置规则，性能收益待设备测量。实际发布资产与证书以 Release 元数据为准。

完整变更与参考项目见 [实现对照](docs/VOICE_ASSISTANT_GEMINI_RESEARCH.md)。原有上下文压缩、分支／回溯、MCP、Skills、虚拟屏、终端与自动任务继续复用。
