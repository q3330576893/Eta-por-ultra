# Eta Ultra

**面向 Android 的系统级 AI 智能体与语音助手。** 透明底部胶囊连接免费系统／Vosk 语音、屏幕理解、工具调用与多步骤任务，唤醒入口通过系统助理会话展示浮窗。

**简体中文** | [English](README_EN.md)

[项目主页](https://github.com/q3330576893/Eta-por-ultra) · [最新版](https://github.com/q3330576893/Eta-por-ultra/releases/latest) · [全部版本](https://github.com/q3330576893/Eta-por-ultra/releases) · [问题反馈](https://github.com/q3330576893/Eta-por-ultra/issues)

Eta Ultra 由 [q3330576893](https://github.com/q3330576893) 维护，基于 [Oo0520/Eta-por](https://github.com/Oo0520/Eta-por) 定制分支，原始项目为 [Mangi-11/Eta](https://github.com/Mangi-11/Eta)。原有智能体、系统集成与社区定制能力继续保留；下文单独列出本轮语音界面改造，详细来源见文末。

## 下载与安装

<!-- release-info:start -->
**最新正式版：[3.5.6.24](https://github.com/q3330576893/Eta-por-ultra/releases/tag/v3.5.6.24)** · Android 13+ · ARM64

[下载签名正式 APK](https://github.com/q3330576893/Eta-por-ultra/releases/latest/download/Eta-Ultra.apk) · [历史版本](https://github.com/q3330576893/Eta-por-ultra/releases) · [本版更新说明](https://github.com/q3330576893/Eta-por-ultra/releases/tag/v3.5.6.24)

来源提交：[`e1bb367`](https://github.com/q3330576893/Eta-por-ultra/commit/e1bb3679bb3fc71d6cacf64720645408af63848f) · versionCode `2026112124`

SHA-256：`f9b106bdeb14309b9acb5837d23eba4a9159ea07b5658960894881192717e6fe`
<!-- release-info:end -->

下载 Release 中的 `Eta-Ultra.apk` 后安装。正式版沿用应用 ID `io.github.mangi.eta`；覆盖升级同时取决于应用 ID、签名证书和递增的 `versionCode`，上游或其他签名的安装包可能需要先备份数据再迁移。首次使用在应用内配置模型服务，并按需要设置语音、无障碍、默认数字助理、悬浮窗或 Root／LSPosed 集成；不同能力的设备与权限要求见 [设备支持说明](docs/ROOTLESS_SUPPORT.md)。

正式下载入口始终指向 GitHub Releases。Debug 包和开发可选 Beta 包用于调试，面向用户的默认分发为签名正式 Release APK。

生态连接器：MCP 设置页新增 12 个常用服务模板（Notion、Linear、GitHub、GitLab、Sentry、PostgreSQL、Supabase、Slack、飞书、Zapier、n8n、自定义），点选即填入端点并显示可用条件；密钥仍由你自己填写，应用不内置任何凭据。

冷启动带一段约 1 秒的简约品牌开屏：细环收拢、中心点淡入、`ETA ULTRA` 字距收紧、细线生长后整体淡出。仅冷启动播放一次，跟随主题取色，深色模式不闪白，不阻塞初始化。

诊断包自动导出改为：优先写入 `/storage/emulated/0/ai共享文件夹/诊断包备份/自动`，不可写时自动回退到应用私有 `files/diagnostics-auto`；成功按周推进，失败按周退避，不再每分钟重复写失败日志。自检页显示真实导出目录，行为核查分别统计成功与失败。

本轮服务优化还覆盖启动主线程减负、请求投影与快照序列化减负、Hook/工具 token 估算缓存、内存闸门单次读数、工具 capability 常量化、Compose 列表与上下文计算减负、诊断日志单次读盘与解析降本，以及 OAuth 会话后台预热。完整回归 2,525 项、0 失败，强制门禁 48 套件/75 关键用例无跳过通过。

## 按模型请求提示词 Hook（开发版）

- 在 **LLM 服务商 → 模型 → 编辑** 中独立保存请求提示词，默认关闭；启用后选择 **System / User / 双通道**，可套用原创研究模板后继续编辑。
- 普通聊天、工具续轮、指定子模型和模型对比使用各自模型的设置。四种 HTTP 协议在最终请求侧投影，保留图片与工具调用历史，注入文本不会成为聊天消息；小布文本通道仅使用 User。
- 摘要、语音转写修正、回复改写与自动复盘使用专用提示词。请求快照可核对通道、配置哈希和估计开销，Hook 正文不进入快照复制或导出。
- 本功能提供可检验的请求扩展；具体任务表现以模型实际回复和工具结果为准。顶部下载区继续标识实际公开版本。
- **本地预览验收**：`3.5.6.15+model-hook` 已构建并核对原签名、ARM64、非调试及实际打包内容。全量 2,517 项测试，0 失败／错误、71 项平台跳过（2,446 项实际通过）；47 个必过套件、72 个关键场景无跳过通过，Lint 与 Debug 构建通过。同轮修复了多服务商同名模型串用授权、编辑弹窗重建丢草稿和显式子模型失效回落三项问题，均有真实回归覆盖。真机交互、Linux 专属描述符写入与远端模型效果分别保留待验证状态。

[配置与验证](docs/MODEL_REQUEST_HOOK.md)

## 实验：APK 逆向工具与可选技能

- 新版 **APK 分析** 可选档案（revision 2）增加 `eta-reverse probe / inspect / decompile`，复用已有 JDK/JADX，输出真实版本、输入 SHA-256、退出码、报告及 Java 工件哈希。
- 固定官方 JADX CLI 类与 headless 入口；样本仅在明确工作区内做静态处理，超时、空源码、部分输出、超限和身份变化均保持失败或不完整状态。不会执行 APK。
- Skills 页可手动导入随发布提供的 **Eta-Reverse-Analysis.skill.zip**；不新增随升级自动启用的角色，不改服务商、Root／终端开关或天气功能。客户端与 APK 工具档案都需更新，旧 v14 安装器不含该命令。
- 宿主完成了 11 项合成引擎契约与真实 JADX 1.5.6 自建 APK 反编译；手机 ARM64 安装／工作区实测尚未执行。原有 `apktool build` 限制继续保留。

- 逆向模块阶段历史验收：客户端完整回归 2,429 项、0 失败、0 错误、69 项宿主平台跳过；严格必过用例、Lint 与 Debug 构建通过。另有 11 项后端契约和真实 JADX 自建 APK 烟测通过；Android ARM64 实机执行尚未进行。正式包、Skill 和 CI 样本证据按发布附件核验。

[使用与边界](plugins/eta-reverse-analysis/README.md) · [九项目固定源码调研与实证](docs/AGENT_REVERSE_RESEARCH_2026-11-21.md)

## 功能精简，天气完整保留（2026-11-21）

- 已移除本地备忘、通知栏快捷指令、文档知识库、NFC 任务和自动化规则试跑，包括入口、专属工具、系统组件与资源。
- 天气预报、当前位置解析、常用地点、天气设置、缓存与刷新、桌面天气组件全部保留。天气组件改用独立设置页，不依赖已移除的通知栏指令配置。
- 纯文字／链接分享直接进入聊天草稿；普通附件、长期记忆、会话复盘、定时与非 NFC 事件任务继续保留。
- 升级及旧备份恢复后清理仅以 NFC 为触发器的旧任务和排队记录；旧备忘／通知栏配置／文档库快照不再导出或还原。普通天气配置、聊天、记忆、外部原文件及附件授权不受此清理影响。

- 完整本地回归 2,422 项发现、0 失败、0 错误、69 项宿主平台跳过；严格必过用例、Lint 与 Debug 构建通过。正式包与公开附件另按对应源码、版本、签名和哈希验收。

[移除范围与验证](docs/FEATURE_RETIREMENT_2026-11-21.md)

## 3.5.6：聊天复制、复盘审批与可用入口

本轮源码接线与修复如下；已发布版本、提交和校验值以顶部正式下载区为准。

- **自己的消息也能复制**：回复生成中仍可复制用户气泡正文；编辑、删除、分支继续遵守运行期限制。复制使用气泡实际显示的文字，跳过内部注入块。
- **复盘卡可保存、可跳过**：候选绑定原会话和唯一 token，批准在本地原子写入，重试避免重复；丢弃或跳过直接终结，不启动模型或续跑旧任务。返回前台会清理过期内容。
- **同会话模型对比与采样**：会话选项可选至多三个模型并排查看，独立显示流式结果与失败，支持取消、复制和采纳；对比关闭工具执行。采样按会话保存、重置并传入请求，供应商不支持的参数明确展示。额外模型会增加供应商调用费用。
- **定位与运行证据一致**：屏幕定位匹配最新 UI 节点文字与描述，缺树明确报告；未集成视觉 OCR/OmniParser。节点变化只表示观察证据，不能直接等同任务成功。已用灵屏的运行在撤权后停止，不将旧坐标投到主屏。
- 保持旧版设置渐变主标、图标与分类；保留默认不截屏，Token 统计、运行详情、本地大模型中心、捐赠继续移除；本轮未增加日落提醒。界面沿用现有简体中文配置。

[本轮验证记录](docs/VALIDATION_2026-11-19.md) · [更新记录](CHANGELOG.md)

## 3.5：底部胶囊、免费语音与任务反馈

- **保持当前应用的唤醒入口**：移除中转助理 Activity，系统会话直接展示底部透明、按内容高度布局的胶囊。没有全屏渐变和遮罩；回复区域有高度上限，可上拉进入完整对话、下拉关闭。OEM 实际唤醒行为仍需对应设备验证。
- **免费语音默认可用**：AUTO／系统模式优先使用设备识别服务，可直接使用且无需下载 Vosk 模型；启动失败时一次回退到离线中文 Vosk。本机识别无需语音 API Key；Vosk 首次使用按需联网下载约 44 MB 中文模型，准备后复用私有目录模型并离线识别。系统 TTS 优先选择已安装的离线音色，云端 ASR／TTS 保留为可选配置。
- **连续语音与字幕**：可见胶囊内可开启持续 Vosk 识别，首次按需准备同一中文模型，之后保留麦克风跨轮工作；实际 AEC 启用或耳机路由就绪后，持续语音与非空识别文字共同确认插话。键盘、熄屏和关闭窗口停止采集。显示引擎实际报告的逐词时间、播报范围与有界语音时间线。
- **日常指令直接返回结果卡**：时间、日期、十进制计算、今日日历和 Open-Meteo 天气走确定性处理，聊天与胶囊均可使用，无需启动模型。天气沿用当前位置或手动常用地点设置；闹钟／计时器打开系统确认页面，交接状态与创建成功分开展示。
- **工具过程与恢复**：保留工具、MCP、Skill 和子智能体过程行及审批、任务恢复；运行详情弹层与 Token 统计入口保持删除。
- **计划与恢复**：计划按步骤记录状态和本轮工具证据；中断任务通过持久化记录显式恢复，历史回执不充当本轮成功证据。旧应用经验卡功能已移除，记忆复盘候选使用独立本地审批。
- **审批和屏幕上下文**：回答绑定原会话、run 与单次 token，人工接管完成后重新读屏；展示截图预览、来源、采集时间与重新采集入口。
- **设置更集中**：LLM 服务商置顶，删除本地大模型中心与 LiteRT 依赖；保留 Vosk 语音识别及既有 HTTP／Ollama 服务配置。文件结果继续使用已有 Markdown 文件模板、工作区预览、打开、分享与长按复制。

[本版更新](RELEASE_NOTES.md) · [实现与调研对照](docs/VOICE_ASSISTANT_GEMINI_RESEARCH.md) · [实际验证记录](docs/ASSISTANT_VALIDATION_2026-11-17.md)。连续语音采用 STT → 当前模型 → TTS 管线；端点提示来自 RMS 与文字后缀规则。

## 3.5.1：缩小安装包，语音功能继续保留

中文 Vosk 模型改为首次使用时按需下载，APK 同时启用 DEX 压缩。免费系统识别、Vosk 后备、连续语音以及 3.5 的胶囊、工具与任务能力继续保留。系统识别可直接使用；首次选择 Vosk、触发后备或开启连续语音时，需要联网下载约 44 MB 模型，准备完成后可离线复用；已有私有就绪模型的升级用户直接复用。

模型从固定官方 HTTPS 地址下载到应用私有暂存目录，完整校验大小与 SHA-256，并按文件白名单解压后原子发布就绪目录；失败或取消会清理本次暂存数据。该调整针对 APK 安装包体积：3.5.0.4 为 59,536,691 字节，3.5.1 本地同证书签名包实测 11,184,675 字节（约 10.7 MB），缩小约 81%；模型展开后仍占约 68 MB 存储，运行内存需另行实测。模型来源、校验值与首次使用说明见 [语音配置](docs/VOICE.md)。

## 本次界面精简

- 设置页已移除“支持与捐赠”入口和整页；专用支付跳转、收款码展示／保存代码、收款图片与文案同步删除。
- 关于区域保留正式安装包下载、版本记录、源码和问题反馈，功能入口更集中。

## 当前能力

以下主要来自 Eta 与社区定制分支的已有基础，并非全部由 Eta Ultra 本轮新增。

| 方向 | 能力 |
| --- | --- |
| 智能体执行 | 统一 Agent Runtime、GUI Agent、系统 API、工具执行过程、自动记忆与操作经验、定时和事件任务 |
| 模型与扩展 | 多模型供应商、Codex／Grok 订阅登录、MCP、Skills、工具参数校验、斜杠命令与历史消息引用 |
| 终端与工作区 | Android 终端、私有工作区文件浏览、Markdown 预览与编辑、代码块与离线 KaTeX |
| 屏幕与系统集成 | 唤醒屏幕上下文、Root 虚拟屏与小窗、连续手势、Xposed／OEM 助理入口、ColorOS 流体云与状态胶囊 |
| 会话与协作 | 会话分类、收藏／置顶、搜索与导航、多会话、并发子智能体及其工具过程面板 |
| 语音 | 系统／Vosk／云端 ASR、系统与多供应商 TTS、连续对话、实际范围字幕、续读与按住说话 |
| 外观与输入 | Miuix 界面、浅深色、Monet 与强调色、发送插队／长按排队、输入框上方工具与技能面板 |

具体模型、ROM、语音服务与权限可用性以应用设置、对应文档和实际设备为准。当前打包以简体中文和 ARM64 为主。

## 从源码构建

| 项目 | 要求 |
| --- | --- |
| Java | JDK 25 |
| Android SDK | SDK 37，平台包 `platforms;android-37.0` |
| Gradle | 使用仓库 Wrapper 9.7.1 |
| 最低系统／ABI | Android 13（API 33）／`arm64-v8a` |
| 发布主线 | `main` |

```bash
git clone https://github.com/q3330576893/Eta-por-ultra.git
cd Eta-por-ultra
./gradlew :app:assembleDebug
```

Windows 使用 `gradlew.bat`。在本地忽略的 `local.properties` 中配置 `sdk.dir`；首次构建需要下载依赖。原生组件重建要求见 [终端原生组件文档](docs/TERMINAL_NATIVE.md)。

正式构建需要通过环境变量配置本仓库发布证书：

```bash
./gradlew :app:assembleRelease
```

本地基础版本为 `3.5.3`，基础 `versionCode` 为 `2026111800`；CI 传入发布运行号，生成四段数字版本 `3.5.3.<run_number>`，并使用 `2026111800 + run_number`。四段数字版本可由应用更新检查比较，运行号持续递增。Release 开启 R8 优化、资源裁剪与 DEX 压缩，`debuggable=false`；签名与发布步骤见 [发布流程](.github/RELEASING.md)。

## 每次更新与发布

`main` 是本仓库唯一开发和正式发布主线。每次开发更新应同步维护实际功能介绍、中文首页以及发布说明或变更记录，提交可验证的源码后推送。CI 负责构建正式 APK、验证签名、计算 SHA256、生成 `release-info.json` 与发布说明，并发布公开 GitHub Release。

[`scripts/release_metadata.py`](scripts/release_metadata.py) 维护首页 `release-info` 标记内的版本下载区和发布元数据；它生成构建与提交事实，功能描述仍由维护者依据实际改动更新。签名失败或构建失败应修复对应工作流后重跑，Debug APK 不能替代正式发布产物。

详见 [分支与发布约定](docs/BRANCH_AND_RELEASE.md) 和 [发布操作说明](.github/RELEASING.md)。反馈请使用 [本仓库 Issues](https://github.com/q3330576893/Eta-por-ultra/issues)，附设备、Android／ROM、应用版本、复现步骤与相关日志。

## 文档导航

- [Agent Runtime](docs/AGENT_RUNTIME.md) · [记忆与操作经验](docs/EXPERIENCE_LEARNING.md) · [定时与事件任务](docs/AUTOMATION.md)
- [Root 虚拟屏](docs/VIRTUAL_SCREEN.md) · [设备支持](docs/ROOTLESS_SUPPORT.md) · [技术实现](docs/TECHNICAL.md)
- [语音配置](docs/VOICE.md) · [角色功能](docs/CHARACTERS.md) · [语音浮窗改造](docs/VOICE_ASSISTANT_GEMINI_RESEARCH.md)
- [发布操作](.github/RELEASING.md) · [分支与发布约定](docs/BRANCH_AND_RELEASE.md)

## 来源、致谢与许可

- [Mangi-11/Eta](https://github.com/Mangi-11/Eta)：原始 Eta 项目，提供 Agent Runtime、系统 API、终端、GUI Agent、Xposed 等核心基础。
- [Oo0520/Eta-por](https://github.com/Oo0520/Eta-por)：本仓库直接采用的社区定制基础，包含会话、Markdown、流体云、虚拟屏、子智能体等改进。
- 感谢原项目与社区补丁作者，以及 [Pi Coding Agent](https://github.com/earendil-works/pi)、[Hermes Agent](https://github.com/NousResearch/hermes-agent)、[OmniBot](https://github.com/omnimind-ai/OmniBot)、[libxposed API](https://github.com/libxposed/api)、[Miuix](https://github.com/compose-miuix-ui/miuix) 等参考和依赖项目。

Eta Ultra 是独立维护的定制分支，维护仓库为 [q3330576893/Eta-por-ultra](https://github.com/q3330576893/Eta-por-ultra)，与原作者官方发行版区分。项目保留 [PolyForm Noncommercial License 1.0.0](LICENSE) 及原始署名；第三方组件保留各自许可。

> Required Notice: Copyright © 2026 蛮吉 (Mangi-11).
