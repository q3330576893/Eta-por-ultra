# GitHub 同类项目调研 · 第二批（2026-11-18）

> 应要求寻找**上一批之外**的同类项目，吸取经验、挖掘好功能。
> 本轮只收集建议，**未实施任何一条、未改任何功能代码**——用户明确要求先看全部建议再决定。
> 上一批见 `OPEN_SOURCE_RESEARCH_2026-11-18.md`，本批 16 个项目与 15 条建议均避开上一批全部项目与条目。
> star 数均 shields.io 实测、仓库主页 HTTP 200 确认；GitHub API 搜索因 403/429 未用。

---

## 方向一 · 手机 GUI Agent / 无障碍自动化

### 项目（5）
| 项目 | Star | 链接 | 解决什么 |
|---|---|---|---|
| bytedance/UI-TARS | 12k | https://github.com/bytedance/UI-TARS | 原生 GUI Agent：端到端视觉定位 + 原子动作 + 反思式多步轨迹 |
| microsoft/OmniParser | 26k | https://github.com/microsoft/OmniParser | 纯视觉屏幕解析：可交互控件检测 + OCR + 元素编号标注 |
| droidrun/mobilerun | 10k | https://github.com/droidrun/mobilerun | 模型无关的 a11y 树 + 截图双通道手机自动化 |
| X-PLUG/MobileAgent | 9.4k | https://github.com/X-PLUG/MobileAgent | 截图 + 图标标注做跨 App 操作 |
| google-research/android_world | 1k | https://github.com/google-research/android_world | 真实 Android 环境 Agent 评测基准 |

（microsoft/AppAgent 已 404，弃用。）

### 建议
**1.1【高】动作后目标级验证 / 反思钩子**（借鉴 UI-TARS）
- 改：`VirtualScreenUiTools.kt` 的 `afterAction()`，在 `screen_changed` 基础上加 `goal_progress` 字段——对比动作前后 `ui_nodes` 的 (viewId,text,desc,checked,enabled) 集合，输出 added/removed/changed 与目标关键词命中；`AgentContextAppToolCatalog.kt` 与 `AgentPromptBuilder.kt:168-170` 加"先看 goal_progress 判进度"的指引。
- 价值：Eta 已有 screen_changed 原子断言，但缺"目标推进了多少"的判断，Agent 容易盲目继续。改动最小、价值最直接。
- 边界：只做轻量节点 diff，不引入第二模型；节点不稳时如实 unknown，不猜成功。

**1.2【高】a11y 空/截断树的视觉控件回退**（借鉴 OmniParser + MobileAgent）
- 改：`AgentScreenObservationContract.kt` 加字段；`VirtualScreenUiTools.observe()/dispatch()` 在 `uiTreeAvailable=false` 且含截图时加 `visual_hint`（提示用归一化坐标）；`AgentGestureToolCatalog.kt` 加可选 `locate_on_screen` 工具。
- 价值：自绘 Canvas、二维码、游戏按钮等 a11y 树读不到时，Agent 不再抓瞎。
- 边界：不引入重型视觉模型（保端侧体积），仅提示 + 坐标；无 OCR 如实标 unknown。

**1.3【中高】GUI Agent 本地回归基准**（借鉴 AndroidWorld）
- 改：新增 `fixtures/gui-agent-benchmark/` 任务 JSON + `app/src/test/.../accessibility/GuiTrajectoryBenchmarkTest.kt`，复用 `AccessibilityNodeIdentity.kt` 做 headless 节点轨迹断言，CI 跑 `:app:testDebugUnitTest`。
- 价值：为 GUI 改动建立防回归网。
- 边界：只做确定性节点/坐标回归，CI 无设备不做端到端真机。

**1.4【中】观察/执行 Provider 接口化**（借鉴 mobilerun）
- 改：把 `VirtualScreenUiTools` 的 observe/dispatch 抽成内部 `ScreenObservationProvider`/`ScreenActionProvider`，新增 `agent/display/ScreenProviders.kt`。
- 价值：便于测试注入 fake、日后接视觉解析器不动上层。
- 边界：纯内部接口抽取，行为不变，对外工具名/参数兼容。

---

## 方向二 · Agent 记忆系统 / 个性化 / 长期上下文

### 项目（4）
| 项目 | Star | 链接 | 解决什么 |
|---|---|---|---|
| mem0ai/mem0 | 69k | https://github.com/mem0ai/mem0 | 个性化记忆层：ADD-only 抽取、多信号检索、时间推理、三级记忆 |
| letta-ai/letta | 25k | https://github.com/letta-ai/letta | 有状态 Agent：core/archival/history 分层内存、自我编辑 |
| getzep/graphiti | 33k | https://github.com/getzep/graphiti | 时间知识图：事实有效期窗口、失效非删除、episode 溯源 |
| topoteretes/cognee | 33k | https://github.com/topoteretes/cognee | 本地免 key 记忆平台，session distillation 蒸馏经验 |

### 建议
**2.1【高·主打】memory_get 从整串 contains 升级为本地多信号打分**（借鉴 Mem0 + Graphiti）
- 改：`AgentMemoryRepository.kt` 的 `search()`（478-525 行）——query 分词逐 token 匹配、词频/标题命中加权、实体词优先、返回带分数的有序命中；`AgentMemoryToolCatalog.kt` 同步描述。
- 价值：现在换个说法（"上次那个蓝色的" vs 记忆里"偏好深色"）就检索不到；纯本地算法拿到 Mem0 大部分收益。
- 边界：纯本地 O(行数)，零模型/网络/新依赖/常驻内存；注意别写成 O(n²)。

**2.2【高】事实有效期：按新鲜度重排 + 旧条目"可能过期"标记**（借鉴 Graphiti + Mem0）
- 改：`AgentMemoryRepository.kt` 复用已有日期解析（351 行）在返回里附最近更新日、对同主题冲突条目给新旧提示；`AgentContextSummarizer.kt` 摘要保留事实时间；`AgentMemoryToolCatalog.kt` 要求写入带日期。
- 价值：解决"记忆越多越自相矛盾"，已有日期解析抓手，改动小。
- 边界：纯本地解析可忽略开销；依赖模型按规范写日期，属尽力而为。

**2.3【中高】会话经验蒸馏：确认/纠正的事实走审批写入**（借鉴 Cognee + Mem0）
- 改：`AgentExperienceReview.kt` 在 append 前加一道轻量蒸馏，把确认/纠正事实提炼成 1-3 条候选，经 `AgentConfirmGuard` 审批再 memory_write；用 `AgentSensitiveToolPolicy` 排除 mcp_/personal_* 敏感输出。
- 价值：把隐性对话转成可复用长期记忆，已有复盘骨架，加的是提炼而非新系统。
- 边界：多一次 LLM 调用（本地模型受内存闸门约束，可低内存跳过）；**隐私风险最大**——必须排除敏感输出与凭据。

**2.4【中】记忆召回可检视面板**（借鉴 Letta + Cognee + Mem0）
- 改：`ui/components/MemoryUsageSheet.kt` 加"召回测试"入口，调 2.1 的 search + 2.2 的过期判定，展示命中行/热区冷区/时间戳；`AgentMemoryScreen.kt` 加入口。
- 价值：Eta 已有热/冷分层但用户不可见，无法发现记错/记旧。
- 边界：本地展示，注意截图/录屏别外泄。

---

## 方向三 · 其他 Android 语音助手 / 智能助理

### 项目（4）
| 项目 | Star | 链接 | 解决什么 |
|---|---|---|---|
| Home Assistant Companion Android | 4.1k | https://github.com/home-assistant/android | 通知快捷操作/widget/NFC 直触发家居控制 |
| openHAB Android | 654 | https://github.com/openhab/openhab-android | NFC 改 item、语音命令、Tasker 插件桥接 |
| SUSI.AI Android | 2.4k | https://github.com/fossasia/susi_android | 用户浏览 Skills、打分反馈、地理位置改善回答 |
| Mycroft Core | 6.6k | https://github.com/MycroftAI/mycroft-core | 分层配置覆盖 + 声明式 skill metadata |

### 建议
**3.1【高】通知栏 / 快捷 tile 直触发本地意图**（借鉴 HA Companion）
- 改：新增 `agent/quickactions/QuickTileService.kt`（TileService）与 receiver；复用 `DailyIntentRouter.kt` 严格语法 + `QuickActionsExecutor.kt` 执行；`AndroidManifest.xml` 注册；三语文案。
- 价值：Eta 已有一整套零模型本地快捷指令（时间/闹钟/计算/天气/手电筒/音量…），但目前只能"说出/打字"触发。给它通知栏常驻入口，等于把最常用的几个能力变成一次下拉即用的系统级功能，高频价值极高，不依赖模型、不联网。
- 边界：tile 限 3-4 个可配置；只接受严格整串匹配的意图串，复合句回退会话，防误触危险操作；处理 Android 13+ tile 权限与锁屏可见性。

**3.2【高】NFC 触碰触发任务规则**（借鉴 openHAB）
- 改：Manifest 已有 NFC 权限；新增 `agent/automation/NfcTagReceiver.kt` 解析 NDEF；`AgentTaskRules.kt` 的 eventTypes 加 `nfc_tapped`、filterFields 支持 tagId/ndefText；`AgentTriggerMonitorService.kt` 加分支或独立 receiver 调 `AgentTaskScheduler.publish`；三语文案。
- 价值：Eta 触发器已有 17 类，NFC 是低成本高确定性的物理世界入口——贴"回家 tag"触发场景、贴"勿扰 tag"静音，与现有自动化无缝复用。
- 边界：NFC 后台读取通常要求前台或用户主动触碰，不能宣称锁屏常驻读取；tagId 精确匹配且长度受限（复用 200 字符约束）；无 NFC 设备隐藏功能。

**3.3【高】Skill 评分与反馈闭环**（借鉴 SUSI.AI）
- 改：`SkillEntities.kt` 新增 `skill_feedback` 实体（skillId/rating/comment/createdAt）+ `SkillDao.kt` 查询；`AgentSkillsScreen.kt` 每行加评分入口；`AgentSkillsUiState.kt` 加 action + `AgentSkillsStore.kt` 处理；三语文案 + Room migration。
- 价值：Eta Skills 已能装能管，但用户无法表达"好用/垃圾"，作者也收不到信号；评分是提升 Skill 生态质量的最低成本方式，可作自动禁用低分/优先高分的基础。
- 边界：反馈存本地 Room 不联网；评论限长 500 字符并脱敏；评分只作展示与本地排序，不自动改启用状态除非用户确认；需 migration + 测试（linux-aarch64 下 Robolectric 会 skip）。

**3.4【中·可选】配置分层覆盖模型**（借鉴 Mycroft）
- 改：新增 `core/SettingsLayers.kt` 定义 default < remote < device < user 优先级与合并；Repository 层按层叠加；关键配置页加"恢复默认/仅本设备覆盖"入口；无需改 Schema。
- 价值：Eta 配置多，多设备/换机时难区分"全局默认"和"这台设备特意调的"；分层让重置精确到层。
- 边界：改造面广、回归风险高，属架构级增强，建议放最后；旧配置需平滑迁移到用户层；逐屏验证。

---

## 方向四 · 端侧 LLM 聊天 App / 多模型客户端

### 项目（4）
| 项目 | Star | 链接 | 解决什么 |
|---|---|---|---|
| Vali-98/ChatterUI | 2.9k | https://github.com/Vali-98/ChatterUI | 原生移动 LLM 前端，细粒度 chat structuring：角色卡/多会话/采样参数/Instruct 模板/API 模板 |
| rikkahub/rikkahub | 9k | https://github.com/rikkahub/rikkahub | 多供应商 Android App，Material You，声明式供应商能力注册 |
| CherryHQ/cherry-studio | 54k | https://github.com/CherryHQ/cherry-studio | 多模型并排对比、定时任务、API Gateway、Skills+MCP、WebDAV 备份 |
| Mintplex-Labs/anything-llm | 68k | https://github.com/Mintplex-Labs/anything-llm | 本地优先文档知识库 + 多模型聊天 |

（lobehub/lobe-chat 86k 偏 Web 生态，与端侧重合低，未列主清单。）

### 建议
**4.1【高·力荐】同会话多模型并排对比回答**（借鉴 Cherry Studio）
- 改：`AgentModelPickerUiState.kt` + `AgentChatUiState.kt` 增加多模型对比选择与 per-model 分支状态；复用 `BranchGraphUi.kt` 渲染多列；分发逻辑在 `AgentLoop.kt` 或新增 `AgentMultiModelFanout.kt`；调用走 `ProviderClientFactory.kt`/`AgentModelClient.kt`。
- 价值：Eta 已有多供应商配置 + 分支图 UI，底层现成，缺的只是"一问多答"编排，用户能直观横评模型。
- 边界：token/费用按 N 倍增长，需费用提示；窄屏横向滚动/折叠；并发 N 路 SSE 有内存压力，建议默认 ≤3 模型。

**4.2【中高】会话级"采样参数 + Instruct 模板"覆盖**（借鉴 ChatterUI）
- 改：`PromptPreset.kt`/`PromptPresetCodec.kt`/`PromptPresetRepository.kt` 扩展为会话级覆盖层；注入点在 `AgentPromptBuilder.kt` 与各 Provider Request 构造；设置 UI 在 `ui/settings/` 新增面板；角色元数据挂 `agent/roleplay/`。
- 价值：Eta 有全局提示预设但无"按会话细调采样"，角色扮演/严谨推理场景差别明显，低成本高感知。
- 边界：不是所有 provider 支持同名字段（沿用 `ProviderParameterDiagnostics.kt` 降级）；存会话表需加字段/迁移；UI 标注"仅对支持的模型生效"。

**4.3【中】文档知识库作为可复用实体**（借鉴 AnythingLLM）
- 改：`AgentMemoryContext.kt`/`ConversationMemoryPins.kt` 扩展出 KB pin；`AgentFileReference.kt`/`AgentFileToolCatalog.kt` 加"索引到 KB"动作；检索复用 `AgentPersonalSearchToolCatalog.kt`；UI 在 `AgentMemoryUiState.kt` 加 KB 列表状态。
- 价值：Eta 已有附件 + @引用 + 记忆热区压缩，串成持久化 KB 是自然演进，避免重复解析大文件。
- 边界：切分/embedding 增大本地占用；复用附件权限与敏感策略；若不引入 embedding 可先做"文件名+全文关键词"轻量版控成本。

---

## 优先级速览（供选择）

| 优先级 | 建议 | 一句话 |
|---|---|---|
| ★★★ | 3.1 通知 tile 直触发 | 本地快捷指令变系统级，零模型零联网 |
| ★★★ | 3.2 NFC 触发规则 | 贴标签触发场景，复用现有 17 类触发器 |
| ★★★ | 4.1 多模型并排对比 | 底层现成，缺的只是编排 |
| ★★★ | 2.1 记忆检索多信号打分 | 纯本地零成本，解决换个说法就找不到 |
| ★★★ | 1.1 动作后目标级验证 | 改动最小，Agent 不再盲目继续 |
| ★★☆ | 3.3 Skill 评分反馈 | 补齐 Skill 生态闭环 |
| ★★☆ | 2.2 事实有效期 | 解决记忆自相矛盾 |
| ★★☆ | 2.3 会话经验蒸馏 | 隐私风险需重点防 |
| ★★☆ | 1.2 视觉控件回退 | a11y 空树时的兜底 |
| ★★☆ | 4.2 会话级采样覆盖 | 低成本高感知 |
| ★★☆ | 2.4 记忆召回可检视 | 发现记错记旧 |
| ★☆☆ | 1.3 GUI 回归基准 | 防回归网 |
| ★☆☆ | 3.4 配置分层 | 架构级，风险高放最后 |
| ★☆☆ | 1.4 Provider 接口化 | 长期演进铺垫 |
| ★☆☆ | 4.3 文档知识库 | 轻量版可行 |

**再次强调：本轮只调研，未实施任何一条。**
