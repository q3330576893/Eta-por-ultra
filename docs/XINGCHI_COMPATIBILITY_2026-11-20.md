# 星驰 API 接入与 403 诊断

## 已完成与实际限制

第一阶段的功能版已先公开发布为 **3.5.6.11**，源码提交 `37c73ec54c690280d98378f0360e8b3042b702fa`；之后才开展本项适配。

Eta 复用既有 OpenAI-compatible 连接、自定义请求头、模型目录同步与 Responses 客户端，增加显式兼容配置入口和准确的权限错误分类。没有改其他服务商的默认客户端标识，也没有引入额外本地代理。

**当前提供的密钥仍被服务端“未分配分组”拒绝。兼容代码与本地协议回归不等于该账户已经取得推理权限。**

## 受控实测

2026-11-20，以同一用户提供的密钥访问同一模型目录端点 `/v1/models`，只改变 `User-Agent`：

| 请求标识 | HTTP | 服务端拒绝原因 |
| --- | --- | --- |
| Eta 默认 `Eta` | 403 | `permission_error`，Key 没有分配任何分组 |
| 公开适配器使用的 `opencode/1.19.6` | 403 | 相同的 `permission_error` 与分组原因 |

服务端原文中的关键部分为：`API Key is not assigned to any group`。

这说明当前密钥首先被分组权限拦截；改客户端标识没有改变该结果。它不能证明分组修复后就没有客户端识别限制。需要在星驰后台为 Key 设置可用分组，或由站方处理。没有继续轮换标识、重复发推理请求或进行路径/权限绕过。

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

该标识取自公开适配方案并保留 Eta 归属说明，不是访问令牌，也不会赋予分组、模型或额度权限。当前受限账户尚未实测验证此模板已获网关放行。

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

上述是客户端请求和解析测试。当前密钥的真实普通回答、真实 SSE 和真实工具调用仍待服务端完成分组授权后验证。

## 调研来源

以下是开源作者的实现与测试记录，不是星驰站方的授权或当前账号的实测保证；读取源码而未安装、运行其安装脚本，也未使用其中的注册/兑换链接。

- [magpie-plugin-xingchi](https://github.com/postdare/magpie-plugin-xingchi)：按显式选项配置 UA、无链 Responses 处理、目录与模型能力区分。审阅 `index.mjs` 内容 Git blob 为 `02e065ef487fff00bce71f21e1a47cb980c896fb`。
- [xingchiapi-free-bridge](https://github.com/trojan0tik/xingchiapi-free-bridge)：小型本地头部适配转发器，区分 `client_not_allowed` 与其他失败。审阅 `proxy.js` 内容 Git blob 为 `0b85ade7f6fe80c1286b2a77ea2d927a305034fc`。

本轮没有把代理作为 Eta 的必需依赖；已有原生 HTTP 客户端能够按连接配置请求头。
