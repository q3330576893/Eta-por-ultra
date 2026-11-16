# Eta Ultra

**面向 Android 的系统级 AI 智能体与语音助手。** 将聊天、屏幕理解、工具调用、终端和多步骤任务连接到同一套 Agent Runtime，并提供 Gemini 风格的沉浸式语音浮窗。

**简体中文** | [English](README_EN.md)

[项目主页](https://github.com/q3330576893/Eta-por-ultra) · [最新版](https://github.com/q3330576893/Eta-por-ultra/releases/latest) · [全部版本](https://github.com/q3330576893/Eta-por-ultra/releases) · [问题反馈](https://github.com/q3330576893/Eta-por-ultra/issues)

Eta Ultra 由 [q3330576893](https://github.com/q3330576893) 维护，基于 [Oo0520/Eta-por](https://github.com/Oo0520/Eta-por) 定制分支，原始项目为 [Mangi-11/Eta](https://github.com/Mangi-11/Eta)。原有智能体、系统集成与社区定制能力继续保留；下文单独列出本轮语音界面改造，详细来源见文末。

## 下载与安装

<!-- release-info:start -->
**最新正式版：[3.4.1.1](https://github.com/q3330576893/Eta-por-ultra/releases/tag/v3.4.1.1)** · Android 13+ · ARM64

[下载签名正式 APK](https://github.com/q3330576893/Eta-por-ultra/releases/latest/download/Eta-Ultra.apk) · [历史版本](https://github.com/q3330576893/Eta-por-ultra/releases) · [本版更新说明](https://github.com/q3330576893/Eta-por-ultra/releases/tag/v3.4.1.1)

来源提交：[`d362f99`](https://github.com/q3330576893/Eta-por-ultra/commit/d362f99d555cc6a965f985baba82e557b950d23d) · versionCode `2026111602`

SHA-256：`181db91e9472b94b185f22f7e173339557a905e63353e8c64aa42e1edcda1816`
<!-- release-info:end -->

下载 Release 中的 `Eta-Ultra.apk` 后安装。正式版沿用应用 ID `io.github.mangi.eta`；覆盖升级同时取决于应用 ID、签名证书和递增的 `versionCode`，上游或其他签名的安装包可能需要先备份数据再迁移。首次使用在应用内配置模型服务，并按需要设置语音、无障碍、默认数字助理、悬浮窗或 Root／LSPosed 集成；不同能力的设备与权限要求见 [设备支持说明](docs/ROOTLESS_SUPPORT.md)。

正式下载入口始终指向 GitHub Releases。Debug 包和开发可选 Beta 包用于调试，面向用户的默认分发为签名正式 Release APK。

## 本轮改造：Gemini 风格语音浮窗

- **全屏蓝紫渐变**：浅深色分别调色，正文和输入区域保持清晰的表面层次。
- **底部四色流动声波**：录音音量平滑响应；优先使用原有 Shader 数值轨道，软件画布、资源加载或绘制异常时使用 Canvas 回退。
- **统一胶囊输入框**：文字输入与聆听态共享 60dp 高、32dp 圆角外观，支持键盘切换、实时识别文本、发送与结束录音操作。
- **真实阶段反馈**：连接麦克风、聆听、识别、处理、工具执行、朗读、完成和失败状态有对应文字与颜色，保留读屏语义。
- **屏幕上下文可见性**：展示唤起时的应用来源、采集时间和快照时效说明；可关闭本轮请求附加的屏幕上下文。
- **现有交互继续保留**：按住说话、松开发送、移出取消、建议胶囊、键盘 Insets、拖拽与进入完整对话。

本轮实现、开源项目参考和后续候选功能见 [语音浮窗改造与调研记录](docs/VOICE_ASSISTANT_GEMINI_RESEARCH.md)。记录中的后续计划与已实现功能分别列出。

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
| 语音 | 系统／云端 ASR、多供应商 TTS、自动或手动朗读、续读、按住说话、可整体关闭语音识别 |
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

本地基础版本为 `3.4.1`，基础 `versionCode` 为 `2026111601`；CI 传入发布运行号，生成四段数字版本 `3.4.1.<run_number>`，并使用 `2026111601 + run_number`。四段数字版本可由应用更新检查比较，运行号持续递增。Release 开启 R8 优化与资源裁剪，`debuggable=false`；签名与发布步骤见 [发布流程](.github/RELEASING.md)。

## 每次更新与发布

`main` 是本仓库唯一开发和正式发布主线。每次开发更新应同步维护实际功能介绍、中文／英文首页以及发布说明或变更记录，提交可验证的源码后推送。CI 负责构建正式 APK、验证签名、计算 SHA256、生成 `release-info.json` 与发布说明，并发布公开 GitHub Release。

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
