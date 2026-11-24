package io.github.mangi.eta.data.model

/**
 * 插件 / 技能精选来源目录。
 *
 * 与 [ConnectorCatalog] 的区别：连接器接的是「外部服务」，这里接的是「能力扩展」——
 * 别人写好的 Skill 包，装进来就能被模型调用。
 *
 * 每条只记录公开仓库位置与用途，不含任何凭据；安装时仍走既有的
 * GitHub 检查 → 固定 commit → 下载 → 校验 流程。
 */
internal object PluginCatalog {

    enum class Kind(val label: String) {
        OFFICIAL("官方目录"),
        COMMUNITY("社区精选"),
        TOOLCHAIN("开发工具链"),
    }

    /**
     * @param id 稳定标识
     * @param name 展示名
     * @param repository GitHub owner/repo，或带 tree 路径的完整 URL
     * @param kind 分类
     * @param summary 一句话说明这个来源提供什么能力
     */
    data class Source(
        val id: String,
        val name: String,
        val repository: String,
        val kind: Kind,
        val summary: String,
    )

    val sources: List<Source> = listOf(
        Source(
            id = "openai-skills",
            name = "OpenAI Skills 官方目录",
            repository = "openai/skills",
            kind = Kind.OFFICIAL,
            summary = "官方 curated 目录：文档处理、数据分析、代码审查等通用能力。",
        ),
        Source(
            id = "anthropic-skills",
            name = "Anthropic Skills",
            repository = "anthropics/skills",
            kind = Kind.OFFICIAL,
            summary = "官方示例技能集合，偏文档生成与结构化写作。",
        ),
        Source(
            id = "eta-builtin",
            name = "Eta 内置技能",
            repository = "",
            kind = Kind.OFFICIAL,
            summary = "随应用打包：自我改进、技能创建、技能安装、连接器配置、Android 自动化。",
        ),
        Source(
            id = "custom-repo",
            name = "自定义 GitHub 仓库",
            repository = "",
            kind = Kind.COMMUNITY,
            summary = "填入任意公开仓库或 tree 路径；安装前会先列出可用 Skill 供你选择。",
        ),
    )

    fun byId(id: String): Source? = sources.firstOrNull { it.id == id }

    fun installable(): List<Source> = sources.filter { it.repository.isNotBlank() }

    fun grouped(): Map<Kind, List<Source>> = sources.groupBy { it.kind }
}
