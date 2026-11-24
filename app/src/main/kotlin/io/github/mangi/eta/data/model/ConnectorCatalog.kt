package io.github.mangi.eta.data.model

/**
 * 生态连接器目录：常用第三方服务的一键接入模板。
 *
 * 每个模板只声明「连到哪、用什么鉴权、默认开哪些工具类别」，
 * 不包含任何密钥；用户点选后仍需填自己的令牌。
 *
 * 说明：条目按通用 MCP / OpenAPI 形态描述，不绑定某一家的私有协议；
 * 是否可用取决于该服务是否公开提供 MCP 或兼容端点，界面上会标注。
 */
internal object ConnectorCatalog {

    /** 连接器分类，用于分组展示。 */
    enum class Category(val label: String) {
        PRODUCTIVITY("效率与文档"),
        CODE("代码与研发"),
        DATA("数据与存储"),
        COMMUNICATION("沟通协作"),
        AUTOMATION("自动化与集成"),
    }

    /**
     * @param id 稳定标识，用于去重与恢复选择
     * @param name 展示名
     * @param category 分组
     * @param endpoint 默认端点；空表示需要用户自行填写
     * @param authorization 默认鉴权方式
     * @param hint 一句话说明，必须如实描述可用条件
     */
    data class Template(
        val id: String,
        val name: String,
        val category: Category,
        val endpoint: String,
        val authorization: String = McpAuthorizationType.BEARER,
        val hint: String,
    )

    val templates: List<Template> = listOf(
        Template(
            id = "notion",
            name = "Notion",
            category = Category.PRODUCTIVITY,
            endpoint = "https://mcp.notion.com/mcp",
            hint = "需 Notion 账号已开通 MCP 访问并生成令牌；端点以官方文档为准。",
        ),
        Template(
            id = "linear",
            name = "Linear",
            category = Category.PRODUCTIVITY,
            endpoint = "https://mcp.linear.app/mcp",
            hint = "需 Linear 工作区授权；个人 API Key 走 Bearer。",
        ),
        Template(
            id = "github",
            name = "GitHub",
            category = Category.CODE,
            endpoint = "https://api.githubcopilot.com/mcp/",
            hint = "使用 GitHub 个人访问令牌；仓库权限由令牌范围决定。",
        ),
        Template(
            id = "gitlab",
            name = "GitLab",
            category = Category.CODE,
            endpoint = "",
            hint = "自建或 SaaS 均可，需填实例的 MCP/兼容端点与访问令牌。",
        ),
        Template(
            id = "sentry",
            name = "Sentry",
            category = Category.CODE,
            endpoint = "https://mcp.sentry.dev/mcp",
            hint = "需 Sentry 组织授权令牌；读取范围由令牌决定。",
        ),
        Template(
            id = "postgres",
            name = "PostgreSQL",
            category = Category.DATA,
            endpoint = "",
            hint = "需先部署数据库 MCP 网关；直连数据库不在此模板范围内。",
        ),
        Template(
            id = "supabase",
            name = "Supabase",
            category = Category.DATA,
            endpoint = "",
            hint = "使用项目级 MCP 端点与 service token；权限随令牌。",
        ),
        Template(
            id = "slack",
            name = "Slack",
            category = Category.COMMUNICATION,
            endpoint = "",
            hint = "需工作区安装对应 MCP 应用并授权；机器人令牌不可直接复用。",
        ),
        Template(
            id = "feishu",
            name = "飞书",
            category = Category.COMMUNICATION,
            endpoint = "",
            hint = "需企业自建应用并开通相应权限；端点由企业域名决定。",
        ),
        Template(
            id = "zapier",
            name = "Zapier",
            category = Category.AUTOMATION,
            endpoint = "https://mcp.zapier.com/api/mcp/mcp",
            hint = "需 Zapier 账号生成 MCP 令牌；可用动作取决于已建 Zap。",
        ),
        Template(
            id = "n8n",
            name = "n8n",
            category = Category.AUTOMATION,
            endpoint = "",
            hint = "自建实例需开启 MCP 并填实例地址；令牌由实例生成。",
        ),
        Template(
            id = "custom",
            name = "自定义 MCP",
            category = Category.AUTOMATION,
            endpoint = "",
            authorization = McpAuthorizationType.NONE,
            hint = "填入任意兼容 MCP 的 HTTP 端点；鉴权按需选择。",
        ),
    )

    fun byId(id: String): Template? = templates.firstOrNull { it.id == id }

    fun grouped(): Map<Category, List<Template>> =
        templates.groupBy { it.category }
}
