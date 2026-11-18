# Eta Ultra 分支与发布约定

本约定适用于 [q3330576893/Eta-por-ultra](https://github.com/q3330576893/Eta-por-ultra)。维护者已明确授权本仓库采用 `main` 作为唯一开发和正式发布主线，替代继承文档中 `test/beta-on-latest` 为发布主线、`main` 仅同步上游的旧规则。

## 唯一主线：main

```text
main  ← 开发整合、版本维护、签名正式 APK 与 GitHub Release
```

临时功能分支可以用于隔离实现或审查；完成验证后合入 `main`。对外发布由 `main` 的目标提交触发，避免出现“工作区有改动，但发布提交没有改动”的情况。上游同步也以经过审查的提交合入 `main`，保留 [Mangi-11/Eta](https://github.com/Mangi-11/Eta)、[Oo0520/Eta-por](https://github.com/Oo0520/Eta-por) 的来源及许可署名。

Beta 仅是开发可选变体，既不是独立的对外发布主线，也不是默认下载入口。

## 每次开发更新必须交付

1. **完整源码提交**：实现、资源、测试和运行所需文件一起提交，避免只存在本地的依赖。
2. **验证记录**：完成与改动相关的检查，注明实际执行结果；设备／ROM 尚未验证的部分明确列出。
3. **同步说明**：维护发布说明或变更记录，并按实际能力更新中文、英文 README 和项目简介，区分继承功能、本轮新增与后续计划。
4. **正式发布**：推送 `main` 后，跟踪 CI 构建、签名验证与公开 GitHub Release；失败就修复并重新运行，不能用 Debug 包替代。
5. **发布核对**：确认版本、源码提交、APK 内部信息、证书、SHA256 与元数据一致。

CI 和 `scripts/release_metadata.py` 可生成构建／提交事实、版本下载区、变更列表和元数据，维护者仍负责准确描述业务变化；自动脚本不等同于自动理解全部代码。

## 推送与构建前检查

从仓库根目录执行：

```bash
git branch --show-current
git status --short
git log -1 --oneline
git diff --check
```

发布目标必须是 `main` 中包含预期改动的提交；本地正式发布检查使用完整、干净且已提交的树。CI 从目标提交检出构建，任何自动生成的 README 元数据更新由工作流单独处理，并防止循环触发。

检查改动是否已发布，应比较目标提交与 Release 的 tag／commit，而不是只看工作区文件。合并后至少执行 `:app:compileDebugKotlin`，并运行受影响的测试；正式交付还须通过 `:app:assembleRelease` 与签名验证。

## 正式版本规则

| 属性 | 规则 |
| --- | --- |
| 品牌 | Eta Ultra |
| 正式应用 ID | `io.github.mangi.eta` |
| 本地基础版本／code | `3.5.4`／`2026111900` |
| CI 版本 | `3.5.4.<run_number>` |
| CI code | `2026111900 + run_number` |
| 系统／ABI | Android 13+（API 33）／ARM64 |
| 工具链 | JDK 25、Android SDK 37、Gradle Wrapper 9.7.1 |
| 产物 | R8 优化、资源裁剪、`debuggable=false` 的签名正式 Release APK |

CI 的正整数运行号递增；四段数字版本用于应用更新检查的数字比较。提高基础版本或迁移工作流时须保证 `versionCode` 高于已发布值，避免升级序号回退。同一次运行重试可能沿用版本号，不能伪装成新版本。

## 自动发布触发与资产

以下事件走同一正式发布路径：

- 每次推送至 `main`。
- 手动 `workflow_dispatch` 运行发布工作流。

CI 执行 `assembleRelease`，验证签名与 APK 信息，再创建公开 GitHub Release。固定资产名为 `Eta-Ultra.apk`，同时附 SHA256 校验文件和 `release-info.json`。

- 最新版：[releases/latest](https://github.com/q3330576893/Eta-por-ultra/releases/latest)
- 固定 APK：[Eta-Ultra.apk](https://github.com/q3330576893/Eta-por-ultra/releases/latest/download/Eta-Ultra.apk)
- 全部历史：[releases](https://github.com/q3330576893/Eta-por-ultra/releases)

对外发布不再以手工草稿、临时 Actions Artifact 或另一个 Beta 分支作为最终交付。发布元数据与两份 README 的版本区由 `scripts/release_metadata.py` 维护；自动区块使用一对 `<!-- release-info:start -->`、`<!-- release-info:end -->` 标记。

## 签名与本地环境

仓库 Actions 使用四个签名 Secrets：`ETA_RELEASE_KEYSTORE_BASE64`、`ETA_RELEASE_STORE_PASSWORD`、`ETA_RELEASE_KEY_ALIAS`、`ETA_RELEASE_KEY_PASSWORD`，以及一个仓库元数据管理 Secret：`ETA_REPO_ADMIN_TOKEN`。CI 解码证书后，以 `ETA_RELEASE_STORE_FILE` 传递临时 KeyStore 路径。后续版本持续使用同一正式证书。

本地私钥保存在用户目录的 `AppData/Local/EtaBuildTools/signing`，与源码分离；证书 Base64、密码、KeyStore、SDK 配置与本地工具均保持在 Git 之外。具体操作见 [发布流程](../.github/RELEASING.md)。

## 合并与共享工作区

- 合并前检查重叠文件，逐项判断语义冲突，保留其它会话已完成的工作。
- 新增资源需检查重复 key，尤其是 `strings.xml`；合并后执行资源编译和相关测试。
- 提交之前核对追踪文件，避免把本地证书、SDK、缓存、构建 APK 或临时脚本放入源码。
- 合并与发布完成后可删除临时功能分支；以 `main` 和公开 Release 作为实现与分发事实来源。
