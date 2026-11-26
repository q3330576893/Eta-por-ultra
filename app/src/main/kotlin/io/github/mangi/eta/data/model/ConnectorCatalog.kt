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
     * @param credentialHint 凭据获取指引：去哪个页面、拿什么凭据、需要什么权限。
     *   只描述获取路径，绝不含任何真实凭据。
     * @param requiresGateway 服务方只公开 REST / Bot API，没有 MCP 端点：
     *   必须由用户自建网关转成 MCP，端点不能照抄官方 API 地址。
     *   界面据此把「填了地址却不通」直接指向「缺网关」，而不是笼统的协议错误。
     * @param noConnectorNeeded 本地文件用内置文件工具即可，本模板只是入口占位，
     *   连上去没有任何收益；界面据此劝阻而不是让用户白填一次地址。
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
        val credentialHint: String = "",
        val requiresGateway: Boolean = false,
        val noConnectorNeeded: Boolean = false,
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
            credentialHint = "Notion 设置 → 连接（Connections）里创建集成，复制 Internal Integration Token，" +
                "并把要访问的页面或数据库「共享」给该集成；未共享的页面即使有令牌也读不到。",
        ),
        Template(
            id = "linear",
            name = "Linear",
            category = Category.PRODUCTIVITY,
            endpoint = "https://mcp.linear.app/mcp",
            hint = "需 Linear 工作区授权；个人 API Key 走 Bearer。",
            badge = "L",
            brandColor = 0xFF5E6AD2,
            credentialHint = "Linear → 设置 → 安全与访问（API）→ 个人 API 密钥，新建后立即复制（只显示一次）；" +
                "可见范围等于该账号的团队权限，工作区管理员关闭 API 时无法生成。",
        ),
        Template(
            id = "github",
            name = "GitHub",
            category = Category.CODE,
            endpoint = "https://api.githubcopilot.com/mcp/",
            hint = "使用 GitHub 个人访问令牌；仓库权限由令牌范围决定。",
            badge = "GH",
            brandColor = 0xFF24292F,
            credentialHint = "GitHub → 设置 → 开发者设置 → 个人访问令牌（推荐 Fine-grained），" +
                "按需勾选仓库 Contents/Issues/Pull requests 权限；令牌权限就是 Agent 的全部权限，建议只给只读。",
        ),
        Template(
            id = "gitlab",
            name = "GitLab",
            category = Category.CODE,
            endpoint = "",
            hint = "自建或 SaaS 均可，需填实例的 MCP/兼容端点与访问令牌。",
            badge = "GL",
            brandColor = 0xFFFC6D26,
            credentialHint = "GitLab → 用户设置 → 访问令牌（Access Tokens），勾选 api 或 read_api 并设过期时间；" +
                "SaaS 与自建实例的令牌不通用，端点要填你自己实例的网关地址。",
        ),
        Template(
            id = "sentry",
            name = "Sentry",
            category = Category.CODE,
            endpoint = "https://mcp.sentry.dev/mcp",
            hint = "需 Sentry 组织授权令牌；读取范围由令牌决定。",
            badge = "S",
            brandColor = 0xFF362D59,
            credentialHint = "Sentry → 设置 → 认证令牌（Auth Tokens）新建用户令牌，勾 org:read、project:read、" +
                "event:read；要读源码或发布信息再按需加 project:releases。",
        ),
        Template(
            id = "postgres",
            name = "PostgreSQL",
            category = Category.DATA,
            endpoint = "",
            hint = "需先部署数据库 MCP 网关；直连数据库不在此模板范围内。",
            badge = "PG",
            brandColor = 0xFF336791,
            credentialHint = "先在数据库侧部署 MCP 网关，把连接串、账号密码写在网关的配置文件里（不要填进这里），" +
                "再给网关单独签发一个访问令牌；这里只填网关地址 + 网关令牌，并建议用只读账号。",
            requiresGateway = true,
        ),
        Template(
            id = "supabase",
            name = "Supabase",
            category = Category.DATA,
            endpoint = "",
            hint = "使用项目级 MCP 端点与 service token；权限随令牌。",
            badge = "SB",
            brandColor = 0xFF3ECF8E,
            credentialHint = "Supabase 控制台 → Account → Access Tokens 生成个人访问令牌（sbp_ 开头）；" +
                "数据库密码与 service_role key 都不要填这里，需先在项目侧部署网关，端点填网关地址。",
            requiresGateway = true,
        ),
        Template(
            id = "slack",
            name = "Slack",
            category = Category.COMMUNICATION,
            endpoint = "",
            hint = "需工作区安装对应 MCP 应用并授权；机器人令牌不可直接复用。",
            badge = "SL",
            brandColor = 0xFF4A154B,
            credentialHint = "在 Slack API 后台创建 App，配置 OAuth 范围（channels:read、chat:write 等）后安装到工作区，" +
                "取得 Bot/User Token（xoxb-/xoxp-）；Slack 只提供 Web API，需要自建 MCP 网关转发。",
            requiresGateway = true,
        ),
        Template(
            id = "feishu",
            name = "飞书",
            category = Category.COMMUNICATION,
            endpoint = "",
            hint = "需企业自建应用并开通相应权限；端点由企业域名决定。",
            badge = "飞",
            brandColor = 0xFF3370FF,
            credentialHint = "飞书开放平台创建企业自建应用，开通 im:message、docx:readonly 等权限并提交发布，" +
                "在「凭证与基础信息」取 App ID / App Secret；需自建 MCP 网关用它换 tenant_access_token。",
            requiresGateway = true,
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
            credentialHint = "QQ 开放平台（q.qq.com）创建机器人应用，在「开发设置」取 AppID 与 AppSecret（机器人 Token），" +
                "并配置回调/白名单；平台只提供 Bot API，需要自建 MCP 网关转发后填网关地址。",
            requiresGateway = true,
        ),
        Template(
            id = "wechat",
            name = "微信",
            category = Category.IM,
            endpoint = "",
            hint = "需公众号或已授权的微信机器人凭据；个人微信无官方开放接口，端点请自填。",
            badge = "微",
            brandColor = 0xFF07C160,
            credentialHint = "公众号后台 → 设置与开发 → 基本配置，取 AppID 与 AppSecret 并把出口 IP 加进白名单" +
                "（仅认证的服务号有消息接口权限）；个人微信没有官方开放接口，需自建网关并自行承担封号风险。",
            requiresGateway = true,
        ),
        Template(
            id = "wecom",
            name = "企业微信",
            category = Category.IM,
            endpoint = "",
            hint = "需企业微信自建应用并开通相应权限，取得 CorpID 与 Secret；端点请自填。",
            badge = "企",
            brandColor = 0xFF0082EF,
            credentialHint = "企业微信管理后台 → 应用管理 → 自建应用，在「我的企业」取 CorpID，" +
                "应用详情页取 AgentId 与 Secret；需自建 MCP 网关换 access_token，端点填网关地址。",
            requiresGateway = true,
        ),
        Template(
            id = "dingtalk",
            name = "钉钉",
            category = Category.IM,
            endpoint = "",
            hint = "需在钉钉开放平台创建企业内部应用并申请机器人权限；端点请自填。",
            badge = "钉",
            brandColor = 0xFF0089FF,
            credentialHint = "钉钉开放平台创建「企业内部应用」，开通机器人/消息通知权限并发布上线，" +
                "在凭证页取 AppKey（Client ID）与 AppSecret；需自建 MCP 网关转发，端点填网关地址。",
            requiresGateway = true,
        ),
        Template(
            id = "telegram",
            name = "Telegram",
            category = Category.IM,
            endpoint = "",
            hint = "需在 @BotFather 创建机器人获取 Token；官方只提供 Bot API，需自建网关后填端点。",
            badge = "T",
            brandColor = 0xFF229ED9,
            credentialHint = "在 Telegram 里对 @BotFather 发 /newbot，按提示取名后拿到形如 123456:ABC-DEF 的 Token；" +
                "官方只提供 https://api.telegram.org/bot<token> 这套 Bot API，需自建 MCP 网关转成 MCP 端点。",
            requiresGateway = true,
        ),
        Template(
            id = "discord",
            name = "Discord",
            category = Category.IM,
            endpoint = "",
            hint = "需在 Discord 开发者后台创建应用并取得 Bot Token；官方只提供 Bot API，需自建网关后填端点。",
            badge = "D",
            brandColor = 0xFF5865F2,
            credentialHint = "Discord 开发者后台新建 Application → Bot 页取得 Bot Token（重置后只显示一次），" +
                "在 OAuth2 勾 bot 范围把机器人邀请进服务器并开启 Message Content 意图；需自建 MCP 网关转发。",
            requiresGateway = true,
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
            credentialHint = "Google Cloud Console 启用 Drive API，配置 OAuth 同意屏幕后创建 OAuth 客户端（或服务账号），" +
                "授权 drive.readonly / drive.file 范围；OAuth 走授权码换 refresh token，需自建网关保管，不要填客户端密钥以外的账号密码。",
            requiresGateway = true,
        ),
        Template(
            id = "onedrive",
            name = "OneDrive",
            category = Category.DOCS,
            endpoint = "",
            hint = "需 Microsoft Entra 应用注册并授予文件读写权限；端点请自填。",
            badge = "OD",
            brandColor = 0xFF0078D4,
            credentialHint = "Microsoft Entra 管理中心注册应用，授予 Microsoft Graph 的 Files.ReadWrite（或只读）委派权限，" +
                "用 OAuth 授权码或设备码换 token；组织租户可能需要管理员同意，需自建网关代持令牌。",
            requiresGateway = true,
        ),
        Template(
            id = "word",
            name = "Word 文档",
            category = Category.DOCS,
            endpoint = "",
            hint = "需本地或自建的文档处理服务（读写 .docx）；端点请自填。",
            badge = "W",
            brandColor = 0xFF2B579A,
            credentialHint = "手机里的 .docx 直接用 Agent 的文件工具读写即可，不需要连接器；" +
                "只有要连远程文档服务时才填端点，令牌用该服务自己签发的访问令牌。",
            noConnectorNeeded = true,
        ),
        Template(
            id = "excel",
            name = "Excel 表格",
            category = Category.DOCS,
            endpoint = "",
            hint = "需本地或自建的表格处理服务（读写 .xlsx）；端点请自填。",
            badge = "XL",
            brandColor = 0xFF217346,
            credentialHint = "本地 .xlsx 同样不需要连接器，交给文件工具处理；" +
                "要连在线表格服务（如 Graph/Sheets）时先完成对应 OAuth，再把自建网关地址和网关令牌填进来。",
            noConnectorNeeded = true,
        ),
        Template(
            id = "pdf",
            name = "PDF 处理",
            category = Category.DOCS,
            endpoint = "",
            hint = "需本地或自建的 PDF 解析/生成服务；端点请自填。",
            badge = "PDF",
            brandColor = 0xFFB30B00,
            credentialHint = "本地 PDF 用内置文件工具解析/生成即可，无需连接器；" +
                "要接第三方解析服务时，令牌用该服务的 API Key，并确认它允许你把文档内容上传出去。",
            noConnectorNeeded = true,
        ),
        Template(
            id = "zapier",
            name = "Zapier",
            category = Category.AUTOMATION,
            endpoint = "https://mcp.zapier.com/api/mcp/mcp",
            hint = "需 Zapier 账号生成 MCP 令牌；可用动作取决于已建 Zap。",
            badge = "Z",
            brandColor = 0xFFFF4A00,
            credentialHint = "登录 Zapier 后在 MCP / API 设置页生成 MCP 令牌并复制；" +
                "能被调用的动作只限于你已发布并暴露为工具的 Zap，未建 Zap 时连接成功也没有可用的工具。",
        ),
        Template(
            id = "n8n",
            name = "n8n",
            category = Category.AUTOMATION,
            endpoint = "",
            hint = "自建实例需开启 MCP 并填实例地址；令牌由实例生成。",
            badge = "n8",
            brandColor = 0xFFEA4B71,
            credentialHint = "在自建 n8n 实例里启用 MCP Server 触发器，再于「设置 → n8n API」创建 API Key；" +
                "端点填 https://你的实例/mcp-server/http 这类实例路径，密钥只属于该实例。",
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
            credentialHint = "端点不需要鉴权时令牌留空；需要鉴权时填服务方给的 Bearer Token（不要填账号密码），" +
                "并确认该端点支持 Streamable HTTP 或 SSE 的 MCP 传输。",
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
