# Eta Ultra 3.5 验证记录

环境：Windows x64、JDK25、Android SDK37、Gradle9.7.1、Robolectric4.17／SDK36。当前未连接 Android 设备。

## 自动检查

- 最终集中回归：49个测试类、333个用例，0失败，1项 Android RuntimeShader 硬件编译测试跳过；Gradle执行成功。
- 主源码及全部测试源码编译通过。`lintDebug`执行成功，0个新增错误、51条警告；既有Lint基线过滤55个错误、580条警告和18条提示，未刷新基线掩盖新增错误。
- 全量检查执行2221项，初次150项失败、2项跳过。随后在独立工作树对旧正式提交 `26d471b` 使用相同环境执行2020项，160项失败、2项跳过；本轮初次失败中的147项在旧提交逐项复现。
- 本轮新增的另外3项失败为计算舍入边界期望和审批静态夹具污染，修复后均在333项集中回归中通过。旧提交的13项语音／设置失败在新版本中已通过。全量套件仍有存量失败，结果与本轮通过的集中回归分开记录。
- GitHub Linux 构建节点的最终全量报告：2221项、70项失败、1项跳过；Lint为0个新增错误、65条警告。70项失败中69项已在旧正式提交的Windows对照测试中复现；另1项为 `AgentTaskSchedulerTest.refreshingRulesDoesNotReplaceAnExecutingJob`，相关生产代码与测试本轮均未修改，原因尚未在相同Linux基线环境复核。
- 发布前重点回归、正式构建、签名与包内容校验全部通过。全量检查步骤使用既有 `continue-on-error` 收集报告，因此流水线整体成功不表示2221项全部通过。云端报告保存在[本次构建记录](https://github.com/q3330576893/Eta-por-ultra/actions/runs/44010166509)。
- Vosk模型归档：43,898,754字节，SHA-256 `3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba`，与固定manifest一致。
- 新增独立资源XML通过解析，当前检查未发现重复key。两份README各保留一组release-info边界标记。
- JNA官方AAR classes.jar的META-INF/LICENSE原文随包保存；Vosk和模型Apache-2.0全文、来源与哈希清单一并保存。

## 关键回归范围

- 胶囊空闲高度、回复高度、拖拽、键盘Insets、关闭和焦点时序。
- 系统助理onReady、keyguard、requestSession、refresh、shutdown及Manifest零Activity中转；触摸Region使用真实android-all内部Insets回调，检查四边界、IME留白、替换、空区域、幂等attach和close。
- 免费识别回退、模型安全展开、系统TTS初始化／取消／超时、实际播放进度与续读、逐词JSON范围、语音时间线有界、连续录音生命周期与回声隔离。
- 计算语法边界，天气缓存与城市选择，日历权限／今日半开窗口／DST／20行上限／游标取消，闹钟计时器交接状态。
- run隔离、请求尝试、未知指标、计划回执、经验卡版本与信任、重复重启恢复、精确owner/run/token审批和UI／分发器竞争。

## 主机真实识别烟测

既有 Windows Vosk0.3.45 x64烟测已实际加载模型：加载0.602秒；5秒静音空结果。WeNet AISHELL语音样例4.281秒，解码2.169秒，18条非空partial，最终文字为“广州市 房地产 中介 协会 分析”。这是主机解码证据，Android包使用Vosk0.3.75，麦克风／JNI／中文准确率仍需实机测量。

## 设备与性能边界

厂商默认助理／锁屏／长按电源键、Android前台麦克风条件、实际跨应用触摸穿透、输入法、AudioRecord／AEC／耳机路由、端到端延迟与耗电尚待实机。Baseline Profile为人工整理的启动规则，最终APK需检查生成profile；未给出测量加速百分比。低内存策略为关闭Shader并限制波形更新频率。

连续语音为本机STT→已配置模型→TTS；RMS与文字后缀提供端点提示。神经语义端点、WebRTC服务、端到端语音模型、说话人分离和后台持续热词不在本版实现。Vosk逐词时间是识别器本轮偏移，TTS高亮来自实际播放事件，二者身份独立。

## 正式包检查

已公开发布 [3.5.0.4](https://github.com/q3330576893/Eta-por-ultra/releases/tag/v3.5.0.4)，versionCode `2026111705`，源码提交 `517d08785cd120d86e4252ba700b871b84f37ca3`。公开APK已重新下载并独立核验，大小59,536,691字节，SHA-256为 `8567ae7d6586aaf8500459ae3e1b5ab8bc06f687ec7dd22f73aeb4e7b8a6f553`。

发布证书SHA-256保持 `8450d3f45958265e3a7d9bec03cb45f4c843dffb7262d6661b0803949c815814`。签名、包名、版本、不可调试、ARM64、模型归档、Vosk／JNA全部LOAD段16KiB对齐及 `assets/dexopt/baseline.prof` 检查通过；旧助理中转Activity、本地大模型中心及专用支付资源残留检查通过。Release附有APK、SHA256SUMS、release-info和签名记录。仓库首页版本区、简介及下载主页已同步。
