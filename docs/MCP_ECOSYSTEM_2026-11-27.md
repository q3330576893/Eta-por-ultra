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
- 未修改 `ops/research/magpie-autocompact` 桥接代码，未触碰其他项目交接文件。
