# 模型请求 Hook 请求链审计

审计日期：2026-11-21（Asia/Shanghai）

## 结论

当前工作树中的请求 Hook 已接入 Eta 的实际模型请求链，而不是只在设置页保存一段文本。配置以模型条目为边界持久化，运行配置通过 `modelEntryId` 和 `requestHook` 传过跨进程边界，Provider 在发送前从未修改的会话消息生成一次请求副本。Hook 默认关闭，只有模型条目明确启用且正文有效时才会参与请求。

本审计确认的是请求结构、配置隔离、持久化和本地回归结果，不把 HTTP 200、界面状态或关键词判定当成模型任务成功，也没有把第三方项目的提示词正文复制进应用。

## 请求链

1. `Model.requestHook` 保存每个模型的 `enabled`、`channel` 和正文；数据库 `provider_models.request_hook_json` 在 34→35 迁移中以 `{}` 作为旧模型默认值。
2. `ModelRepository.saveModel` 在模型保存时裁剪首尾空白、限制 16,000 字符，并拒绝“启用但空白”的配置。远端模型目录刷新保留已有本地模型 ID、Hook、请求头和请求体覆盖。
3. 子模型授权由 `SubagentModelSelection` 统一投影给工具清单和执行器；同名 API 模型按服务商与本地模型选择键区分，不移除前缀后猜测目标。唯一旧 API 名与本地 ID 继续兼容，过期/歧义授权不会退回主模型。`RuntimeConfigRepository.buildRuntimeConfig` 将当前模型的 Hook 与 `modelEntryId` 放入运行配置。指定子模型时读取子模型自己的 Hook；未指定子模型时保留父请求快照。OAuth 运行时重新解析同时匹配 provider、API 模型名和本地模型条目 ID。
4. `AgentRuntimeWire` 使用已有文本描述符机制传输长 Hook JSON。非聊天操作（压缩、回复改写）在发送端清除 Hook；接收端对缺失、畸形、未知枚举或超长数据回退到关闭状态。
5. `ProviderRequest.effectiveMessages` 只在 `CHAT` 和 `COMPARISON` 用途启用 Hook，并在第一次访问时完成校验、上下文窗口检查和借用小布 User 通道限制。结果通过 `ModelRequestHookProjection.project` 生成请求副本，原始会话数组保持不变。
6. System 通道在原有 system/developer 前缀之后插入独立 system 消息；User 通道在最后一条真实用户消息前插入文本块；Both 同时执行两处投影。观察消息、上下文摘要和角色卡 depth user 不会被当成插入目标。
7. Chat Completions、Responses、Anthropic Messages、Gemini Interactions 分别把同一份投影转换到各自协议的 system/instructions/input 字段。图片、工具调用 ID、工具结果和 Provider 原生思考/签名块由各协议转换器继续保留。
8. 技术错误重试复用同一个 `ProviderRequest` 和延迟投影；后续工具轮从未注入的 canonical messages 重新投影，因此不会累计重复 Hook，也不会依赖正文子串哨兵。
9. 历史裁剪在请求侧预留 Hook 的估计 token；预留耗尽历史份额时仍按完整用户轮次裁剪到最小保留量。注入正文不写回聊天数据库、持久 transcript 或上下文压缩摘要。
10. 请求快照只记录启用状态、通道、短哈希和估计 token。Hook system 段只保留长度与 revision；用户消息和图片只记录角色/长度；复制与导出会排除 Hook 正文。

## 界面恢复

`ModelEditorDraft` 只保存来源条目 ID 与可编辑字段，`ModelEditorState` 随 Compose saveable registry 恢复。关闭弹窗使用明确的非空状态标记；恢复内容校验服务商归属，保存时重新从当前仓库条目复制，避免把完整目录、请求体和请求头重复写进 Bundle。当前标签、草稿及取消/保存后重开已由真实 Compose registry 生命周期测试覆盖，Android Activity 旋转仍需设备验证。

## 证据

- 独立存储/运行审查：` .eta-tools/model-prompt-research-20261121/independent-storage-review.md`。
- 独立核心审查：` .eta-tools/model-prompt-research-20261121/independent-core-review.md`。
- 本地完整回归：`.eta-tools/model-prompt-research-20261121/full-20261121-202053/summary.json`。
- 修复前完整回归：2,498 项测试，0 失败，0 错误，71 项 Windows 平台跳过；源指纹为 `abe260d60edf7d2828cda2f988a69eab0d8242b3eb31f1618e07d514325f3534`，构建期间源文件未变化。
- 修复前门禁：42 个必过套件、61 个关键场景均实际通过且无跳过。新加入的子模型授权、真实派发和编辑恢复用例也纳入强制门禁，最终计数随本轮完整验收更新。
- 四协议 loopback HTTP 测试核对了 System/User/Both、图片、工具历史、专用用途隔离、自定义请求体保护和成功/错误响应边界。
- Windows 可执行的跨进程描述符读取测试通过；POSIX unlink 后继续读取用例在 Windows 跳过，Linux 发布门禁仍要求实际执行。

## 明确限制

- 这些用例验证请求构造和本地协议闭环，不代表某个远端模型会接受 Hook，也不提供模型效果提升百分比。
- 本机没有连接 Android 设备，未完成真机设置页交互、OEM 生命周期和 Android ARM64 描述符写入验证。
- 当前公开 Release 仍是 v3.5.6.14；3.5.6.15+model-hook 仅为本地签名预览，必须经过提交、CI、签名核验和公开资产比对后才能称为正式发布。
- 该功能是可配置的请求扩展，不宣称能改变模型权重、训练参数或服务端审查策略。
