# Eta Ultra

面向 Android 的系统级 AI 智能体与语音助手：透明底部胶囊连接免费系统语音、屏幕理解、工具调用与多步任务。

[English](README_EN.md) · [最新版](https://github.com/q3330576893/Eta-por-ultra/releases/latest) · [全部版本](https://github.com/q3330576893/Eta-por-ultra/releases) · [问题反馈](https://github.com/q3330576893/Eta-por-ultra/issues)

## 下载

<!-- release-info:start -->
**最新正式版：[3.5.6.28](https://github.com/q3330576893/Eta-por-ultra/releases/tag/v3.5.6.28)** · Android 13+ · ARM64

[下载签名正式 APK](https://github.com/q3330576893/Eta-por-ultra/releases/latest/download/Eta-Ultra.apk) · [历史版本](https://github.com/q3330576893/Eta-por-ultra/releases) · [本版更新说明](https://github.com/q3330576893/Eta-por-ultra/releases/tag/v3.5.6.28)

来源提交：[`dd73acc`](https://github.com/q3330576893/Eta-por-ultra/commit/dd73acc45fcbcfdf0a8dcf132934e35649204ebe) · versionCode `2026112128`

SHA-256：`f29591efac2586681497d975ebf248f57ac5165c8dc1e636cc0381980af2653c`
<!-- release-info:end -->

下载后直接安装 `Eta-Ultra.apk`。正式版沿用应用 ID `io.github.mangi.eta`，覆盖升级取决于应用 ID、签名证书与递增的 `versionCode`。

要求：Android 13+ / ARM64。首次使用需在应用内配置模型服务，语音、无障碍、悬浮窗与 Root/LSPosed 按需开启。

## 主要能力

以下描述当前源码工作区；本轮尚未公开发布，正式下载版本以顶部元数据为准。[本地验收](docs/MCP_ECOSYSTEM_2026-11-27.md) · [酷安与公开分支对照](docs/COOLAPK_ETA_COMPARISON_2026-11-27.md)。

- **设置体验**：共用玻璃材质和高对比降级，支持大字号与可访问开关；已移除首次启动教程及设置页教程入口。

- **模型接入**：Chat Completions、Responses、Anthropic Messages、Gemini Interactions 四种协议，支持多模型对比；提示词与工具边界在全局统一管理。
- **生态连接器**：发现页默认国内常用，支持按场景浏览与搜索；31 项配置包含高德、百度地图、博查、秘塔、智谱和百炼等，提供官方接入指南。密钥自填并加密保存，需网关或套餐的服务明确标注；刷新保留最新配置与工具勾选。
- **技能扩展**：可从 GitHub 仓库安装 Skill，支持官方目录与自定义仓库；内置自我改进、技能创建、技能安装、连接器配置、Android 自动化五项。
- **语音与助手**：Vosk 离线语音、系统助手会话浮窗、屏幕理解、悬浮窗与 Root/LSPosed 集成。
- **设备能力**：天气与桌面组件、定时任务、长期记忆、文件检索、终端与 Linux 环境。

## 说明

本项目由 [q3330576893](https://github.com/q3330576893) 维护，基于 [Oo0520/Eta-por](https://github.com/Oo0520/Eta-por) 定制，原始项目为 [Mangi-11/Eta](https://github.com/Mangi-11/Eta)。设备与权限差异见 [设备支持说明](docs/ROOTLESS_SUPPORT.md)，变更记录见 [CHANGELOG.md](CHANGELOG.md)。
