# Eta Ultra 正式发布流程

本仓库为 [q3330576893/Eta-por-ultra](https://github.com/q3330576893/Eta-por-ultra)。`main` 是唯一开发和正式发布主线；每次向 `main` 推送或通过 `workflow_dispatch` 手动运行发布工作流，都应产出经过验证的签名正式 Release APK，并发布公开 GitHub Release。

面向用户的入口：

- [最新版发布页](https://github.com/q3330576893/Eta-por-ultra/releases/latest)
- [固定最新版 APK 下载](https://github.com/q3330576893/Eta-por-ultra/releases/latest/download/Eta-Ultra.apk)
- [全部历史版本](https://github.com/q3330576893/Eta-por-ultra/releases)

## 1. 签名配置

在仓库 `Settings > Secrets and variables > Actions` 配置以下 Secrets：

| Secret | 内容 |
| --- | --- |
| `ETA_RELEASE_KEYSTORE_BASE64` | 发布 KeyStore 文件的 Base64 文本 |
| `ETA_RELEASE_STORE_PASSWORD` | KeyStore 密码 |
| `ETA_RELEASE_KEY_ALIAS` | 发布密钥别名 |
| `ETA_RELEASE_KEY_PASSWORD` | 发布密钥密码 |
| `ETA_REPO_ADMIN_TOKEN` | 每次发布后同步 GitHub 仓库简介、主页和 topics 的仓库管理凭据 |

CI 将 KeyStore 解码到临时路径，以 `ETA_RELEASE_STORE_FILE` 环境变量将路径交给 Gradle，其余三个值通过同名环境变量传递。私钥、Base64、密码和本地 SDK 路径均保持在 Git 之外；Base64 只是编码，仍是私密签名材料。

当前维护环境的本地签名材料位于用户目录下的 `AppData/Local/EtaBuildTools/signing`。备份这套密钥，并在后续所有正式版本中保持同一证书。应用 ID 沿用 `io.github.mangi.eta`，签名不同的包即使应用 ID 相同也不能直接覆盖升级。

Secrets 可以通过 GitHub 网页设置，GitHub CLI 仅是可选工具，无需假定本机已安装。禁止把密码写进命令参数、源码、发布说明或日志。

## 2. 版本与构建身份

| 项目 | 正式版规则 |
| --- | --- |
| 应用名 | `Eta Ultra` |
| 应用 ID | `io.github.mangi.eta` |
| 最低系统／ABI | Android 13（API 33）／ARM64 |
| 本地基础版本 | `3.5.1` |
| 基础 `versionCode` | `2026111706` |
| CI 版本 | `3.5.1.<run_number>` |
| CI `versionCode` | `2026111706 + run_number` |
| 发布类型 | Release，R8 优化、资源裁剪、`debuggable=false` |

CI 将正整数运行号传入 Gradle 的 `eta.releaseRunNumber`。四段数字版本能由应用更新检查按数字比较；运行号连续递增。更新基础版本或迁移发布工作流时，须确保生成的 `versionCode` 高于所有已发布安装包，并保持在 Android 上限内。重复运行同一个 workflow run 可能沿用同一运行号，不应将其描述为全新版本。

可选 Beta 仅用于开发测试；公开下载默认使用正式 Release。不得把 Debug 构建重命名后作为正式版发布。

## 3. 每次更新的提交准备

1. 完成源码和必要测试；检查本次改动已经提交在 `main`。
2. 更新发布说明或变更记录，说明实际变化、验证结果和已知限制。
3. 同步维护中文、英文首页的当前能力与项目简介；功能实现、候选方案和未来计划分别描述。
4. 保留 README 的一对 `<!-- release-info:start -->` 与 `<!-- release-info:end -->` 标记，供脚本更新版本下载区。
5. 检查凭据、本地工具、SDK、缓存和构建产物均未进入源码提交。
6. 推送 `main`，等待该提交的发布工作流完成。

仓库外部 Description、homepage 和 topics 随每次成功发布由 `scripts/update_repo_metadata.py` 自动刷新；README 外的功能介绍仍由维护者按实际改动维护。

## 4. 自动构建、验证与公开发布

工作流从目标提交构建，使用 JDK 25、Android SDK 37 和仓库 Gradle Wrapper 9.7.1。主流程为：

1. 检出目标 `main` 提交，准备 SDK 与发布签名 Secrets。
2. 生成 CI 数字版本与递增 `versionCode`，执行 `:app:assembleRelease`。
3. 检查 APK 包名、版本、ABI 与 Release 属性；使用 Android Build Tools 的 `apksigner verify --verbose --print-certs` 验证 APK 签名。
4. 将通过验证的 APK 作为固定资产名 `Eta-Ultra.apk`，计算 SHA256。
5. 运行 `scripts/release_metadata.py`，生成发布说明、`release-info.json` 和 README 版本下载区更新。
6. 发布公开、非草稿的 GitHub Release，附 APK、SHA256 校验文件、`release-info.json`，同步 GitHub 仓库简介、下载主页、topics 与中英文 README 版本下载区，并核对最新版入口。

公开发布不再依赖先推 `v*` 标签再手工发布草稿。标签由发布工作流与构建版本关联；Actions Artifact 可供排查，但不能代替长期公开的 Releases 附件。构建、签名或发布失败时应让失败可见，修复后重跑，不能宣称该次推送已完成正式发布。

## 5. 发布元数据脚本

`scripts/release_metadata.py` 负责维护中英文 README 自动区块、发布说明及 `release-info.json`。查看仓库中的脚本帮助了解当前参数：

```bash
python scripts/release_metadata.py --help
```

脚本记录版本、提交与发布资产等可核验事实，并根据工作流输入生成变更信息；它不会自动理解所有业务源码。维护者仍须审阅发布说明，并更新自动区块外的功能介绍、实际验证与已知限制。

两份 README 各保留一对同名边界标记。自动区块更新若产生提交，须使用工作流设计的防重复触发机制；不要为仅更新元数据形成无限发布循环。

## 6. 本地正式构建与复核

在 `main` 的完整已提交源码上配置 JDK 25、SDK 37 和忽略的 `local.properties`。通过安全的本地环境配置提供：

- `ETA_RELEASE_STORE_FILE`
- `ETA_RELEASE_STORE_PASSWORD`
- `ETA_RELEASE_KEY_ALIAS`
- `ETA_RELEASE_KEY_PASSWORD`

执行：

```bash
./gradlew :app:assembleRelease
```

Windows 使用 `gradlew.bat`。本地产物位于 `app/build/outputs/apk/release/`。使用当前 SDK Build Tools 的 `apksigner` 验证签名，并核对证书指纹、包名、版本和 Release 属性；本地版本为基础版本，CI 运行号构建另行增加数字段。公开发布资产统一命名为 `Eta-Ultra.apk`。

## 7. 发布完成判据

- Release 指向预期源码提交，版本与 APK 内部信息一致。
- APK 使用正式证书，签名验证通过，Release 优化启用且不可调试。
- 公共发布页可见，并附 `Eta-Ultra.apk`、SHA256 校验文件、`release-info.json`。
- `/releases/latest` 及固定 APK 下载链接指向预期版本，历史版本继续可查。
- 中文／英文首页版本下载区、当前能力和发布说明一致。

详情见 [分支与发布约定](../docs/BRANCH_AND_RELEASE.md)。
