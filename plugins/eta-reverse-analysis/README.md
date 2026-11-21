# ETA Reverse Analysis · 0.1.0（实验）

这是 ETA 的可选逆向分析技能包，配合本次客户端集成的 `eta-reverse` 工具使用。它为已经存在的 JADX/Apktool 环境增加结构化验真与报告，不修改服务商、模型权重、全局提示词或终端权限。

## 在手机上使用

1. 安装包含本模块的新版 Eta 客户端。旧 `3.5.6.14` 的 APK 分析档案尚不提供 `eta-reverse`；仅重装技能不能补齐执行器。
2. 在现有 Linux 工具环境中安装基础工具，再显式安装或更新 **APK 分析**。档案修订号为 2；继续使用已固定校验的 JADX 1.5.6、Apktool 3.0.3、smali/baksmali 3.0.10 和 JDK 25。
3. 在 Skills 页导入 `Eta-Reverse-Analysis.skill.zip`。这是用户选择安装的技能，不是随升级自动启用的全局角色。
4. 按需启用既有终端工具，把用户指定的 APK 导入当前环境可读的工作区。PRoot 与 Chroot 的工作区和权限保持原样；不假定某一侧文件自动出现在另一侧。
5. 对助手提出具体任务和样本路径，例如“对已导入工作区的这个 APK 做静态概览，并反编译我指定的类”。助手应先执行 `eta-reverse probe`，再执行 inspect/decompile 并核验报告。

宿主 `filesDir/skills` 不是 Linux guest 挂载。包内 `assets/EtaReverse.java` 是便于审阅的源码资源；实际执行命令由新版客户端的 APK 工具档案安装到 guest，不把宿主 Skill 路径直接当 guest 脚本执行。

## CLI

```text
eta-reverse probe
eta-reverse inspect --workspace /workspace --input /workspace/imports/SAMPLE/sample.apk
eta-reverse decompile --workspace /workspace --input /workspace/imports/SAMPLE/sample.apk --class com.example.MainActivity --timeout-seconds 120 --max-output-mb 64
```

使用现有一次性 `terminal(action=exec, environment=linux)`。反编译调用建议设置终端超时 180000 ms，留出工具初始化和报告时间；助手自身的子进程超时上限为 150 秒。该命令被加入既有重任务识别；后台任务、守护进程和已有长会话保持各自的原有执行行为，不另作全部串行的承诺。

工具使用 JDK 源码模式，无 Python、Node 或新服务依赖。JADX 发行包的 JAR 默认 Main-Class 是 GUI；本工具固定使用官方 `jadx.cli.JadxCLI` 类与 headless 参数，不通过 `java -jar` 猜入口。子进程偏好与缓存放在本任务临时目录，不加载个人 JADX 配置。

## 结果与边界

- stdout 为 UTF-8 单行 JSON，小摘要包含真实状态、输入 SHA-256、退出码、报告路径和报告 SHA-256。完整报告及工件位于工作区 `.eta-reverse/run-*`。
- 输入必须位于明确选定的工作区，先复制为独立快照并计算指纹；不执行 APK。原样本不被更改，收尾删除快照。
- inspect 返回 ZIP 条目、支持的传统 DEX 头声明与 native ABI；不解码二进制 Manifest、不验证 APK 签名、不把声明数当运行事实。尚未支持的 DEX 头明确标为 unknown/unsupported。
- decompile 返回 JADX 的 Java 表示，混淆名及合成代码可能保留；未声称恢复原始 Kotlin。只有引擎正常退出、输出非空且输入/引擎身份未变化时，状态才是 completed。
- 超时、部分输出、非零退出、空源码、超限和身份变化均保持失败或不完整状态。输出/缓存/日志采用运行时轮询预算；超限停止并清理受管输出，不能把它描述成操作系统硬磁盘配额。
- 输入上限 256 MiB；默认输出预算 64 MiB，最大 256 MiB；日志、摘要与工件列表分别有界。不要把外部 APK 资源直接作为命令执行。
- 本模块交付静态 APK 指纹／概览和 Java 反编译。资源回编译、签名和设备运行使用各自独立工具链；当前手机 ARM64 档案原有的 `apktool build` 限制继续保留。

## 验证

仓库提供两类独立验证：

- `scripts/test_reverse_helper.py`：用明确的合成引擎测试路径、UTF-8、错误入口、进程超时、输出预算、输入/报告身份、缓存归属和失败状态；这些不冒充真实 JADX 分析。
- `scripts/smoke_reverse_helper.py`：用 JDK/D8/AAPT2 构建自有最小 APK，再用与手机档案完全相同且经官方制品 SHA-256 校验的 JADX 1.5.6 反编译，核对已知类、`return 42`、真实版本、退出码和文件哈希，并静态检查已公开 ETA APK。

已完成的宿主测试与 REA 静态证据均不是 Android ARM64 真机验收。手机端安装后以实际 probe、选定样本和报告为准；未进行的设备级测试会在本轮交付记录中保持说明。

许可沿用仓库 `LICENSE`（PolyForm Noncommercial 1.0.0）。JADX 等外部工具保留各自许可和安装来源，本技能包不重新打包外部引擎 JAR。
