package io.github.mangi.eta.data.model

/**
 * 生态连接器目录：按日常场景提供 MCP 接入配置与官方指南。
 *
 * 模板不包含密钥，也不代表已连接。固定地址来自服务方 HTTP MCP 文档；
 * 需要网关或专属服务地址的条目单独标注，用户完成配置后再发现和选择工具。
 */
internal object ConnectorCatalog {

    /** 连接器分类，用于分组展示。枚举顺序即界面分组顺序。 */
    enum class Category(val label: String) {
        SEARCH("搜索与阅读"),
        MAPS("地图与出行"),
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
        /** 国内用户常用服务；只影响发现页默认筛选，不代表已连接或免费。 */
        val domestic: Boolean = false,
        /** 官方接入文档或服务控制台，不含用户凭据。 */
        val guideUrl: String = "",
    ) {
        val setupLabel: String
            get() = when {
                noConnectorNeeded -> "使用手机内置工具"
                requiresGateway -> "需先部署网关"
                endpoint.isBlank() -> "粘贴专属服务地址"
                authorization == McpAuthorizationType.NONE -> "填写服务地址"
                else -> "配置 API 密钥"
            }
    }

    /** 没有品牌色时的中性灰蓝，保证图标始终有底色。 */
    const val NEUTRAL_BRAND: Long = 0xFF6E7884

    val templates: List<Template> = listOf(
        Template(
            id = "amap",
            name = "高德地图",
            category = Category.MAPS,
            endpoint = "https://mcp.amap.com/mcp",
            authorization = McpAuthorizationType.QUERY_KEY,
            hint = "找附近地点、规划出行路线、查询天气；填写高德 Web 服务 Key。",
            badge = "高德",
            brandColor = 0xFF1677FF,
            credentialHint = "高德开放平台 → 控制台 → 应用管理，创建应用并添加「Web服务」类型 Key；" +
                "将 Key 单独填入密钥栏，地址无需附加 key 参数；可用能力和额度以账号为准。",
            domestic = true,
            guideUrl = "https://lbs.amap.com/api/mcp-server/gettingstarted",
        ),
        Template(
            id = "baidu_maps",
            name = "百度地图",
            category = Category.MAPS,
            endpoint = "https://mcp.map.baidu.com/mcp",
            authorization = McpAuthorizationType.QUERY_AK,
            hint = "查地点、公交驾车路线与路况；需百度地图服务端 AK 及对应权限。",
            badge = "百度",
            brandColor = 0xFF2932E1,
            credentialHint = "百度地图开放平台 → 控制台创建应用，取得服务端 AK 并按官方指南开通 MCP 权限；" +
                "将 AK 单独填入密钥栏，使用 /mcp 地址；部分高级能力需额外开通。",
            domestic = true,
            guideUrl = "https://github.com/baidu-maps/mcp/blob/main/README_zh.md",
        ),
        Template(
            id = "bocha",
            name = "博查搜索",
            category = Category.SEARCH,
            endpoint = "https://mcp.bochaai.com/mcp",
            hint = "搜索中文网页与近期资讯；需博查 API Key 和可用额度。",
            badge = "博查",
            brandColor = 0xFF3D52D5,
            credentialHint = "登录博查开放平台 open.bochaai.com，在控制台获取 API Key 并检查额度；" +
                "在密钥栏填写原始 Key，客户端自动添加 Bearer；使用官方 Streamable HTTP 地址。",
            domestic = true,
            guideUrl = "https://docs.bocha.cn/tools/mcp",
        ),
        Template(
            id = "metaso",
            name = "秘塔 AI 搜索",
            category = Category.SEARCH,
            endpoint = "https://metaso.cn/api/mcp",
            hint = "检索网页、文档与论文，读取公开内容；需秘塔搜索 API Key。",
            badge = "秘塔",
            brandColor = 0xFF6D4AFF,
            credentialHint = "秘塔搜索 API 控制台 → API Keys 创建密钥并检查配额；" +
                "密钥栏填写原始 API Key。官方 MCP 发布页列有 HTTP 配置，无需在手机安装 Node.js。",
            domestic = true,
            guideUrl = "https://www.modelscope.cn/mcp/servers/metasota/metaso-search",
        ),
        Template(
            id = "zhipu_search",
            name = "智谱联网搜索",
            category = Category.SEARCH,
            endpoint = "https://open.bigmodel.cn/api/mcp/web_search_prime/mcp",
            hint = "查询近期公开信息；需 GLM Coding Plan 套餐或体验卡。",
            badge = "智搜",
            brandColor = 0xFF3859FF,
            credentialHint = "智谱个人编程套餐「套餐概览」或团队编程套餐「我的套餐」中新建 API Key；" +
                "该 MCP 属于 GLM Coding Plan，普通模型 API Key 的权限和额度未必适用。",
            domestic = true,
            guideUrl = "https://docs.bigmodel.cn/cn/coding-plan/mcp/search-mcp-server",
        ),
        Template(
            id = "zhipu_reader",
            name = "智谱网页读取",
            category = Category.SEARCH,
            endpoint = "https://open.bigmodel.cn/api/mcp/web_reader/mcp",
            hint = "提取链接正文、读取文章与文档；需 GLM Coding Plan 套餐或体验卡。",
            badge = "智读",
            brandColor = 0xFF2D73C7,
            credentialHint = "在智谱 GLM Coding Plan 个人或团队套餐入口新建 API Key，填入密钥栏；" +
                "使用 /mcp HTTP 地址，网页访问限制仍可能导致读取失败。",
            domestic = true,
            guideUrl = "https://docs.bigmodel.cn/cn/coding-plan/mcp/reader-mcp-server",
        ),
        Template(
            id = "bailian_search",
            name = "阿里百炼联网搜索",
            category = Category.SEARCH,
            endpoint = "https://dashscope.aliyuncs.com/api/v1/mcps/WebSearch/mcp",
            hint = "通过百炼 One Key 服务搜索互联网；需百炼 API Key 及可用额度。",
            badge = "百搜",
            brandColor = 0xFFFF6A00,
            credentialHint = "阿里云百炼控制台获取 DashScope API Key，确认服务地域与计费额度；" +
                "Key 填入密钥栏，客户端按 Bearer 发送；此模板只配置官方联网搜索服务。",
            domestic = true,
            guideUrl = "https://help.aliyun.com/zh/model-studio/mcp-external-calls",
        ),
        Template(
            id = "bailian_mcp",
            name = "阿里百炼 MCP 广场",
            category = Category.AUTOMATION,
            endpoint = "",
            hint = "浏览并接入云端工具；从具体服务安装区域复制 HTTP MCP 地址。",
            badge = "百炼",
            brandColor = 0xFFB84E00,
            credentialHint = "百炼控制台 → MCP 广场，选择并按要求开通服务，在安装区域复制 Streamable HTTP 地址；" +
                "密钥栏填写 DashScope API Key，部分服务还需在控制台配置第三方凭据。",
            domestic = true,
            guideUrl = "https://help.aliyun.com/zh/model-studio/official-and-third-party-mcp",
        ),
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
            domestic = true,
            guideUrl = "https://open.feishu.cn/document/mcp_open_tools/end-user-call-remote-mcp-server",
            name = "飞书云文档",
            category = Category.DOCS,
            endpoint = "",
            authorization = McpAuthorizationType.NONE,
            hint = "在飞书 MCP 平台添加云文档并授权，复制专属 HTTP 地址；个人托管服务仍在内测并有迁移安排。",
            badge = "飞",
            brandColor = 0xFF3370FF,
            credentialHint = "飞书开放平台 → MCP → 创建服务，添加云文档工具并完成用户授权，复制 Streamable HTTP 地址；" +
                "完整地址本身包含访问凭据，请妥善保管，令牌栏留空；不要填写 App Secret。" +
                "新建托管服务可能需每 7 天重新授权，MCP Token 链路将逐步下线，以平台提示为准。",
        ),
        Template(
            id = "wps_dbsheet",
            domestic = true,
            guideUrl = "https://open.wps.cn/documents/app-integration-dev/mcp-server/tools/mcp_kso-dbsheet",
            name = "WPS 365 多维表格",
            category = Category.DOCS,
            endpoint = "https://openapi.wps.cn/mcp/v2/kso-dbsheet/message",
            hint = "创建、检索和更新企业多维表格；需 WPS 365 应用权限与用户授权，不是通用 Excel 编辑服务。",
            badge = "WPS",
            brandColor = 0xFFEB3340,
            credentialHint = "WPS 开放平台创建企业应用，申请多维表格 MCP 的用户委托读写权限并完成用户授权；" +
                "按官方对接指南关闭接口签名，将用户 access_token 填入令牌栏，客户端自动加 Bearer。" +
                "不填写 App Secret，令牌到期后重新获取。",
        ),
        Template(
            id = "laiye_adp",
            domestic = true,
            guideUrl = "https://developer.cloud.tencent.com/mcp/server/11820",
            name = "来也 ADP 文档解析",
            category = Category.DOCS,
            endpoint = "https://adp.laiye.com/mcp",
            authorization = McpAuthorizationType.QUERY_KEY,
            hint = "解析 PDF、扫描图片及 Word、Excel、PPT，提取文字、表格和版面；需 ADP API Key 与额度。",
            badge = "来也",
            brandColor = 0xFF6246E5,
            credentialHint = "登录来也 ADP 控制台 → 个人设置 → 我的 MCP 获取 API Key，单独填入密钥栏，地址无需附加 key 参数；" +
                "按服务用量计费，长文档建议使用异步任务并查询结果，避免同步等待超过客户端超时。",
        ),
        Template(
            id = "paddleocr",
            domestic = true,
            guideUrl = "https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/integrations/mcp_server.md",
            name = "PaddleOCR 文档识别",
            category = Category.DOCS,
            endpoint = "",
            authorization = McpAuthorizationType.NONE,
            hint = "识别图片、PDF 文字与版面并导出 Markdown；先在服务器部署官方 HTTP MCP 服务。",
            badge = "OCR",
            brandColor = 0xFF1769C2,
            credentialHint = "按 PaddleOCR 官方 MCP 指南在服务器启用 HTTP 模式，复制手机可访问的 MCP 地址；" +
                "没有统一公共端点，手机无需运行 stdio 命令。推理平台凭据配置在服务端，" +
                "令牌栏仅填写你部署的服务或反向代理签发的 Bearer Token（如有）。",
        ),
        // ---- 即时通讯：均无公开通用 MCP 端点，统一留空由用户填写网关地址 ----
        Template(
            id = "qq",
            domestic = true,
            guideUrl = "https://q.qq.com/",
            name = "QQ 机器人",
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
            domestic = true,
            guideUrl = "https://mp.weixin.qq.com/",
            name = "微信公众号",
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
            domestic = true,
            guideUrl = "https://developer.work.weixin.qq.com/",
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
            domestic = true,
            guideUrl = "https://open.dingtalk.com/",
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
                "选择 Streamable HTTP 地址；旧式 GET /sse 传输需先转接，POST 响应中的 SSE 帧可直接读取。",
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
