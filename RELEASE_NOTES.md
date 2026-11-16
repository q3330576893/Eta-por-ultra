## 3.4.1 · Eta Ultra 正式发布流程

### 本次更新

- 语音助理采用 Gemini 风格全屏蓝紫渐变、底部四色流动声波与胶囊输入栏。
- 顶部显示连接、聆听、识别、处理、执行工具、朗读等真实状态；屏幕来源可展开查看采集时间。
- 增加当前屏幕上下文开关、键盘切换和完整对话入口；Shader 不可用时采用 Canvas 波形回退。
- 应用名称、源码、反馈、更新检查和下载入口统一到 `q3330576893/Eta-por-ultra`。
- 每次推送 `main` 自动构建并验证签名 Release APK，发布 `Eta-Ultra.apk`、SHA256 和版本信息；中英文首页同步最新下载与来源提交。
- 签名证书由本仓库独立保存并通过加密 Actions Secrets 提供，后续正式版本沿用同一证书。

### Changes

- Gemini-inspired full-screen voice assistant with blue/violet ambient gradients, flowing four-color waves and a capsule composer.
- Live listening, recognition, processing, tool and playback status; expandable screen-source timestamp and context toggle.
- Unified keyboard/voice controls and conversation handoff, with Canvas waveform fallback when the shader is unavailable.
- Eta Ultra branding, source, feedback, in-app updates and downloads now point to this repository.
- Every `main` push produces a signed, R8-optimized Release APK, checksum and release metadata; both README download panels track the published source commit.
- Stable releases reuse this fork's dedicated certificate, provided to CI through encrypted Actions Secrets.

后续变更应继续维护本文件与中英文功能简介；CI 负责实际版本、提交、校验和及下载区的自动更新。
Future changes must update these notes and both feature descriptions; CI updates version, source commit, checksum and download panels.

### 验证记录

- 定向回归：`EtaVoicePanelTouchTest`、`EtaAssistantScreenContextTest`、`AppUpdateCheckerTest`、`EnhancementSettingsHistoryTest` 通过。
- `lintDebug`：建立继承代码问题基线后通过，新增 Lint 问题清零。
- `assembleRelease`：签名证书、包名、Release 属性与版本号由发布脚本和 CI 验证。
- 完整 JVM 测试套件在本机 Windows/JDK 25/Robolectric 环境下仍有继承模块的历史失败；CI 保留完整报告，正式发布以定向回归、编译、Lint、签名和 APK 身份校验为准。
