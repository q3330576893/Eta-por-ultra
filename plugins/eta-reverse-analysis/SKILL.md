---
name: eta-reverse-analysis
description: 对用户明确选择并导入当前工作区的 APK 做静态指纹、DEX 概览与 JADX Java 反编译，按真实版本、退出码、报告和工件哈希验收。需要启用终端、Linux 环境和含 eta-reverse 的新版 APK 分析工具档案；不运行 APK。
---

# ETA 逆向分析

## 使用前

1. 先确认用户指定的样本和分析目标；仅使用这个样本。读取当前终端事实，采用其中实际就绪的 Linux 环境和 `/workspace` 映射。
2. 通过既有 `terminal` 工具、`environment=linux` 调用。终端关闭、环境未就绪或安装缺失时说明实际状态；本技能不授予 Root、不开新服务、不更换后端、不改模型协议与全局提示词。
3. APK 必须已位于选定工作区内。文件选择器导入到 PRoot 私有工作区的文件不能假定在 Chroot 可见；路径不通时停止并说明映射，等待用户明确导入到正确位置。不要遍历其它应用私有目录或猜测目标。
4. 先执行 `eta-reverse probe`。它真实运行已安装 JADX 的版本命令并记录 JAR SHA-256；文件存在或安装标记不等于样本处理成功。缺少 `eta-reverse` 时，用户可在「Linux 工具环境 → 可选工具 → APK 分析」显式更新工具档案。

## 操作

下面是参数示例，不是已执行结果。把路径替换为当前已确认的样本；传递路径时按当前 Shell 正确引用。工具内部使用固定参数数组启动 Java/JADX，不接受自由命令。

### 指纹与静态概览

`eta-reverse inspect --workspace /workspace --input /workspace/imports/SAMPLE/sample.apk`

记录输入 SHA-256、字节数、ZIP/DEX 头声明与 native ABI。`manifest_decoded=false` 或 `signature_verification=not_performed` 必须保留其含义，不能据此宣称签名通过、原始 Kotlin 恢复或运行行为得到验证。

### Java 反编译

`eta-reverse decompile --workspace /workspace --input /workspace/imports/SAMPLE/sample.apk --class com.example.MainActivity --timeout-seconds 120 --max-output-mb 64`

优先分析用户指定或已有证据确定的类；省略 `--class` 会尝试整个 APK，大包可能超时或超限。固定使用 JADX、不解码资源、不执行 APK，单个任务使用独立目录和输入快照。当前输入上限 256 MiB；执行、日志、输出和工件摘要有界。它是静态反编译工作流，不是 APK 回编译或动态注入工具。

## 验收与报告

- 同时检查终端真实退出码与助手 JSON：只有 `ok=true`、`status=inspected/completed` 才可说明对应步骤完成。`probe` 的 `ready` 仅说明工具版本探测通过。
- 阅读 `report_path` 指向的当前 Linux 工作区报告，用文件工具或 `sha256sum` 核对 `report_sha256`。报告记录输入指纹、实际引擎/JAR 版本与哈希、退出码、超时/资源状态、工件路径和哈希。
- `partial`、`timed_out`、`resource_limit`、非零退出码、空源码、输入/引擎变化和截断都保持为失败或不完整观察。不得把口令回执、术语覆盖、文件夹存在或先收到部分文本当成闭环成功。
- 生成 Java 是反编译表示；混淆名、缺失信息、编译器合成代码和 JADX 警告应明确展示，不能声称它就是原始源码。
- 只读取与本次目标有关的有界工件，沿用现有文件引用格式。结果继续位于当前环境/工作区，不把 Linux 路径直接当 Android 路径打开，不上传样本。
- 仅当用户继续要求时，利用既有文件/终端工具跟踪调用、比对代码与整理证据。保留输入、工具与产物身份，避免再次执行已经完成的昂贵步骤。

本技能不改变天气、长期记忆、普通任务或服务商设置，也不重新引入文档知识库、本地备忘、通知栏指令、NFC 任务和规则试跑。
