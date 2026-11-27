# 酷安 Eta 改版与公开分支对照（2026-11-27）

## 结论与证据口径

本轮取得 **1 条酷安作者正文及 3 个公开 GitHub 分支**的证据。三位 GitHub 作者与该酷安发帖者的身份对应关系尚未建立，不能把这些仓库写成该帖的源码。也没有证据证明其他分支在所有方面都优于 Eta Ultra。

- **作者宣称**：帖子、README 或提交说明中的介绍，未由本轮运行测试验证。
- **源码可见**：已读取具体实现或测试源码；证明代码存在，不等于功能已在设备上通过或已发布。
- **当前 Eta**：指本次阅读的本地工作树，包含开发中的改动；不等于公开 APK 或 Git HEAD。
- **截图未验**：本轮未取得并查看可读截图，不根据图片名称、链接或文字描述猜测布局、动效、流畅度和视觉优劣。

研究仅访问公开页面、公开 GitHub API 和项目源码；未安装他人 APK、未运行 Gradle、未读取账号凭据。唯一文档写入为本文。

## 一、来源与覆盖范围

| 编号 | 来源 | 本轮取得的证据 | 边界 |
| --- | --- | --- | --- |
| C1 | [酷安：向阳865828 的 Eta Fork 动态](https://www.coolapk.com/feed/75252458?s=MzVjMTIxYTQxYzg4MzVkZzZiMDg0YzRkega1670) | HTTP 200 正文、作者名、9 个截图 URL | 没有可核对的 GitHub 链接；截图未验；没有装机结果 |
| G1 | [Sam-Fic/EtaExpressive](https://github.com/Sam-Fic/EtaExpressive) | README、默认分支与提交 SHA、主题及 MCP/Skill 相关源码 | 与 C1 作者的关联未核实；没有运行或视觉评分 |
| G2 | [donglihu/Eta-por](https://github.com/donglihu/Eta-por) | README、提交 SHA、虚拟屏浮窗和消息队列源码 | README 以旧上游版本为比较基线，不能直接套为相对当前 Ultra 的增量 |
| G3 | [Bxdj99/Eta-por](https://github.com/Bxdj99/Eta-por) | 默认快照分支、提交说明、消息队列 reducer 和测试源码 | README 包含大量上游介绍，不能把其中功能全部认作该分支新增 |

公开搜索已执行：宿主网页搜索查询“site:coolapk.com/feed Eta 改版”“site:coolapk.com Eta GitHub”“Eta Android 助手 改版 酷安”，搜索工具返回 `no web_search_tool_result blocks`；另查询公开 Bing/Google 与酷安网页搜索入口，未取得可用于新增帖结论的相关正文。GitHub REST 仓库搜索 `Eta-por fork:true` 返回了上述实际项目。未修改搜索服务配置，也不据此断言酷安只有一条相关帖子。

C1 不带查询参数的地址在先前读取中仅显示扫码入口；使用上表完整分享 URL 后已复现正文。页面显示相对时间“2天前”，截图路径含 `2026/1124`；这些信息不足以确定精确发帖时间，本文不补写发布日期。

## 二、酷安正文：需求线索，不当作功能验收

C1 作者介绍的方向可归纳为：更多场景的 MCP 目录、浏览器调试与多标签、ADB 执行能力、运行中指令处理、称呼及字号等自定义、操作审批、记忆改进，以及 OriginOS 相关适配。作者还称其 Root 问题已修复。**这些均为作者正文宣称，本轮没有该构建的源码、APK 或运行轨迹。**

| 帖子方向 | 当前 Eta 工作树对照 | 可作出的结论 |
| --- | --- | --- |
| 邮箱、云盘、搜索、开发、生图等连接器 | [连接器目录](../app/src/main/kotlin/io/github/mangi/eta/data/model/ConnectorCatalog.kt)已有国内服务模板和网关条目；高德、百度、博查、秘塔、智谱两项、百炼搜索及百炼提供商入口可见 | 目录数量不等于已连接数量；邮箱、云盘、生图仍需逐项端点、认证与实际工具发现证据 |
| Agent 浏览器多标签和调试控制台 | [浏览器会话](../app/src/main/kotlin/io/github/mangi/eta/agent/browser/AgentBrowserSession.kt)以单个共享 WebView 与单份快照保存状态；[工具目录](../app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentBrowserToolCatalog.kt)有导航、正文、DOM 操作和截图等 action | 基础 Agent 浏览器已存在；本轮所读目录未见标签管理或控制台 action，是值得单独设计的候选增量 |
| 排队、打断、引导 | [聊天状态入口](../app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppState.kt)已有发送、仅排队、打断发送和队列撤回；[运行控制器](../app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRunController.kt)有下一回合注入的 steering | 当前并非缺少整套能力；更适合统一语义、入口和取消后的队列行为 |
| 访问与命令审批 | [确认守卫](../app/src/main/kotlin/io/github/mangi/eta/agent/display/dashboard/AgentConfirmGuard.kt)、[审批续跑](../app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentApprovalFollowUpRuntime.kt)和[审批卡片](../app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentApprovalCard.kt)已有对应实现 | 尚未核实与帖子所述三档模式完全同义，不写成“已经相同”或“完全缺失” |
| 记忆改进 | [记忆仓库](../app/src/main/kotlin/io/github/mangi/eta/data/repository/AgentMemoryRepository.kt)存在热区上限、归档和压缩逻辑 | “更深”缺少量化定义，不能据帖评定记忆效果高低 |
| ADB 虚拟屏、ADB Frida | 本轮定向查找未定位到明确的同名后端；现有 Root、虚拟屏与 Linux 能力见[分叉状态说明](FORK_STATE.md)及实际源码 | 记录为待核实后端，而不是现有 Root 能力的别名；也不据检索未命中断言仓库绝对不存在相关路径 |
| OriginOS 适配 | [Vivo Hook](../app/src/main/kotlin/io/github/mangi/eta/hook/vivo/VivoHooks.kt)、[上岛通知](../app/src/main/kotlin/io/github/mangi/eta/hook/vivo/VivoIslandNotifications.kt)、[桥接](../app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeIslandBridge.kt)及个人上下文源码已可见 | 不能说 Ultra 完全没有适配；代码存在也不证明 OriginOS 7 或原子笔记 CLI 令牌已验证 |
| 自定义称呼 | [角色上下文设置](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/characters/CharacterContextScreens.kt)已有角色范围的称呼设置 | 角色人设不自动等于全局助手称呼功能；范围需分别核对 |

**图片记录：**C1 页面列出 9 张 CDN 图片，例如[第 1 张原页面图片链接](https://image.coolapk.com/feed/2026/1124/18/38176687_916579c2_6203_6503_943@1080x2400.jpg.m.jpg)。协作取证中主会话报告图片请求为 HTTP 567；本轮未取得可读图像。因此不评价该改版的卡片形状、字体、玻璃效果或过渡动画。

## 三、三个公开分支的交叉核实

### G1：EtaExpressive——设计体系与扩展配置交互

核查快照：`main`，提交 [`fa9940bac73bf632fe4ae9402c0ec646251bc9ab`](https://github.com/Sam-Fic/EtaExpressive/commit/fa9940bac73bf632fe4ae9402c0ec646251bc9ab)。[README](https://github.com/Sam-Fic/EtaExpressive/blob/fa9940bac73bf632fe4ae9402c0ec646251bc9ab/README.md)介绍 M3 Expressive、对话配置 MCP 和本地 Skill 安装等方向。

源码交叉证据：

- [AgentAppTheme](https://github.com/Sam-Fic/EtaExpressive/blob/fa9940bac73bf632fe4ae9402c0ec646251bc9ab/app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppTheme.kt)实际调用 `MaterialExpressiveTheme` 和 `MotionScheme.expressive()`，不只是 README 中出现设计名词。
- [AgentSelfMcpTools](https://github.com/Sam-Fic/EtaExpressive/blob/fa9940bac73bf632fe4ae9402c0ec646251bc9ab/app/src/main/kotlin/io/github/mangi/eta/agent/tool/AgentSelfMcpTools.kt)有 list/add/update/delete/refresh/set_tool 分派、发现结果校验及下一次回复生效的返回信息。
- [AgentSkillToolCatalog](https://github.com/Sam-Fic/EtaExpressive/blob/fa9940bac73bf632fe4ae9402c0ec646251bc9ab/app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentSkillToolCatalog.kt)声明 `skills_install_local`，包含目录参数和冲突覆盖参数。这证明工具声明存在，不替代安装全链路运行验证。

当前 Ultra 的国内连接器目录已形成基础，可借鉴“从用户目标引导到配置”的交互，不必照搬让密钥进入模型工具参数的路径。本轮未在当前工具目录定位到同名自配置工具，后续应按实际注册与分派链验证。

**UI 判断边界：**源码足以确认主题体系差异；README 有界面预览链接，但本轮未查看像素，不能据此声称 M3E 比当前玻璃设置页更美观或更流畅。Ultra 的[共用 Scaffold](../app/src/main/kotlin/io/github/mangi/eta/ui/components/MiuixScaffoldPage.kt)与[玻璃材质](../app/src/main/kotlin/io/github/mangi/eta/ui/components/EtaSettingsGlass.kt)已有实现，可继续统一当前体系，无需仅因分支展示另一套主题就更换组件库。

### G2：donglihu/Eta-por——日常交互、浮窗与任务控制

核查快照：`main`，提交 [`2934102d0e0b2818b54fa70aac361d9b44ed1438`](https://github.com/donglihu/Eta-por/commit/2934102d0e0b2818b54fa70aac361d9b44ed1438)。[README](https://github.com/donglihu/Eta-por/blob/2934102d0e0b2818b54fa70aac361d9b44ed1438/README.md)将会话、Markdown、虚拟屏浮窗、子代理等列为个人定制方向；其比较基线是上游 3.3.3，不能当作对当前 Ultra 的缺失清单。

本轮确认了两条具体代码路径：

- [VirtualScreenFloatWindow](https://github.com/donglihu/Eta-por/blob/2934102d0e0b2818b54fa70aac361d9b44ed1438/app/src/main/kotlin/io/github/mangi/eta/agent/display/VirtualScreenFloatWindow.kt)含边缘折叠开关、`collapseIfAtEdge`、收起/展开和接管相关处理，支持其浮窗方向的源码存在性。
- [AgentMessageQueueReducer](https://github.com/donglihu/Eta-por/blob/2934102d0e0b2818b54fa70aac361d9b44ed1438/app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentMessageQueueReducer.kt)明确区分排队、立即插话、停止后保留队列，并另有将消息移至队首而不打断运行的转换。

当前 Ultra 已有同类会话队列、steering、虚拟屏及会话分类等基础，不能把相近名称重复列为待新增。可借鉴的是边界状态、操作反馈和验证方法。README 对并发会话标为实验性质，本轮没有并发性能或稳定性数据。未取得并查看该分支当前界面截图。

### G3：Bxdj99/Eta-por por66——聚焦插队链路的快照

核查快照：默认分支 `por66-snapshot`，提交 [`3dc1b2beb59cec65b846caa1bfdcdfb6c5f3400e`](https://github.com/Bxdj99/Eta-por/commit/3dc1b2beb59cec65b846caa1bfdcdfb6c5f3400e)。仓库描述称关注插队链路；提交说明将其标为源码快照，并说明不含截图。不能由此推断所有插队问题已修复。

[队列 reducer](https://github.com/Bxdj99/Eta-por/blob/3dc1b2beb59cec65b846caa1bfdcdfb6c5f3400e/app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentMessageQueueReducer.kt)和[对应测试](https://github.com/Bxdj99/Eta-por/blob/3dc1b2beb59cec65b846caa1bfdcdfb6c5f3400e/app/src/test/kotlin/io/github/mangi/eta/ui/app/AgentMessageQueueReducerTest.kt)可见队首插话、撤回合并草稿、主动停止后保留队列等分支与断言。本轮只读取测试，没有执行，因此不写“测试通过”。

其 [README](https://github.com/Bxdj99/Eta-por/blob/3dc1b2beb59cec65b846caa1bfdcdfb6c5f3400e/README.md)仍包含上游项目的大量通用介绍及链接；这些不作为 por66 原创或新增功能的证据。当前 Ultra 的同名 reducer 已有相近状态转换，适合对照回归用例，而不是整段替换实现。未进行该快照 APK 的构建、安装或截图比较。

## 四、当前 Eta 的 UI 与优化判断

此前按实际 AppRoute 和设置入口核查的 22 个直接设置导航入口，以及所查提供商、外观、语音、Linux、Prompt 等子页，均经过两套共用 Scaffold；当前骨架已经接入独立玻璃背景。该结论来自源码覆盖审查，不是设备截图验收。后续应检查局部吸顶栏、弹层、输入法与横屏表现，而不是继续给已覆盖页面叠加新骨架。

优化也应区分层次：主题调用证明材质体系，reducer 证明状态规则，快照与热区代码证明结构设计；它们都不直接证明帧率、内存、召回率或任务成功率更高。本文没有这些可比实测数据。

## 五、五项优先建议

以下为评估建议，尚未实施。

| 顺序 | 建议 | 基于什么 | 最小验收口径 |
| --- | --- | --- | --- |
| 1 · P0 | 让国内连接器从“目录”走到“可理解的连接流程” | C1 的生态需求、G1 的配置交互、当前真实服务目录 | 区分未配置/待验证/已发现工具/失败；指南展示套餐与网关前提；用户在独立凭据栏填 Key；连接后以实际发现的工具为准，暂不承诺 OAuth 或泛化邮箱/云盘/生图能力 |
| 2 · P1 | 为现有浏览器规划多标签及有界诊断信息 | C1 的作者宣称与当前单 WebView 架构 | 若实现，先定义 tabId、当前标签和人工接管范围；console/导航错误限定条数与单条长度；切标签不把旧页元素标识误用于新页；用两个真实页面完成回归 |
| 3 · P1 | 统一“仅排队、立即打断、下一回合引导”和审批续跑反馈 | C1、G2/G3 队列源码及当前已有链路 | 用户能看清何时生效；主动停止后普通队列不自动继续；撤回不覆盖草稿；审批只送达原任务一次；保留已有组件体系并核查窄屏、字号、横屏和弹层 |
| 4 · P2 | 把 ADB、Root、LSPosed 与 ROM 能力分开检测和展示 | C1 的 ADB/OriginOS 方向与当前已有 Vivo/虚拟屏实现 | 先列支持矩阵及探测结果；ADB 后端作为独立路径核实后再宣传；OriginOS 版本和应用版本有对应设备证据；不把预览文案或源文件存在视为已适配 |
| 5 · P2 | 把“记忆加深”改成可观察的记忆质量改进 | C1 的模糊表述与当前热区/冷区实现 | 展示本轮使用的记忆范围及来源；给用户更正、删除和归档入口；以固定多轮任务比较关键事实保留、错误记忆清除、输入预算与耗时，再决定是否调整检索/压缩 |

## 六、后续证据缺口

1. C1 的源码、发布包与酷安作者身份尚未对应；取得作者明确链接后再验证具体实现，不自行配对 GitHub 账号。
2. 尚无第二、第三条可用正文的酷安帖子。本文使用真实公开分支补充研究，未把 GitHub 项目伪装为酷安帖子。
3. 所有案例均缺少本轮亲自查看的可读截图和可比设备运行结果；不作 UI 排名、速度排名或综合优劣排名。
4. 各分支的功能介绍与当前 Ultra 部分重叠，后续按具体用户收益逐项比较，避免整段替换已存在的能力。
