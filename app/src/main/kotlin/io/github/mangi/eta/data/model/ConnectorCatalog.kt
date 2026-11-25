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

    /** 连接器分类，用于分组展示。枚举顺序即界面分组顺序。 */
    enum class Category(val label: String) {
        PRODUCTIVITY("效率与文档"),
        CODE("代码与研发"),
        DATA("数据与存储"),
        COMMUNICATION("沟通协作"),
        IM("即时通讯"),
        DOCS("办公文档"),
        AUTOMATION("自动化与集成"),
    }

    /**
     * @param id 稳定标识，用于去重与恢复选择
     * @param name 展示名
     * @param category 分组
     * @param endpoint 默认端点；空表示需要用户自行填写
     * @param authorization 默认鉴权方式
     * @param hint 一句话说明，必须如实描述可用条件
     * @param badge 图标内的短标识（1-3 字符）：品牌图标由 Compose 现场绘制，不引入图片资源
     * @param brandColor 品牌主色（ARGB），用于绘制圆角方块底；界面据此区分不同服务
     */
    data class Template(
        val id: String,
        val name: String,
        val category: Category,
        val endpoint: String,
        val authorization: String = McpAuthorizationType.BEARER,
        val hint: String,
        val badge: String = "",
        val brandColor: Long = NEUTRAL_BRAND,
    )

    /** 没有品牌色时的中性灰蓝，保证图标始终有底色。 */
    const val NEUTRAL_BRAND: Long = 0xFF6E7884

    val templates: List<Template> = listOf(
        Template(
            id = "notion",
            name = "Notion",
            category = Category.PRODUCTIVITY,
            endpoint = "https://mcp.notion.com/mcp",
            hint = "需 Notion 账号已开通 MCP 访问并生成令牌；端点以官方文档为准。",
            badge = "N",
            brandColor = 0xFF000000,
        ),
        Template(
            id = "linear",
            name = "Linear",
            category = Category.PRODUCTIVITY,
            endpoint = "https://mcp.linear.app/mcp",
            hint = "需 Linear 工作区授权；个人 API Key 走 Bearer。",
            badge = "L",
            brandColor = 0xFF5E6AD2,
        ),
        Template(
            id = "github",
            name = "GitHub",
            category = Category.CODE,
            endpoint = "https://api.githubcopilot.com/mcp/",
            hint = "使用 GitHub 个人访问令牌；仓库权限由令牌范围决定。",
            badge = "GH",
            brandColor = 0xFF24292F,
        ),
        Template(
            id = "gitlab",
            name = "GitLab",
            category = Category.CODE,
            endpoint = "",
            hint = "自建或 SaaS 均可，需填实例的 MCP/兼容端点与访问令牌。",
            badge = "GL",
            brandColor = 0xFFFC6D26,
        ),
        Template(
            id = "sentry",
            name = "Sentry",
            category = Category.CODE,
            endpoint = "https://mcp.sentry.dev/mcp",
            hint = "需 Sentry 组织授权令牌；读取范围由令牌决定。",
            badge = "S",
            brandColor = 0xFF362D59,
        ),
        Template(
            id = "postgres",
            name = "PostgreSQL",
            category = Category.DATA,
            endpoint = "",
            hint = "需先部署数据库 MCP 网关；直连数据库不在此模板范围内。",
            badge = "PG",
            brandColor = 0xFF336791,
        ),
        Template(
            id = "supabase",
            name = "Supabase",
            category = Category.DATA,
            endpoint = "",
            hint = "使用项目级 MCP 端点与 service token；权限随令牌。",
            badge = "SB",
            brandColor = 0xFF3ECF8E,
        ),
        Template(
            id = "slack",
            name = "Slack",
            category = Category.COMMUNICATION,
            endpoint = "",
            hint = "需工作区安装对应 MCP 应用并授权；机器人令牌不可直接复用。",
            badge = "SL",
            brandColor = 0xFF4A154B,
        ),
        Template(
            id = "feishu",
            name = "飞书",
            category = Category.COMMUNICATION,
            endpoint = "",
            hint = "需企业自建应用并开通相应权限；端点由企业域名决定。",
            badge = "飞",
            brandColor = 0xFF3370FF,
        ),
        // ---- 即时通讯：均无公开通用 MCP 端点，统一留空由用户填写网关地址 ----
        Template(
            id = "qq",
            name = "QQ",
            category = Category.IM,
            endpoint = "",
            hint = "需在 QQ 开放平台创建机器人并获取 AppID/Token；平台未提供通用 MCP 端点，请自填网关地址。",
            badge = "Q",
            brandColor = 0xFF12B7F5,
        ),
        Template(
            id = "wechat",
            name = "微信",
            category = Category.IM,
            endpoint = "",
            hint = "需公众号或已授权的微信机器人凭据；个人微信无官方开放接口，端点请自填。",
            badge = "微",
            brandColor = 0xFF07C160,
        ),
        Template(
            id = "wecom",
            name = "企业微信",
            category = Category.IM,
            endpoint = "",
            hint = "需企业微信自建应用并开通相应权限，取得 CorpID 与 Secret；端点请自填。",
            badge = "企",
            brandColor = 0xFF0082EF,
        ),
        Template(
            id = "dingtalk",
            name = "钉钉",
            category = Category.IM,
            endpoint = "",
            hint = "需在钉钉开放平台创建企业内部应用并申请机器人权限；端点请自填。",
            badge = "钉",
            brandColor = 0xFF0089FF,
        ),
        Template(
            id = "telegram",
            name = "Telegram",
            category = Category.IM,
            endpoint = "",
            hint = "需在 @BotFather 创建机器人获取 Token；官方只提供 Bot API，需自建网关后填端点。",
            badge = "T",
            brandColor = 0xFF229ED9,
        ),
        Template(
            id = "discord",
            name = "Discord",
            category = Category.IM,
            endpoint = "",
            hint = "需在 Discord 开发者后台创建应用并取得 Bot Token；官方只提供 Bot API，需自建网关后填端点。",
            badge = "D",
            brandColor = 0xFF5865F2,
        ),
        // ---- 办公文档：云盘与文档处理同样依赖自建或官方 MCP 网关 ----
        Template(
            id = "google_drive",
            name = "Google Drive",
            category = Category.DOCS,
            endpoint = "",
            hint = "需 Google Cloud 项目启用 Drive API 并完成 OAuth 授权；端点请自填。",
            badge = "GD",
            brandColor = 0xFFFBBC04,
        ),
        Template(
            id = "onedrive",
            name = "OneDrive",
            category = Category.DOCS,
            endpoint = "",
            hint = "需 Microsoft Entra 应用注册并授予文件读写权限；端点请自填。",
            badge = "OD",
            brandColor = 0xFF0078D4,
        ),
        Template(
            id = "word",
            name = "Word 文档",
            category = Category.DOCS,
            endpoint = "",
            hint = "需本地或自建的文档处理服务（读写 .docx）；端点请自填。",
            badge = "W",
            brandColor = 0xFF2B579A,
        ),
        Template(
            id = "excel",
            name = "Excel 表格",
            category = Category.DOCS,
            endpoint = "",
            hint = "需本地或自建的表格处理服务（读写 .xlsx）；端点请自填。",
            badge = "XL",
            brandColor = 0xFF217346,
        ),
        Template(
            id = "pdf",
            name = "PDF 处理",
            category = Category.DOCS,
            endpoint = "",
            hint = "需本地或自建的 PDF 解析/生成服务；端点请自填。",
            badge = "PDF",
            brandColor = 0xFFB30B00,
        ),
        Template(
            id = "zapier",
            name = "Zapier",
            category = Category.AUTOMATION,
            endpoint = "https://mcp.zapier.com/api/mcp/mcp",
            hint = "需 Zapier 账号生成 MCP 令牌；可用动作取决于已建 Zap。",
            badge = "Z",
            brandColor = 0xFFFF4A00,
        ),
        Template(
            id = "n8n",
            name = "n8n",
            category = Category.AUTOMATION,
            endpoint = "",
            hint = "自建实例需开启 MCP 并填实例地址；令牌由实例生成。",
            badge = "n8",
            brandColor = 0xFFEA4B71,
        ),
        Template(
            id = "custom",
            name = "自定义 MCP",
            category = Category.AUTOMATION,
            endpoint = "",
            authorization = McpAuthorizationType.NONE,
            hint = "填入任意兼容 MCP 的 HTTP 端点；鉴权按需选择。",
            badge = "＋",
            brandColor = 0xFF5B5BF6,
        ),
    )

    fun byId(id: String): Template? = templates.firstOrNull { it.id == id }

    fun grouped(): Map<Category, List<Template>> =
        templates.groupBy { it.category }

    /**
     * 按枚举声明顺序分组，界面直接遍历即可稳定保持
     * 「效率→代码→数据→沟通→即时通讯→办公文档→自动化」。
     */
    fun groupedInOrder(): List<Pair<Category, List<Template>>> =
        Category.entries.mapNotNull { category ->
            templates.filter { it.category == category }
                .takeIf { it.isNotEmpty() }
                ?.let { category to it }
        }
}
