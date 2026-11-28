# MCP 生态、玻璃设置页与教程清理 · 本地验收记录（2026-11-27）

> 本文件记录**当前工作树**的实际执行结果。它不是公开发布证明，也不替代真机验收。
> 项目未提交、未推送、未安装到设备；公开下载区仍标识已发布版本。

## 一、本轮范围

| 领域 | 改动 |
| --- | --- |
| MCP 连接器 | 新增国内连接器发现页（高德、百度地图、博查、秘塔、智谱联网搜索与网页读取、百炼联网搜索、MCP 广场），默认国内常用，可搜索、可切换全部 |
| MCP 鉴权 | 高德 `key`、百度 `ak` 使用独立密钥栏与加密存储；URL 不落密钥；同源 307/308 保留密钥，跨源停止 |
| MCP 目录 | 完整校验后应用：分页游标循环、超 8 页或 128 工具、重复工具名、畸形条目与 schema 明确报错，保留原目录与勾选 |
| MCP 并发 | 刷新只提交目录元数据，原子合并期间发生的勾选、停用、改名与连接变更；身份已变的旧结果作废 |
| 设置界面 | 设置页与共用二级页骨架接入独立玻璃材质；模糊关闭或着色器不可用时高对比降级；保留 Monet 主色 |
| 控件可访问性 | 开关具备 Switch 角色与状态、单次回调、禁用态；320dp 窄屏与 200% 字号双向布局；RTL 滑块镜像 |
| 教程移除 | 删除首次启动教程、设置页开关与重播、手势教练、锚点、专用偏好字段；旧备份中的教程字段被忽略 |
| 本地化 | 本轮英文／繁体译文保留为待启用草稿，不注册为应用语言，生产资源继续完整简体中文 |

## 二、执行结果

### 2.1 单元与集成测试

命令：

```
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --console=plain
```

- 结果：`BUILD SUCCESSFUL in 11m 7s`，60 个任务，18 个实际执行。
- 报告汇总（`app/build/test-results/testDebugUnitTest`，412 个套件）：

| 指标 | 数值 |
| --- | --- |
| 用例总数 | 2,658 |
| 失败 | 0 |
| 错误 | 0 |
| 跳过 | 69 |

- 跳过项来自 Robolectric 原生运行时平台限制（`EtaRobolectricTestRunner` 主动 assume），
  按项目既有约定视为「本机未执行」，不计入通过。

### 2.2 严格报告门禁

```
python scripts/verify_feature_test_reports.py
```

输出：`Verified 53 required suites and 80 mandatory integration cases without skips.`

本轮新增并纳入门禁的套件与用例：

- `McpRefreshConcurrencyTest`、`McpQueryAuthorizationTest`、`McpConnectorDraftTest`、
  `McpCredentialUpdateTest`、`McpConnectorDiscoveryTest`
- `EtaSettingsGlassTest`、`EtaSettingsControlsTest`、`OnboardingRetirementTest`
- 关键用例：查询模板空密钥不降级、真实 HTTP + 加密存储 + 详情换钥全链路、
  RTL 滑块像素位置、200% 字号双向布局、旧设置忽略教程字段、教程 JVM 类缺席

门禁脚本只承认「真实执行且通过」的套件；未匹配到的 `--tests` 过滤不算证据。

### 2.3 Android Lint

- 结果：**0 error**；229 warning、3 hint（基线已过滤既有项）。
- 本轮曾出现 2,667 条 `MissingTranslation`：根因是新增的 `values-en` / `values-zh-rTW`
  只包含 48 个本轮字符串，Lint 因此判定整个应用支持这两种语言。
- 处理方式：译文移入 `docs/localization-drafts/2026-11-27/` 保留，键集合与简体中文一致；
  未关闭缺译检查、未降低 Lint 级别、未扩大基线。启用语言时需先补齐完整目标语言资源。

### 2.4 构建产物

| 项 | 值 |
| --- | --- |
| 路径 | `app/build/outputs/apk/debug/Eta-v3.5.6-debug.apk` |
| 大小 | 40,201,011 字节 |
| SHA-256 | `a2750c234d3b85f10fff31cd35c9036fed26352ba4cae7481831c9d5fdbabfc0` |
| 包名 | `io.github.mangi.eta` |
| versionCode / Name | `2026112100` / `3.5.6-debug` |
| SDK | minSdk 33 / targetSdk 37 / compileSdk 37 |
| ABI | 仅 `arm64-v8a` |
| 签名 | v2 通过；证书 `CN=Android Debug`（Debug 包，非发布签名） |

`apksigner verify` 结论：`Verifies`，v2 = true，v1/v3/v4 = false（Debug 变体默认行为）。

### 2.5 渲染证据

目录：`.eta-tools/settings-glass-20261127-final-images/`，共 10 张 PNG：

`settings_switch_ltr` / `settings_switch_rtl` / `settings_switch_disabled_dark` /
`settings_blur_on_light` / `settings_blur_off_light` / `settings_fallback_light` /
`settings_fallback_dark` / `settings_shader_unavailable` / `settings_font_200_ltr` /
`settings_font_200_rtl`。

- 来源：Robolectric + `SettingsComposeTestHost` 的组件级 Canvas 截图，**不是实机截图**。
- 已人工查看：`settings_fallback_dark`（深色底白字可读）、`settings_font_200_rtl`
  （开关在左、滑块镜像正确）。
- 断言层面：暗色回退同时校验实际字色等于调色板 `onSurface`，并对渲染像素计算对比度 ≥ 4.5:1；
  RTL 用例逐状态比对左右滑块像素位置。

## 三、本轮修复的真实缺陷

1. **查询鉴权空值降级（P2）**：新增高德／百度连接时，密钥框留空会把鉴权类型置为 `NONE`，
   绕过「缺少查询密钥」检查与 URL 去密钥逻辑；若用户把旧 key 写在 URL 里，会被原样保存，
   之后在详情页换钥会变成 Bearer，查询服务继续用旧密钥。
   - 修复：新增与详情共用 `McpCredentialUpdate.from(...)`；查询类型在空值、清空、空白时保留；
     新增 `toServerSetting()` 作为唯一保存路径，缺值由客户端在联网前报 `AUTHENTICATION`。
   - 回归：两模板「空钥不产生 HTTP 请求」+「真实发现→加密保存→换钥→刷新使用新 query」全链路。

2. **玻璃开关 RTL 未镜像（P3）**：滑块始终按 LTR 绘制，RTL 下开启态停在右端。
   - 修复：按 `LayoutDirection` 反转绘制进度；交互语义不变。
   - 回归：LTR/RTL × 开/关 四组像素级断言。

3. **暗色回退截图误导（P3，测试夹具）**：夹具 `Text` 未指定颜色，深色底渲染黑字，
   不能作为可读性证据。
   - 修复：显式使用调色板前景色，并新增渲染像素对比度断言。

## 四、验收边界（未完成 / 需真机）

- 当前 `adb devices` 无设备：安装、启动、真实 GPU 模糊、滚动帧率、主题切换闪白均未实测。
- 屏幕级未覆盖：设置首页导航与搜索清除、两种 Scaffold 顶栏折叠与返回、弹窗、二级页跳转。
- 真实第三方服务：高德／百度／博查／秘塔／智谱／百炼需要用户自己的 Key、权限与配额，
  本轮只用本地 HTTP 夹具验证协议与鉴权，未连接真实服务。
- 酷安与公开分支的对照结论见 `docs/COOLAPK_ETA_COMPARISON_2026-11-27.md`；
  其中「未来候选」不是本轮待办，也不是已实现功能。

## 五、约束遵守

- 未 `git commit`、未 `git push`、未安装或卸载应用。
- 未读取任何真实凭据；测试密钥均为合成值。
- 未删除用户数据，未修改 DSH 打包核心或 `ops/research/magpie-autocompact` 桥接代码。

## 六、2026-11-28 接续复核

### 6.1 地区二级页标题

- 已确认 [`McpServersScreen.kt`](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/mcp/McpServersScreen.kt) 的地区二级页使用 `mcp_discovery_domestic_short` / `mcp_discovery_overseas_short`，对应文案为「国内」/「国外」，并保留 `compactTitle = true`。
- 这两个短标签替代长标题「国内连接器」/「国外连接器」，用于 200% 字号下的窄屏顶栏，避免标题被省略号截断。

### 6.2 编译提示与回归断言

- 已核对 [`McpServersScreen.kt`](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/mcp/McpServersScreen.kt) 的自定义配置调用为 `configure(customTemplate)`，清除了旧日志中的 `Unnecessary safe call on a non-null receiver of type 'ConnectorCatalog.Template'.`；首轮本地 `compileDebugKotlin` 实际执行，日志未再出现该提示。
- 组件回归覆盖：首层地区入口回调、国内／国外独立筛选、320×568 小屏下 100%／200% 字号最后一个连接器配置按钮的可见性与 MotionEvent 点击、无匹配结果时自定义入口可达，以及国内「办公文档」场景筛选。首层测试不承担当页路由切换验收。
- 首轮专项测试发现夹具断言错误：`firstLevelHasTwoRegionEntriesAndCustomWithoutExpandingServiceCards` 在仅渲染菜单、仅记录地区回调的夹具中，错误要求点击后「国内连接器」消失；该夹具没有切换页面。已修正为验证国内/国外回调顺序、菜单不展开服务卡片、自定义回调恰一次，保持组件测试与整页导航验收的区别。
- 修正后的最终专项执行：15 个连接器套件、168 tests、0 failures、0 errors、0 skipped（筛选 `*Mcp*Test` 与 `*Connector*Test`）；`McpConnectorNavigationTest` 的 5 个用例均实际执行，包含末项配置按钮的 MotionEvent 点击和新增标题回归。最终汇总保存为 `.eta-tools/connector-test-summary-final-20261128.json`。
- 随后补充 `regionTitlesAreNotEllipsizedAtTwoHundredPercentBeforeAndAfterScrolling`：在两个地区页 200% 字号、列表顶部和底部读取 `TextLayoutResult`；末项按钮测试同时精确断言 320×568 位图尺寸。新用例已加入既有报告门禁。
- 新断言首次执行报告 168 tests / 1 failure，根因是错误使用 `hasVisualOverflow` 判断标题字形：实际标题节点为 68×50 px，但内部段落仍保留 121×50 px 的约束宽度，产生 `widthOverflow=true`，原生截图中的「国内」完整显示。失败 XML 已独立保存在 `.eta-tools/title-layout-first-failure-20261128.xml` 与 `.eta-tools/title-layout-diagnostic-failure-20261128.xml`。
- 已改为直接断言单行、`isLineEllipsized(0) == false`、可见末尾等于完整标题长度、实际行宽和逐字边界在节点内、节点在视口内；最终 XML 输出 `line=0..68`、`visibleEnd=2`、`ellipsized=false`，两个字符边界各为 34×50 px。

### 6.3 原生渲染与触摸复验（非真机）

- `SettingsComposeTestHost` 使用 Robolectric 原生图形模式、320×568 px 视口；地区页分别在 100% 与 200% 字号下滚动至列表最后一项，执行 `ACTION_DOWN` / `ACTION_UP` 触摸事件并断言配置回调目标 ID。
- 国内与国外 200% 字号截图均为 320×568 px；本次人工查看确认顶栏短标题「国内」「国外」均完整显示、没有省略号，末项配置按钮在可视范围内。
- 最终截图目录：`.eta-tools/mcp-connector-final-20261128-images/`，共 10 张 PNG：菜单、办公分类、国内/国外标题顶部与底部 200%、以及国内/国外 100%／200% 底部按钮。短标题和末项按钮均在 320×568 原生位图中可见。这里属于 **Robolectric 原生渲染 + 合成触摸事件验收**，不是 Android 真机验收；最终 `adb devices` 仍只有 `List of devices attached` 标题行。
- 真机系统字体缩放、导航栏 Insets、GPU/ROM 差异及实物点击仍待连接 Android 设备后验收；二者结论分开记录。

### 6.4 Lint 与 APK 构建

- 最终脚本 `.eta-tools/run-connector-acceptance-20261128.ps1` 返回 `ACCEPTANCE_EXIT=0`；日志 `.eta-tools/mcp-connector-final-20261128.log` 中 `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 全部完成，Gradle `BUILD SUCCESSFUL in 2m 39s`。
- Lint 报告为 `0 errors, 230 warnings, 1 hint`；这些 warning/hint 是现有工程统计，标题修复没有新增对应的编译器提示。旧的 `Unnecessary safe call on a non-null receiver of type 'ConnectorCatalog.Template'.` 未出现在本轮编译日志。
- APK：`app/build/outputs/apk/debug/Eta-v3.5.6-debug.apk`，40,201,011 字节，SHA-256 `F46B1EAA85CE1DAC5BD64B2228707F0255A02422E8A6C889D8BECC2ACC76893C`；包名 `io.github.mangi.eta`，versionCode/versionName `2026112100` / `3.5.6-debug`，min/target/compile SDK `33/37/37`，ABI `arm64-v8a`。`apksigner` 验证 v2 通过，签名为 `CN=Android Debug`。
- 交付边界：以上是本地 Debug 构建和 Robolectric 原生验收；尚未执行安装或真机验收。

### 6.5 定时任务收尾

- 本次接续由一次性定时任务 `schedule-b21f154d-9f8b-4cf2-abbf-fb3c0b0a6234` 触发。全部待办完成后已调用删除接口，`deleted=true`，原 RRULE `DTSTART;TZID=Asia/Shanghai:20261128T083200 / RRULE:FREQ=DAILY;COUNT=1` 不再触发。
- 全程未 `git commit`、未 `git push`、未修改 DSH 打包核心、未读取任何真实凭据、未删除用户数据、未安装或卸载应用。



- 未修改 `ops/research/magpie-autocompact` 桥接代码，未触碰其他项目交接文件。
