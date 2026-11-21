# Agent「破甲」项目与 ETA 逆向模块 — 2026-11-21

## 本轮结论

先完成了五项功能精简并独立核验公开 `3.5.6.14`，随后才开展本页调研与第二阶段代码。天气、长期记忆、普通任务及星驰预设继续保留。

本轮将候选项目的**角色文本、宿主配置、实际执行器和验收证据**分开核对。对 ETA 的落地选择是：复用已有 Linux APK 工具档案，新增 `eta-reverse` 结构化静态分析命令和可选 Skill ZIP。模型通过原终端接口使用已有权限；全局提示词、服务商和默认开关保持原状。

当前交付范围是 APK 指纹／DEX 头概览和 JADX Java 反编译。新模块不凭角色口号宣称任意模型、任意格式和任意设备上的全部操作都已成立。

## 检索与证据边界

- 使用 GitHub 仓库检索发现 26 个匹配候选，选取下列 9 个相关项目，保存完整目录树、固定提交、README／许可与代表性入口源码；各快照目录树均未被 API 截断。
- 表中「观察」来自固定提交的具体文件。未安装这些项目的角色／补丁、未执行其安装器，也未批量调用商业模型测试提示词效果。
- `dsh-purge-ports` 引用的 `YuJunZhiXue/dsh-purge` 上游链接未成功读取，本页不对该上游当前实现作独立结论。
- 注册数、静态文本命中率、文件存在、自检口令与真实工具执行是不同证据，不能互相替代。

## 九个项目的源码对照

| 项目／固定提交 | 核对的机制与入口 | 对 ETA 的价值与边界 |
| --- | --- | --- |
| [Deva](https://github.com/sqfcyily/deva/tree/8997ecb10914f2b14134eb7e17025ff138bd43f2) · MIT | `apps/desktop/src/main/services/default-personas.ts:1–41` 把角色定义作为可播种的附加提示词；`chat-prompts.ts:43–96` 组装系统／项目／角色文本；`tool-catalog.ts:33–81` 独立登记文件、命令、浏览器、技能和成员工具。 | 可借鉴角色与工具目录分层、按需加载。所查目录没有因选择某个角色而自动获得一个新的反编译引擎；实际执行仍需工具和环境。未把其宣传中的模型表现当成 ETA 实测。 |
| [dsh-purge-ports](https://github.com/shenwei322/dsh-purge-ports/tree/b9ed9b03a06b20d77c31a302ddc62c72b9c39177) · Apache-2.0 | `bin/purge.mjs:202–263` 备份并替换宿主 Markdown 标记块；`core/presets.json` 管理不同体积的文本组合；`scripts/eval.mjs:5–6,59–81` 以关键词／槽位出现次数计算静态覆盖率。 | 可借鉴可选包、大小预算、幂等导入和备份。其覆盖率衡量文本包含哪些信号，既未调用模型，也未验证 JADX／Ghidra 的样本输出，不能当成实际「成功率」。 |
| [dsh-pojia-mode](https://github.com/z91772524-ai/dsh-pojia-mode/tree/434953be26dfc33d18c62ba85a0fe37248778480) · MIT | `unlock-dsh.ps1:341–420` 参数化并写入用户 Preset／composition；`:482–511` 检查文本标记和顶层行数。 | 可借鉴用户可选、避免覆盖默认目录及可回退的生命周期设计。该自检证明的是配置文本就位，并非某个 APK 已完成反编译。Windows 宿主目录探测不适合直接搬到 Android。 |
| [Hermes Seagull](https://github.com/laoshu666/hermes-seagull/tree/ad1d49e3bfdbaf552efc379d5a9537c319294c41) · MIT | `seagull-armor-break/__init__.py:56–87` 注册系统提示词变换和 turn hook，按标记去重后拼接文本；问候钩子只记录激活信号。 | 展示了提示词插件钩子；所查注册函数没有新增二进制分析工具。对 ETA 适合采用显式 Skill 工作流，而非把问候回执当作工具安装或分析成功。 |
| [ArmorBreak](https://github.com/LYL-Z/dsh-armorbreak/tree/fa493b527ce078873dbcb2fc95d0e5e2d71cf496) · MIT | `packages/core/src/capabilities.js:57–126,162–189` 加载能力清单并关联依赖状态；`lib/analyzers/android/jadx.js:124–156` 调真实 JADX、检查 Java 输出、输出有限索引并清理临时目录；另有 CLI／MCP 适配。 | 可借鉴明确的能力契约、依赖探测、任务证据和失败状态。当前 main 为 `2.4.0-preview.1`，README 将已发布 Stable 与开发候选区分；仓库简介 39、历史正文 46、package 描述 53 不是同一个验收口径。本轮未安装或验收其整套运行时。 |
| [REA](https://github.com/morluto/rea/tree/a9f8c70dbf0cefa606dc2c76497ea3c9c0b68d87) · MIT | `docs/guides/android/android-analysis.md` 描述 APK／类／方法／引用和独立 JADX provider；本轮实际调用包、类、方法分析，保留产物哈希与 Evidence。 | 可借鉴静态观察／推断／未知分层、源身份、分页和证据导出。宿主 REA 运行成功证明这台分析主机的工具链可用，不代表 ETA 已经启动该服务或手机端获得同样环境。 |
| [JADX](https://github.com/skylot/jadx/tree/f84358d6a8e8cd2418c674a178f1653e85197ed0) · Apache-2.0 | 官方 CLI 与 `jadx-cli/.../JadxCLIArgs.java` 是实际 DEX→Java 执行器；ETA 已有档案固定 1.5.6。 | 本次直接复用已校验的官方引擎，不把大 JAR 再塞进 APK。真实发行包 `lib/jadx-1.5.6-all.jar` 的默认 Main-Class 是 GUI，官方 `bin/jadx` 使用 `jadx.cli.JadxCLI`；新桥明确固定 CLI 类和 headless 参数。 |
| [JADX-MCP-Server](https://github.com/zinja-coder/jadx-mcp-server/tree/a8e5b7de4f7d7f9ef0ccea9d063631133105bd34) · Apache-2.0 | `jadx_mcp_server.py:84–91,413–483` 注册工具、连接 JADX-GUI 插件并支持 stdio／Streamable HTTP。 | 可以成为已经运行的外部分析端；ETA 现有客户端能手动连接 HTTP。它不等于手机已具备 GUI 插件、stdio 启动器或样本传输通道。本轮选择本地 CLI，保留这一路作为独立候选。 |
| [GhidraMCP](https://github.com/LaurieWired/GhidraMCP/tree/1e980252087b2479a21b9ca6d82d5829538651a7) · Apache-2.0 | README 的当前入口同时支持 Ghidra 插件和 PyGhidra headless；可通过 `--transport streamable-http` 暴露 MCP，后端依赖 Ghidra 12.1.4 等环境。 | 是原生二进制分析的候选伴随服务，不按旧资料简单称为「仅 GUI」。它与 ETA 的 HTTP MCP 有协议接点；手机端依赖、样本 staging 和具体 native 分析尚未由本轮实测，未默认嵌入。 |

许可列记录仓库当时声明及所取许可文件。ETA 新增代码沿用本仓库 PolyForm Noncommercial License；本次没有复制这些项目的角色正文或补丁，也没有重写它们的许可。

## ETA 原有基础与本次新增

### 原有基础

- `LinuxApkAnalysisInstaller` 已为 Alpine／Debian 安装固定官方制品并校验 SHA-256，已有 JADX 1.5.6、Apktool 3.0.3、smali/baksmali 3.0.10 和 JDK 25。
- `terminal(action=exec, environment=linux)` 已有真实身份／后端／工作区事实、开关检查、取消、超时和输出控制。
- Skill ZIP 已能经用户选择安装。内置 manifest 则按现有规则自动播种和启用，因此本模块选择可选 ZIP，不新增默认启用角色。
- MCP 表单连接已运行的 Streamable HTTP 服务；没有通用 stdio 管理、`.mcp.json` 导入或本地样本上传能力，不能把外部服务当成手机端本地执行器。

### 新增实现

```mermaid
flowchart LR
  A[用户选择安装 Skill 并指定样本] --> B[既有 terminal 一次性 exec / Linux]
  B --> C[原终端权限与环境检查]
  C --> D[重任务识别 eta-reverse]
  D --> E[JDK 源码模式 helper]
  E --> F[固定 JADX CLI / headless]
  F --> G[任务报告 / Java 工件 / SHA-256]
  G --> H[既有文件工具按当前环境读取]
```

- `app/src/main/assets/reverse/EtaReverse.java`：`probe / inspect / decompile`，UTF-8 单行摘要、真实 JDK/JADX 版本、输入快照与指纹、受管工作区、固定参数数组、超时／日志／输出预算、临时工具配置归属、原子报告及工件哈希。
- APK 工具档案升级为 revision 2，安装 helper 和 `eta-reverse` 包装，验证后发布标记，失败／取消保留旧工具；新命令纳入既有一次性终端重任务识别。外部工具版本和 APK 回编译限制保留。
- `plugins/eta-reverse-analysis` 是可导入技能包；包内 Java 源码便于审阅，真正 guest 命令由工具档案安装，避免把宿主 Skill 路径误传进 Linux。
- CLI 的 `ready` 只代表版本探测，`completed` 要求真实进程正常退出、存在 Java 工件且输入／引擎身份一致。空输出、部分输出、超时、超限和变化都不当作成功。

## 实际验证与未验证

### 已完成的宿主实证

- 官方 JADX 1.5.6 ZIP：72,646,741 字节，SHA-256 `545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974`；同一 CLI JAR SHA-256 `fe3e12c45acf75f92369685fd02d1d7a7323385dc725680a9b98a0dac0ea554b`。
- Windows/JDK 25.0.5 的 11 项合成引擎契约测试通过：错误 JAR 默认入口、UTF-8／特殊路径、工作区边界、非 APK／大输入、部分／空输出、超时、受管输出清理、输入变化、报告及工件哈希、配置缓存归属等。合成引擎结果不冒充 JADX 实测。
- 另用 JDK/D8/AAPT2 构造自有最小 APK，再实际调用上述官方 JADX，检查 `io.eta.reverse.fixture.EtaSample` 的反编译 Java 中已知 `return 42`、退出码 0 和工件哈希；APK 没有被执行。
- 同一 helper 检查公开 v3.5.6.14 APK 的 SHA-256，与公开发布记录一致。
- REA/JADX headless provider 0.7.1 对公开 v14 APK 真实完成包信息、类结构和 `WeatherWidgetSettingsActivity.onCreate` 方法反编译；R8 混淆名仍保留。完整本地证据为 6 个记录，关键 parent Evidence：`ev_c2b379a6e6e363938e97a46d02bda0092d6da1c30c8294dee9d18f7cd0432bb7`、`ev_72c02065c80bebb67b9ef2903e779bdc778d6e3ae4e30c809a0924ede33d0f7b`、`ev_aa51551df6e690b52496d1f6a191d04ca5f84e001ab59d7c4ff3826c2881a77d`。

### 交付时继续记录

Android 客户端单测、可选 ZIP 导入、完整 Lint／构建、Linux CI 的真实 JADX fixture、正式 APK 和插件附件以本轮发布核验记录为准。CI 新增合成契约、可选包及真实引擎三类验收，继续保留天气与已退休功能的门禁。

没有执行 Android ARM64 真机／ROM／实际工作区安装验收，没有执行 APK 动态运行／注入，也没有把某个商业模型的输出边界改变量化为已通过。后续扩展原生二进制、远程 Ghidra 或更多样本，需要各自的环境与具体证据，不能从注册表或角色文本推导已就绪。
