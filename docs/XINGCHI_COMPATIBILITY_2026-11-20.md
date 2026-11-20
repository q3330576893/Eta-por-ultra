# 星驰 API 接入与 403 诊断

## 本轮实现：四种手动兼容预设

2026-11-20 12:14–12:26（UTC+8），使用当前明确提供的同一连接，在 `/v1/responses`、实际目录返回的 `gpt-6.1-sol` 上逐一完成以下**精确 ETA HTTP 兼容配置**的文本、标准 SSE、`eta_echo(text=ping)` 函数调用与工具结果回放。文本终态为 completed/OK，回放终态为 completed/DONE；没有额外 originator 头。

| 预设 | 精确 User-Agent | 三步闭环通过时间（UTC+8） |
| --- | --- | --- |
| OpenCode | `opencode/1.19.6 (EtaUltra compatibility)` | 2026-11-20 12:14:38 |
| WorkBuddy | `WorkBuddy/1.0.0 (EtaUltra compatibility)` | 2026-11-20 12:26:19 |
| Codex | `codex_cli_rs/0.114.0 (EtaUltra compatibility)` | 2026-11-20 12:21:40 |
| DeepSeek Harness | `deepseek-harness/1.0.0 (EtaUltra compatibility)` | 2026-11-20 12:22:22 |

- 默认 `Eta` 和 WorkBuddy/Codex/DSH 的三个无版本候选分别返回 403 `client_not_allowed`；这不是完整网关分类算法的结论。
- 首次版本化 WorkBuddy 请求收到 HTTP 200 与文本 OK，随后 `response.failed` / `upstream_error`，该次明确判为失败并保留。使用相同精确串单次受控复测后，文本、函数及结果回放成功。应用不自动轮换预设，不把 HTTP 200 或部分文本当作成功终态。
- WorkBuddy/DeepSeek Harness 的 `1.0.0` 是本次明确测试的兼容候选串，不声称厂商当前二进制版本；Codex 的 `originator/version` 形态来源为 [OpenAI 固定 rust-v0.114.0 源码](https://github.com/openai/codex/blob/rust-v0.114.0/codex-rs/core/src/default_client.rs)。没有启动四种原生客户端，不能由此推导应用级等价。

### 2026-11-21 交付前真实复验

00:23–00:25（UTC+8），使用本任务原始用户消息中的同一连接再次逐项请求。四个精确预设均分别取得 200 模型目录、completed/OK 文本、completed 的 eta_echo 调用和 completed/DONE 结果回放；完整 16 个请求全部通过，本次无需重试。仍使用目录实际返回的 gpt-6.1-sol、stream=true、store=false、完整 typed 上下文回放，不发送 previous_response_id。认证值只在进程内使用，原始及本次脱敏证据分开保留。

### 操作与保存语义

1. 编辑精确 `https://api.xingchiapi.com` 的 OpenAI-compatible 或 Custom API Key 连接。仅 HTTPS、443、无 userinfo/query/fragment 的精确主机匹配；相似域名、其他端口、OAuth、Anthropic/Gemini 均不启用入口。
2. 使用“客户端兼容预设”手动选择四项之一。**选择只替换并去重 User-Agent**，不会改 URL、协议、密钥或其他自定义字段。新连接如需 Responses，另点“补齐星驰兼容配置”或手动选择协议。
3. 原“补齐”仍把根地址补成 `/v1`、协议设为 Responses，仅在缺少非空 UA 时加入旧 OpenCode 默认值；重复应用幂等，已有非空自定义 UA 完整保留。
4. 保存后重新打开，选择状态直接从该连接已持久化的请求头推断。旧 OpenCode 自动识别；未知值、缺失值或多条歧义 UA 显示“自定义／当前标识”或“默认 Eta 标识”。首项仅表示当前状态，不清空请求头；自定义内容继续在下方请求头编辑。
5. 无独立全局预设或新数据库字段。两个同 host 不同连接 id 的配置各自保存；未保存的草稿切换不会更新持久化连接。其他服务商默认保持原状。

### 本轮验证与交付范围

本轮新增选择/全组合切换/去重/未知自定义值/实体 JSON 往返/精确主机隔离回归，真实 catalog 与 Responses 客户端的 loopback 测试参数化到四种精确 UA，并检查单一请求头、首增量、完整 typed 工具回放及 failed 终态。36 项兼容、协议与权限测试全部通过。另新增两项真实 ProviderRepository/Room 集成测试：四预设逐项保存并关闭重开数据库，验证同 host 不同 id 连接、密钥、模型和其他头互不串用；未保存草稿不落库，未知自定义 UA 保存重开后保持。两项精确方法均加入发布强制用例，Release 聚焦回归也包含其所在 suite。

2026-11-21 00:38–00:42（UTC+8）本地全量测试重新执行：2,450 项，2,380 通过、0 失败、0 错误、70 项平台跳过；Lint 和 Debug 构建成功。Windows 上既有原生 Unix KB 用例因平台能力跳过，严格门禁仍保留，公开发布须由 Linux CI 实际执行通过。独立只读审查通过；真机的 Compose 旋转、取消和重开界面测试未执行，数据库重开证据不冒充设备生命周期验证。旧 3.5.6.12 只有原 OpenCode 模板；本轮选择器须以本次源码提交构建的正式包验收。原生成员请求的零 usage/length 故障根因仍未确定，本轮未声称修复模型预算或环境。

English: Four exact ETA-attributed HTTP presets passed live text/SSE, fixed-function and result replay on November 20, and another complete catalog/text/function/replay delivery check on November 21, 2026. The historical WorkBuddy upstream failure remains recorded. Manual selection changes only User-Agent; initial completion separately selects Responses and preserves nonblank custom identities. A fresh local run executed 2,450 discovered tests with zero failures/errors and 70 platform skips; Lint and Debug assembly passed. Real repository/database-reopen tests prove same-host connection isolation and custom persistence. Linux CI must independently pass all mandatory cases before publication. No native-client equivalence or Android device lifecycle verification is claimed.

## 此前 OpenCode 单模板记录：已完成与实际限制

第一阶段的功能版已先公开发布为 **3.5.6.11**，源码提交 `37c73ec54c690280d98378f0360e8b3042b702fa`；之后才开展本项适配。

Eta 复用既有 OpenAI-compatible 连接、自定义请求头、模型目录同步与 Responses 客户端，增加显式兼容配置入口和准确的权限错误分类。没有改其他服务商的默认客户端标识，也没有引入额外本地代理。

**2026-11-20 09:07–09:13（UTC+8）换用新 Key 后，已完成真实目录、Responses 流式回答和函数调用闭环验证。推理接口默认 Eta 标识被拒，现有兼容模板可通行。旧 Key 的未分组问题保留为历史对照。**

## 新 Key 的真实推理复测

同一新 Key、同一模型与请求内容，仅改变客户端标识：

| 检查 | 结果 |
| --- | --- |
| `/v1/models`，默认及兼容标识 | 均 200，当前列出 `gpt-6.1-sol` |
| `/v1/responses`，`User-Agent: Eta` | 403，`client_not_allowed` |
| `/v1/responses`，兼容模板标识 | 200，标准 SSE，完整返回 `OK`，约 3.5 秒完成 |
| 强制函数调用 | 200，模型调用 `eta_echo`，参数为 `text=ping` |
| 工具结果回传 | 200，同一无存储链协议完整回放后，最终返回 `DONE` |

这次实际验证了**推理接口的客户端标识限制**。目录能够同步并不代表同一标识的推理请求也被允许。函数测试仅在内存回显固定字符串，没有执行手机操作或外部副作用。请求使用 `store=false`，工具结果回放不依赖 `previous_response_id`。

现有 3.5.6.12 APK 的兼容模板与实测标识一致，无需重新构建应用。真实 HTTP 验证不替代 Android 真机/ROM 的界面和设备集成验证。

## 旧 Key 的受控实测（发布时记录）

2026-11-20，使用先前的旧 Key访问同一模型目录端点 `/v1/models`，只改变 `User-Agent`：

| 请求标识 | HTTP | 服务端拒绝原因 |
| --- | --- | --- |
| Eta 默认 `Eta` | 403 | `permission_error`，Key 没有分配任何分组 |
| 公开适配器使用的 `opencode/1.19.6` | 403 | 相同的 `permission_error` 与分组原因 |

服务端原文中的关键部分为：`API Key is not assigned to any group`。

这说明旧 Key 首先被分组权限拦截；改客户端标识没有改变该结果。它不能证明分组修复后就没有客户端识别限制。需要在星驰后台为 Key 设置可用分组，或由站方处理。没有继续轮换标识、重复发推理请求或进行路径/权限绕过。

真实测试凭据与源码和发布资产分开管理；提交的测试只使用 fixture。源码、文档、控制台结果和搜索查询均不包含真实密钥，报告只保留脱敏后的状态和错误类别。

## Eta 中的配置入口

1. 在 LLM 服务商页面编辑一个 OpenAI-compatible/API Key 连接。
2. Base URL 使用 `https://api.xingchiapi.com/v1`；原地址只填域名时，兼容按钮会补齐 `/v1`。
3. 点 **补齐星驰兼容配置**。该按钮仅在精确的星驰 HTTPS API 主机、默认端口、API Key 认证和兼容协议类型下出现。
4. 保存后同步模型目录，只选择该账户实时返回的模型。兼容模板没有写死可用模型、上下文上限或工具能力。

按钮设置 Responses 端点，并在缺少非空自定义标识时加入可编辑的 `User-Agent`：

```text
opencode/1.19.6 (EtaUltra compatibility)
```

已有非空自定义 `User-Agent` 保留；其他请求头、API Key、提示词、启用状态和模型配置不被清空。模板是用户显式点击后应用，既有其他连接默认仍使用 `Eta`。

该标识取自公开适配方案并保留 Eta 归属说明，不是访问令牌，也不会赋予分组、模型或额度权限。新 Key 已通过上述真实复测验证该标识可通行；旧 Key 的分组拒绝不由客户端配置改变。

## 请求链路与错误行为

- 模型目录请求和对话请求都使用统一的提供商请求头合并入口，且 API Key 仍由 Eta 的认证层管理。
- Responses 已使用 `instructions` 承载 system/developer，完整提交本轮上下文，`store=false`，不发送 `previous_response_id`。工具输出按 `call_id` 回放，不依赖服务端存储链。
- `client_not_allowed` 映射为 `PROVIDER_CLIENT_NOT_ALLOWED`，提示检查客户端兼容标识。
- 明确的未分组 `permission_error` 映射为 `PROVIDER_GROUP_UNASSIGNED`，提示服务商分组操作，不误报为 Key 无效。
- 两类拒绝均不可自动重试；不会通过自动轮换标识产生重复调用。普通 403、其他错误与现有重试策略保持原口径。
- 流内权限错误使用同样分类，但不会凭空声称该 SSE 响应的 HTTP 状态是 403。

## 验证范围

本地完整回归发现 2,423 项，2,353 项通过、0 失败、70 项因当前宿主能力明确跳过；Android Lint 通过，沿用未扩大的原基线。新增 11 项兼容与权限回归全部通过。备份流式测试改为自行初始化/关闭数据库的普通 Application，避免应用预热任务竞争。

本地协议回归使用隔离的 loopback HTTP 服务，覆盖：

- 模板只匹配精确 HTTPS 主机，拒绝相似域名、非默认端口、账号登录模式和其他协议；重复应用幂等。
- 保护原凭据与自定义字段，自定义标识可以覆盖默认值且只发送一次。
- 真实 `RemoteModelFetcher` 发出兼容头并采用远端模型 ID。
- 真实 Responses 客户端在服务端结束前交付首个文本增量，完整回放函数调用与工具结果。
- system/developer、用户问题与工具信息保持正确协议形态，禁用服务端存储链。
- 目录请求与对话请求的明确分组拒绝得到一致分类，而且不盲目重试。

上述为隔离的客户端请求和解析回归；新 Key 的真实推理、SSE 和工具调用结果回传也已另行验证通过，详见本页复测表。

## 调研来源

以下是开源作者的实现与测试记录，不是星驰站方的授权或当前账号的实测保证；读取源码而未安装、运行其安装脚本，也未使用其中的注册/兑换链接。

- [magpie-plugin-xingchi](https://github.com/postdare/magpie-plugin-xingchi)：按显式选项配置 UA、无链 Responses 处理、目录与模型能力区分。审阅 `index.mjs` 内容 Git blob 为 `02e065ef487fff00bce71f21e1a47cb980c896fb`。
- [xingchiapi-free-bridge](https://github.com/trojan0tik/xingchiapi-free-bridge)：小型本地头部适配转发器，区分 `client_not_allowed` 与其他失败。审阅 `proxy.js` 内容 Git blob 为 `0b85ade7f6fe80c1286b2a77ea2d927a305034fc`。

本轮没有把代理作为 Eta 的必需依赖；已有原生 HTTP 客户端能够按连接配置请求头。
