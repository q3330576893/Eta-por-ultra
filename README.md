# Eta Ultra

面向 Android 的系统级 AI 智能体与语音助手：透明底部胶囊连接免费系统语音、屏幕理解、工具调用与多步任务。

[English](README_EN.md) · [最新版](https://github.com/q3330576893/Eta-por-ultra/releases/latest) · [全部版本](https://github.com/q3330576893/Eta-por-ultra/releases) · [问题反馈](https://github.com/q3330576893/Eta-por-ultra/issues)

## 下载

<!-- release-info:start -->
**最新正式版：[3.5.6.26](https://github.com/q3330576893/Eta-por-ultra/releases/tag/v3.5.6.26)** · Android 13+ · ARM64

[下载签名正式 APK](https://github.com/q3330576893/Eta-por-ultra/releases/latest/download/Eta-Ultra.apk) · [历史版本](https://github.com/q3330576893/Eta-por-ultra/releases) · [本版更新说明](https://github.com/q3330576893/Eta-por-ultra/releases/tag/v3.5.6.26)

来源提交：[`b0885c0`](https://github.com/q3330576893/Eta-por-ultra/commit/b0885c0c1a0706a50e669f75e3a3627b3318a7f4) · versionCode `2026112126`

SHA-256：`481f4669bacf8fbaa846778fd234ddc9afd715de971743da3754b9f6e620a6c2`
<!-- release-info:end -->

下载后直接安装 `Eta-Ultra.apk`。正式版沿用应用 ID `io.github.mangi.eta`，覆盖升级取决于应用 ID、签名证书与递增的 `versionCode`。

要求：Android 13+ / ARM64。首次使用需在应用内配置模型服务，语音、无障碍、悬浮窗与 Root/LSPosed 按需开启。

## 主要能力

- **模型接入**：Chat Completions、Responses、Anthropic Messages、Gemini Interactions 四种协议，支持多模型对比；提示词与工具边界在全局统一管理。
- **生态连接器**：MCP 连接器目录含 23 个常用服务模板（Notion、Linear、GitHub、GitLab、Sentry、数据库、Slack、飞书、QQ、微信、企业微信、钉钉、Telegram、Discord、Google Drive、OneDrive、Office、PDF 等），点选填入端点，密钥自填。
- **技能扩展**：可从 GitHub 仓库安装 Skill，支持官方目录与自定义仓库；内置自我改进、技能创建、技能安装、连接器配置、Android 自动化五项。
- **语音与助手**：Vosk 离线语音、系统助手会话浮窗、屏幕理解、悬浮窗与 Root/LSPosed 集成。
- **设备能力**：天气与桌面组件、定时任务、长期记忆、文件检索、终端与 Linux 环境。

## 说明

本项目由 [q3330576893](https://github.com/q3330576893) 维护，基于 [Oo0520/Eta-por](https://github.com/Oo0520/Eta-por) 定制，原始项目为 [Mangi-11/Eta](https://github.com/Mangi-11/Eta)。设备与权限差异见 [设备支持说明](docs/ROOTLESS_SUPPORT.md)，变更记录见 [CHANGELOG.md](CHANGELOG.md)。
