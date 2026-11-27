# 尚未启用的本轮译文

当前应用只打包简体中文（`app/build.gradle.kts` 的 `androidResources.localeFilters`），此前也没有完整的英文或繁体中文资源集。

这里保留 MCP 发现页和玻璃设置页的英文、繁体中文译文，键集合与生产目录 `app/src/main/res/values` 中的对应 XML 一致，暂不注册为 Android 应用语言。

2026-11-27 的本地验证表明：仅把本轮 48 个字符串加入 `res/values-en` 和 `res/values-zh-rTW`，会令 Android Lint 将整个应用视为支持这两种语言，继而报告 2,667 项 `MissingTranslation`。因此译文保留于此，现有简体中文生产资源保持完整；没有关闭缺译检查、降低 Lint 级别或扩大基线。

后续启用语言时，应先补齐应用完整的目标语言资源，再调整语言支持声明、移动译文并执行 Lint 和语言切换验收。
