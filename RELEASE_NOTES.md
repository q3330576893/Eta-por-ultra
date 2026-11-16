## 本次更新 · 设置页精简

### 中文

- 完整移除“支持与捐赠”页面及设置入口、导航路由与页面标题。
- 删除支付宝和爱发电专用跳转、微信收款码图片、图片保存逻辑及捐赠专用文案。
- 清理已删除页面的 Lint 基线条目；关于区域继续提供正式包下载、版本记录、源码和问题反馈。
- 修正自动生成仓库简介时误取 README 下载区标题的问题，始终依据项目功能介绍生成简介。
- 继续提供本仓库证书签名的正式 Release APK、SHA256 与版本元数据。

### English

- Removed the entire support/donation page, settings entry, navigation destination and title.
- Deleted dedicated payment links, the payment QR image, image export code and donation-specific strings.
- Removed obsolete Lint baseline entries for the deleted page; signed APK downloads, history, source and feedback remain available.
- Fixed repository-introduction generation so it uses the feature introduction rather than the README download-panel heading.
- Stable APKs continue to use this fork's persistent signing certificate and include checksums and release metadata.

### 验证 / Validation

- 检查剩余设置导航、资源引用和版本比较回归。
- 编译并校验签名正式 Release；检查 APK 资源中已无收款码与捐赠文案。
- CI 保留完整单测与 Lint 报告；以实际工作流结果为准。
