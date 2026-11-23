# 2026-11-23 服务优化记录

## 背景

用户要求参考同类 AI Agent 项目对 Eta 现有服务做一轮极致优化，并指定用 AgentTeams 多人协作、模型统一使用 `magpie/bailu/bailu-2.8` high 路由。

## 协作通道限制（如实记录）

- 本会话子代理路由白名单不接受 `magpie/bailu/bailu-2.8`：`subagent` 与 AgentTeams 成员均返回 `child LLM route ... is not allowed for this Session`。
- 其余可用通道 `custom-xingchiapi` 缺少 `CUSTOM_XINGCHIAPI_API_KEY`，成员启动即失败；`custom-2..6`、`xing` 不接受该模型。
- 因此本轮由 captain（bailu 2.8）直接执行，未虚构多人协作结果。已归档无法启动的团队 `eta-optimize-20261123`。

## 本轮实际优化

### 1. 冷启动主线程阻塞移除

`EtaApp.onCreate` 原先用 `runBlocking(Dispatchers.IO)` 同步读取 DataStore 的预测返回设置，属于启动关键路径上的磁盘等待。

改为：先按默认关闭应用预测返回，随后在 `applicationScope` 后台读取真实设置并修正。冷启动不再等待 DataStore。

文件：`app/src/main/kotlin/io/github/mangi/eta/EtaApp.kt`

### 2. 启动路径重复初始化移除

`AgentRequestSnapshot.init` 在 `onCreate` 中被调用两次，第二次重复扫描快照目录。保留早期一次（覆盖所有进程），移除主进程内的重复调用。

### 3. 高频重复日志合并

`EtaTrace.log` 每次调用都同步打开/追加/关闭文件。诊断包显示曾出现 1194 条相同失败日志。

改为：相同 `area+message` 在 1 秒窗口内只落一条，并在下一条日志中补记“此前相同内容 N 次已合并”。既保留重复计数，又避免写盘放大。

文件：`app/src/main/kotlin/io/github/mangi/eta/core/EtaTrace.kt`

### 4. 诊断自动导出可靠性（上一轮延续）

- 共享目录不可写时回退到应用私有 `files/diagnostics-auto`
- 成功按周推进，失败按周退避，不再每分钟重试
- 自检页显示真实导出目录
- 行为核查把成功与失败分开统计

文件：`core/DiagnosticsAutoExport.kt`、`core/DiagnosticsAutoExportPolicy.kt`、`core/BehaviorAudit.kt`、`ui/screens/selfcheck/FeatureSelfCheckScreen.kt`

## 参考的同类项目通用做法

同类 Agent 项目（opencode、cline、aider、kilo code、continue、goose 等）在启动与可观测性上的共同模式：

1. **延迟非关键初始化**：Application 启动只做必须同步的事，其余放入后台作用域。
2. **日志去重与分级**：高频失败不逐条刷盘，按窗口合并并保留计数。
3. **失败退避**：可重试任务失败后指数/固定退避，避免忙循环。
4. **可写目录探测与回退**：外部共享目录不可写时回退应用私有目录。

本轮按这四条落地，未复制任何第三方角色正文或安装脚本。

## 额外优化（多代理并行轮）

- 请求消息 `forChatCompletions` 从三趟遍历合并为一趟，system 消息不再做无意义深拷贝。
- 请求快照把约 200KB 的 JSON 序列化移到后台写盘线程，缩短模型请求关键路径。
- `Chat Completions` 构建请求时复用同一份消息投影，避免 Hook/XAI 分支重复深拷贝。
- `ProviderRequest.effectiveConfig` 改为惰性计算，避免四协议/snapshot/retry 重复派生配置。
- Hook token 估算与工具 token 估算加入有界缓存；内容/原地修改会正确失效。
- 内存闸门一次读取 `/proc/meminfo` 同时得出档位、上限与摘要，消除同一次准入的重复读取。
- 工具 capability 与 requirement 的常量集合外提，路由分支复用已读 function 对象。
- Compose `@` 引用候选只扫尾部 80 条，`contextUsage` 不再逐 token 物化整份倒序列表。
- 诊断包按天日志只读一次并供 `trace.txt`/行为核查共用；行为核查解析不再逐行 `split` 与反复构造时间格式化器。
- OAuth 凭据会话从启动主线程构造改为后台预热/首次使用构造。

## 验证

- 定向回归：359 项，0 失败，0 错误，2 项既有 Windows 平台跳过
- 完整回归：2525 项，0 失败，0 错误，71 项平台跳过
- 强制门禁：48 个必过套件、75 个关键用例无跳过通过
- Lint 与 Debug 构建通过
- 源码指纹在构建前后一致：`671b18c97cf0b89c93ef36a8289e8ee63f5a96b7724503da9a67e668698baf09`

## 未做与边界

- 未做请求链深拷贝重构：`OpenAiRequestMessages` 的逐条深拷贝与 `ChatToolCallHistory.repairCompleteBatches` 耦合，改动风险高于本轮收益，留待专项。
- 未做 Compose 重组专项：缺少真机帧率数据，避免无依据改动。
- 真机/OEM ROM、Linux 专属描述符写入、远端模型效果仍未验证。
- 本轮未提交、未推送、未发布。
