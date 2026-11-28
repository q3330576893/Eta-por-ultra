package io.github.mangi.eta.ui.screens.mcp

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mcp.McpDiscoveryChange
import io.github.mangi.eta.agent.mcp.McpEmptyCatalogException
import io.github.mangi.eta.agent.mcp.McpGatewayGuide
import io.github.mangi.eta.agent.mcp.McpProtocolStatus
import io.github.mangi.eta.agent.mcp.McpServerManager
import io.github.mangi.eta.agent.mcp.validateMcpEndpoint
import io.github.mangi.eta.data.model.ConnectorCatalog
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.validateConnectorEndpoint
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaDesign
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.InlineAlert
import io.github.mangi.eta.ui.components.InlineAlertLevel
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 新增连接的草稿：凭据只随当前目标保留，模板重选不覆盖用户输入。 */
internal data class McpConnectorDraft(
    val connectorId: String? = null,
    val name: String = "",
    val url: String = "",
    val token: String = "",
    val authorizationType: String = McpAuthorizationType.NONE,
    val templateAuthorizationType: String = authorizationType,
) {
    fun selectTemplate(template: ConnectorCatalog.Template): McpConnectorDraft =
        if (connectorId == template.id) {
            this
        } else {
            copy(
                connectorId = template.id,
                name = template.name,
                url = template.endpoint,
                token = "",
                authorizationType = template.authorization,
                templateAuthorizationType = template.authorization,
            )
        }

    fun withToken(value: String): McpConnectorDraft = copy(
        token = value,
        authorizationType = McpCredentialUpdate.from(templateAuthorizationType, value).authorizationType,
    )

    fun toServerSetting(): McpServerSetting = McpServerSetting(
        id = "",
        name = name.trim(),
        url = url.trim(),
        protocolMode = McpProtocolMode.AUTO,
        // 查询鉴权缺值仍保留参数类型，由客户端报错，不能降为匿名并保存 URL 中的旧密钥。
        authorizationType = McpCredentialUpdate.from(authorizationType, token).authorizationType,
    )
}

/** 保存前固定输入；查询鉴权的参数类型独立于密钥是否存在，清空后仍可正确重填。 */
internal class McpCredentialUpdate private constructor(
    val authorizationType: String,
    val value: String,
) {
    companion object {
        fun from(currentType: String, value: String): McpCredentialUpdate = McpCredentialUpdate(
            authorizationType = when {
                McpAuthorizationType.queryParameterName(currentType) != null -> currentType
                value.isBlank() -> McpAuthorizationType.NONE
                else -> McpAuthorizationType.BEARER
            },
            value = value,
        )
    }
}

/** 两个独立的发现范围；国外列表不再混入国内服务。 */
internal enum class McpConnectorRegion { DOMESTIC, OVERSEAS }

/** 自定义入口独立于目录，搜索和场景过滤只作用于当前地区。 */
internal fun filterMcpConnectorTemplates(
    templates: List<ConnectorCatalog.Template>,
    region: McpConnectorRegion = McpConnectorRegion.DOMESTIC,
    query: String = "",
    category: ConnectorCatalog.Category? = null,
): List<ConnectorCatalog.Template> {
    val keyword = query.trim()
    return templates.filter { template ->
        template.id != "custom" &&
            template.domestic == (region == McpConnectorRegion.DOMESTIC) &&
            (category == null || template.category == category) &&
            (keyword.isEmpty() || listOf(template.name, template.category.label, template.hint)
                .any { it.contains(keyword, ignoreCase = true) })
    }
}

@Composable
internal fun McpServersScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(McpConnectorDraft()) }
    val name = draft.name
    val url = draft.url
    val token = draft.token
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val selectedConnectorId = draft.connectorId
    val connector = selectedConnectorId?.let { ConnectorCatalog.byId(it) }
    val connectorHint = connector?.hint
    val connectorCredentialHint = connector?.credentialHint?.takeIf { it.isNotBlank() }
    // 选中的模板是不是「服务方只给 REST/Bot API，必须自建网关」：决定提示语气与错误解读。
    val connectorRequiresGateway = connector?.requiresGateway == true
    // 本地文件类模板：连上去没有任何收益，界面劝阻而不是让用户白填一次地址。
    val connectorNoConnectorNeeded = connector?.noConnectorNeeded == true
    var discoveryRegion by rememberSaveable { mutableStateOf<McpConnectorRegion?>(null) }
    val overviewListState = rememberLazyListState()
    val customTemplate = remember { requireNotNull(ConnectorCatalog.byId("custom")) }
    val discoveryBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = discoveryBackState,
        isBackEnabled = discoveryRegion != null && !showAdd,
        onBackCompleted = { discoveryRegion = null },
    )

    fun configure(template: ConnectorCatalog.Template) {
        if (working) return
        if (draft.connectorId != template.id) {
            draft = draft.selectTemplate(template)
            error = null
        }
        showAdd = true
    }

    fun configureCustom() {
        configure(customTemplate)
    }

    fun openGuide(template: ConnectorCatalog.Template) {
        runCatching { uriHandler.openUri(template.guideUrl) }.onFailure {
            Toast.makeText(context, R.string.mcp_discovery_guide_failed, Toast.LENGTH_LONG).show()
        }
    }

    val region = discoveryRegion
    if (region != null) {
        key(region) {
            McpConnectorDiscoveryPage(
                region = region,
                enabled = !working,
                onBack = { discoveryRegion = null },
                onCustom = ::configureCustom,
                onConfigure = ::configure,
                onGuide = ::openGuide,
            )
        }
    } else {
        MiuixScaffoldPage(
            title = stringResource(R.string.route_mcp_servers),
            onBack = onBack,
            listState = overviewListState,
            actions = {
                IconButton(onClick = ::configureCustom, enabled = !working) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.mcp_discovery_custom_title),
                    )
                }
            },
        ) {
            // 发现入口在首屏；主页不再展开整个连接器目录。
            item(key = "connector-menu") {
                McpConnectorMenu(
                    onRegion = { discoveryRegion = it },
                    onCustom = ::configureCustom,
                )
            }
            item(key = "servers-title") {
                EtaPreferenceGroupTitle(stringResource(R.string.mcp_configured_servers, servers.size))
            }
            if (servers.isEmpty()) {
                item(key = "servers-empty") {
                    EtaPreferenceGroup {
                        ListEmptyState(
                            title = stringResource(R.string.mcp_empty_title),
                            summary = stringResource(R.string.mcp_empty_summary),
                            icon = Icons.Rounded.Hub,
                            action = {
                                EtaTextButton(
                                    text = stringResource(R.string.mcp_add_server),
                                    onClick = ::configureCustom,
                                )
                            },
                        )
                    }
                }
            } else {
                servers.forEach { server ->
                    item(key = "server-${server.id}") {
                        EtaPreferenceGroup {
                            EtaArrowPreference(
                                title = server.name,
                                summary = when {
                                    server.tools.isNotEmpty() -> stringResource(
                                        R.string.mcp_server_row_summary,
                                        server.activeTools.size,
                                        server.tools.size,
                                    )
                                    server.lastRefreshedAt == null -> "已保存，但从未握手成功 · 点开重试"
                                    else -> "已保存，服务端没有返回工具 · 点开刷新"
                                },
                                onClick = { onNavigate(AppRoute.McpServerDetail(server.id)) },
                            )
                        }
                    }
                }
            }
        }
    }

    EtaWindowDialog(
        show = showAdd,
        title = connector?.let { stringResource(R.string.mcp_discovery_configure_service, it.name) }
            ?: stringResource(R.string.mcp_add_server),
        onDismissRequest = { if (!working) showAdd = false },
    ) {
        // 只有表单滚动，按钮固定在底部：输入法弹出或错误较长时确认操作仍然可达。
        val urlCheck = remember(url) { validateConnectorEndpoint(url.trim()) }
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                connector?.let { selected ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ConnectorBrandIcon(selected.badge, selected.brandColor, enabled = !working)
                        Text(
                            text = connectorSetupLabel(selected),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                    if (selected.guideUrl.isNotBlank()) {
                        EtaTextButton(
                            text = stringResource(R.string.mcp_discovery_guide),
                            onClick = { openGuide(selected) },
                        )
                    }
                }
                // 端点为空的模板不会自动带出地址：明确告诉用户这里要自己填，而不是留个空输入框。
                // 需要网关的模板把「为什么必须自建」讲在前面，用户才不会去粘官方 API 地址。
                if (connector?.endpoint?.isEmpty() == true && !connectorNoConnectorNeeded) {
                    InlineAlert(
                        text = if (connectorRequiresGateway) {
                            stringResource(R.string.mcp_discovery_gateway_required)
                        } else {
                            stringResource(R.string.mcp_discovery_instance_required)
                        },
                        level = InlineAlertLevel.Warning,
                    )
                }
                if (connectorNoConnectorNeeded) {
                    InlineAlert(
                        text = stringResource(R.string.mcp_discovery_local_tools_hint),
                        level = InlineAlertLevel.Info,
                    )
                }
                // 粘了官方 API 域名时当场点出来，不必等一次必然失败的连接。
                val gatewayHostHint = remember(url) { McpGatewayGuide.hintForEndpoint(url) }
                gatewayHostHint?.let { hint ->
                    InlineAlert(text = hint, level = InlineAlertLevel.Warning)
                }
                connectorHint?.let { hint ->
                    Text(
                        text = hint,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                // 凭据获取指引：只讲去哪里拿、需要什么权限，绝不预置任何凭据。
                connectorCredentialHint?.let { credential ->
                    Text(
                        text = credential,
                        style = MiuixTheme.textStyles.footnote2.copy(fontSize = 11.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                TextField(
                    value = name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = stringResource(R.string.mcp_server_name),
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = url,
                    onValueChange = { draft = draft.copy(url = it) },
                    label = stringResource(R.string.mcp_server_url),
                    singleLine = true,
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                // 地址校验是纯本地判断：填错立刻说清原因，不必等连接失败才发现。
                if (url.isNotBlank()) {
                    urlCheck.error?.let { reason ->
                        InlineAlert(text = reason, level = InlineAlertLevel.Error)
                    }
                    urlCheck.warning?.let { note ->
                        InlineAlert(text = note, level = InlineAlertLevel.Warning)
                    }
                }
                TextField(
                    value = token,
                    onValueChange = { draft = draft.withToken(it) },
                    label = stringResource(
                        if (connector?.let { McpAuthorizationType.queryParameterName(it.authorization) } != null) {
                            R.string.mcp_discovery_api_key
                        } else {
                            R.string.mcp_discovery_access_token
                        },
                    ),
                    singleLine = true,
                    enabled = !working,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                connector?.let { McpAuthorizationType.queryParameterName(it.authorization) }?.let { parameter ->
                    Text(
                        text = stringResource(R.string.mcp_discovery_query_key_hint, parameter),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                error?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
            MiuixDialogActions(
                modifier = Modifier.padding(top = 16.dp),
                confirmText = if (working) {
                    stringResource(R.string.mcp_connecting)
                } else {
                    stringResource(R.string.mcp_add_server)
                },
                confirmEnabled = !working && urlCheck.isValid,
                onCancel = { showAdd = false },
                onConfirm = {
                    val normalizedName = name.trim()
                    val normalizedUrl = url.trim()
                    error = when {
                        normalizedName.isBlank() -> resources.getString(R.string.mcp_name_required)
                        // 本地格式校验先行：能明确说清原因的错误不必等联网才发现。
                        urlCheck.error != null -> urlCheck.error
                        else -> runCatching { validateMcpEndpoint(normalizedUrl) }
                            .exceptionOrNull()?.message
                    }
                    if (error == null) scope.launch {
                        working = true
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val serverDraft = draft.toServerSetting()
                                val discovered = McpServerManager.discover(serverDraft, token)
                                McpServerRepository.add(discovered, token)
                            }
                        }
                        working = false
                        result.onSuccess { saved ->
                            showAdd = false
                            draft = McpConnectorDraft()
                            error = null
                            // 0 工具是「连接成功但服务端没发布工具」，与「连接失败」是两件事：
                            // 前者已经落库，用户进详情页只会看到空列表，必须在这里说清原因。
                            val message = if (saved.tools.isEmpty()) {
                                "服务器已保存，但服务端没有返回任何工具：" +
                                    "网关需先暴露工具（或先建好 Zap / 发布应用），否则连上也没有可用的工具"
                            } else {
                                resources.getString(R.string.mcp_server_added, saved.tools.size)
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }.onFailure {
                            // 失败时服务器没有被保存：错误要写成「未保存」，避免用户以为设置已经生效。
                            val reason = McpGatewayGuide.describeFailure(normalizedUrl, it)
                            error = "未保存：$reason"
                        }
                    }
                },
            )
        }
    }
}

/** 首层只显示地区入口和自定义入口，列表长度不随目录扩充而增长。 */
@Composable
internal fun McpConnectorMenu(
    onRegion: (McpConnectorRegion) -> Unit,
    onCustom: () -> Unit,
) {
    EtaPreferenceGroupTitle(stringResource(R.string.mcp_discovery_title))
    EtaPreferenceGroup {
        EtaArrowPreference(
            title = stringResource(R.string.mcp_discovery_domestic),
            summary = stringResource(
                R.string.mcp_discovery_domestic_summary,
                filterMcpConnectorTemplates(ConnectorCatalog.templates, McpConnectorRegion.DOMESTIC).size,
            ),
            onClick = { onRegion(McpConnectorRegion.DOMESTIC) },
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaArrowPreference(
            title = stringResource(R.string.mcp_discovery_overseas),
            summary = stringResource(
                R.string.mcp_discovery_overseas_summary,
                filterMcpConnectorTemplates(ConnectorCatalog.templates, McpConnectorRegion.OVERSEAS).size,
            ),
            onClick = { onRegion(McpConnectorRegion.OVERSEAS) },
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaArrowPreference(
            title = stringResource(R.string.mcp_discovery_custom_title),
            summary = stringResource(R.string.mcp_discovery_custom_summary),
            onClick = onCustom,
        )
    }
}

/** 地区二级页：每张卡片是独立的懒加载项，底部由页面骨架保留导航栏安全区。 */
@Composable
internal fun McpConnectorDiscoveryPage(
    region: McpConnectorRegion,
    enabled: Boolean,
    onBack: () -> Unit,
    onCustom: () -> Unit,
    onConfigure: (ConnectorCatalog.Template) -> Unit,
    onGuide: (ConnectorCatalog.Template) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    var query by rememberSaveable(region) { mutableStateOf("") }
    var category by rememberSaveable(region) { mutableStateOf<ConnectorCatalog.Category?>(null) }
    val regionTemplates = remember(region) {
        filterMcpConnectorTemplates(ConnectorCatalog.templates, region)
    }
    val visibleTemplates = remember(region, query, category) {
        filterMcpConnectorTemplates(ConnectorCatalog.templates, region, query, category)
    }
    val categories = remember(region) {
        ConnectorCatalog.Category.entries.filter { candidate -> regionTemplates.any { it.category == candidate } }
    }
    val scope = rememberCoroutineScope()
    fun resetScroll() { scope.launch { listState.scrollToItem(0) } }

    MiuixScaffoldPage(
        title = stringResource(
            if (region == McpConnectorRegion.DOMESTIC) R.string.mcp_discovery_domestic_short
            else R.string.mcp_discovery_overseas_short,
        ),
        onBack = onBack,
        listState = listState,
        compactTitle = true,
        actions = {
            IconButton(onClick = onCustom, enabled = enabled) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.mcp_discovery_custom_title))
            }
        },
    ) {
        item(key = "connector-search") {
            TextField(
                value = query,
                onValueChange = { query = it; resetScroll() },
                label = stringResource(R.string.mcp_discovery_search_hint),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = EtaPreferenceDefaults.SidePadding),
            )
        }
        item(key = "connector-filters") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding, vertical = 12.dp),
            ) {
                ConnectorFilterChip(
                    text = stringResource(R.string.mcp_discovery_all),
                    selected = category == null,
                    onClick = { category = null; resetScroll() },
                )
                categories.forEach { candidate ->
                    ConnectorFilterChip(
                        text = connectorCategoryLabel(candidate),
                        selected = category == candidate,
                        onClick = { category = candidate; resetScroll() },
                    )
                }
            }
        }
        item(key = "connector-conditions") {
            Text(
                text = stringResource(R.string.mcp_discovery_conditions_hint),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding, vertical = 8.dp),
            )
        }
        if (visibleTemplates.isEmpty()) {
            item(key = "connector-search-empty") {
                EtaPreferenceGroup {
                    ListEmptyState(
                        title = stringResource(R.string.mcp_discovery_empty_title),
                        summary = stringResource(R.string.mcp_discovery_empty_summary),
                        action = {
                            EtaTextButton(
                                text = stringResource(R.string.mcp_discovery_show_all),
                                onClick = { query = ""; category = null; resetScroll() },
                            )
                        },
                    )
                }
            }
        }
        ConnectorCatalog.Category.entries.forEach { group ->
            val templates = visibleTemplates.filter { it.category == group }
            if (templates.isNotEmpty()) {
                item(key = "connector-category-${group.name}") {
                    EtaPreferenceGroupTitle(connectorCategoryLabel(group))
                }
                templates.forEach { template ->
                    item(key = "connector-${template.id}") {
                        ConnectorServiceCard(
                            template = template,
                            enabled = enabled,
                            onConfigure = { onConfigure(template) },
                            onGuide = { onGuide(template) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun McpServerDetailScreen(
    serverId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    val server = servers.firstOrNull { it.id == serverId }
    var credentialConfigured by remember(serverId) { mutableStateOf(false) }
    LaunchedEffect(server) {
        credentialConfigured = withContext(Dispatchers.IO) {
            server?.let {
                runCatching { !McpServerRepository.bearerToken(it.id).isNullOrBlank() }.getOrDefault(false)
            } ?: false
        }
    }
    var working by remember { mutableStateOf(false) }
    var pendingRiskyTool by remember { mutableStateOf<McpToolDefinition?>(null) }
    var pendingBulk by remember { mutableStateOf<PendingBulk?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    // 刷新结果：留在页面上而不是只弹一次 toast，用户回头还能看到「少了什么」。
    var refreshNote by remember { mutableStateOf<String?>(null) }
    // 服务端返回空目录时的确认：先问，再决定是否覆盖既有工具与勾选。
    var pendingEmptyCatalog by remember { mutableStateOf<McpEmptyCatalogException?>(null) }

    MiuixScaffoldPage(
        title = server?.name ?: stringResource(R.string.route_mcp_server_detail),
        onBack = onBack,
        actions = {
            IconButton(
                enabled = server != null && !working,
                onClick = {
                    // server 为 null 时按钮本身已禁用；这里再判一次，避免捕获到空快照。
                    val before = server
                    if (before != null) scope.launch {
                        working = true
                        // 上一轮提示属于上一次握手，重新刷新时先清掉，避免旧结论误导。
                        refreshNote = null
                        val result = withContext(Dispatchers.IO) {
                            runCatching { McpServerManager.refresh(serverId) }
                        }
                        working = false
                        result.onSuccess { refreshed ->
                            val change = McpServerManager.discoveryChange(before, refreshed)
                            refreshNote = refreshNoteFor(refreshed, change)
                        }.onFailure { failure ->
                            val empty = failure as? McpEmptyCatalogException
                            if (empty != null) {
                                pendingEmptyCatalog = empty
                            } else {
                                refreshNote = "刷新失败，已保留原有 ${before.tools.size} 个工具与勾选：" +
                                    McpGatewayGuide.describeFailure(before.url, failure)
                            }
                        }
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.mcp_refresh_tools),
                )
            }
        },
    ) {
        if (server == null) {
            item(key = "missing") {
                ListEmptyState(title = stringResource(R.string.mcp_server_missing))
            }
            return@MiuixScaffoldPage
        }
        item(key = "server") {
            EtaPreferenceGroupTitle(stringResource(R.string.mcp_server_settings))
            EtaPreferenceGroup {
                EtaSwitchPreference(
                    title = stringResource(R.string.mcp_enable_server),
                    summary = server.url,
                    checked = server.enabled,
                    onCheckedChange = { enabled ->
                        scope.launch(Dispatchers.IO) {
                            McpServerRepository.update(server.copy(enabled = enabled))
                        }
                    },
                )

                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(
                    title = stringResource(R.string.mcp_update_token),
                    summary = stringResource(
                        when {
                            McpAuthorizationType.queryParameterName(server.authorizationType) != null -> {
                                if (credentialConfigured) R.string.mcp_discovery_key_configured
                                else R.string.mcp_discovery_key_missing
                            }
                            credentialConfigured -> R.string.mcp_token_configured
                            else -> R.string.mcp_no_authentication
                        },
                    ),
                    onClick = { showToken = true },
                )

                EtaPreferenceDivider(hasLeading = false)
                // AUTO 会静默回退 legacy：把当前实际生效的版本与来源摆出来，
                // 用户排查「工具怎么少了」时至少知道自己跑在哪个协议家族上。
                EtaPreference(
                    title = "协议版本",
                    summary = McpProtocolStatus.summary(
                        protocolMode = server.protocolMode,
                        lastProtocolVersion = server.lastProtocolVersion,
                    ),
                )
                if (McpProtocolStatus.isLegacyFallback(server.protocolMode, server.lastProtocolVersion)) {
                    EtaPreference(
                        title = "已回退到旧版协议",
                        summary = "现代协议握手未被接受，当前走旧版会话握手；" +
                            "部分新工具可能因此不可见，可尝试在服务端开启现代协议后重新刷新。",
                        summaryColor = BasicComponentColors(
                            color = MiuixTheme.colorScheme.primary,
                            disabledColor = MiuixTheme.colorScheme.disabledOnSurface,
                        ),
                    )
                }
            }
            refreshNote?.let { note ->
                // 放在卡片外：卡片里的 InlineAlert 会被 EtaPreferenceGroup 的内边距裁掉。
                Column(modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding)) {
                    InlineAlert(text = note, level = InlineAlertLevel.Info)
                }
            }
            // 地址指向官方 REST/Bot API 时，无论有没有工具都当场点破：
            // 这类地址永远连不上 MCP，光靠刷新解决不了，必须换成自建网关。
            McpGatewayGuide.hintForEndpoint(server.url)?.let { hint ->
                Column(modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding)) {
                    InlineAlert(text = hint, level = InlineAlertLevel.Warning)
                }
            }
        }
        item(key = "tool-actions") {
            val current = server
            if (current.tools.isNotEmpty()) {
                fun enable(names: Set<String>) {
                    val toAdd = names.filter { it !in current.enabledToolNames }.toSet()
                    if (toAdd.isEmpty()) return
                    val risky = current.tools.filter { it.name in toAdd && it.readOnlyHint != true }
                    if (risky.isEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            McpServerRepository.setToolsEnabled(serverId, toAdd, true)
                        }
                    } else {
                        pendingBulk = PendingBulk(toAdd, risky)
                    }
                }

                val readOnly = current.tools.filter { it.readOnlyHint == true }.map { it.name }.toSet()
                val writable = current.tools.map { it.name }.toSet() - readOnly
                EtaPreferenceGroupTitle("快捷勾选")
                Column(modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding)) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EtaTextButton(text = "全选只读（${readOnly.size}）", onClick = { enable(readOnly) })
                        EtaTextButton(
                            text = "清空",
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    McpServerRepository.setToolsEnabled(
                                        serverId,
                                        current.tools.map { it.name },
                                        false,
                                    )
                                }
                            },
                        )
                        if (isGitHubServer(current)) {
                            EtaTextButton(
                                text = "GitHub 常用预设",
                                onClick = { enable(githubPresetNames(current.tools)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        toolGroups(current.tools).forEach { group ->
                            EtaTextButton(
                                text = "${group.label}（${group.names.size}）",
                                onClick = { enable(group.names) },
                            )
                        }
                        if (writable.isNotEmpty()) {
                            EtaTextButton(text = "可写（${writable.size}）", onClick = { enable(writable) })
                        }
                    }
                }
            }
        }
        item(key = "tools") {
            EtaPreferenceGroupTitle(stringResource(R.string.mcp_tools_count, server.tools.size))
            EtaPreferenceGroup {
                if (server.tools.isEmpty()) {
                    // 「从没握手成功」与「握手成功但服务端没有工具」是两种完全不同的处境：
                    // 前者是地址/网关问题，后者是服务方没发布工具，混成一句话用户无从下手。
                    val neverHandshook = server.lastRefreshedAt == null
                    ListEmptyState(
                        title = stringResource(R.string.mcp_no_tools),
                        summary = if (neverHandshook) {
                            "还没有成功握手过：检查地址是否指向 MCP 端点（多数平台需要自建网关），" +
                                "确认后点右上角刷新"
                        } else {
                            stringResource(R.string.mcp_refresh_tools_hint)
                        },
                        icon = Icons.Rounded.Refresh,
                    )
                } else {
                    server.tools.forEachIndexed { index, tool ->
                        if (index > 0) EtaPreferenceDivider(hasLeading = false)
                        val checked = tool.name in server.enabledToolNames
                        EtaSwitchPreference(
                            title = tool.title.ifBlank { tool.name },
                            summary = toolSummary(tool),
                            checked = checked,
                            onCheckedChange = { enabled ->
                                if (enabled && tool.readOnlyHint != true) {
                                    pendingRiskyTool = tool
                                } else {
                                    scope.launch(Dispatchers.IO) {
                                        McpServerRepository.setToolEnabled(serverId, tool.name, enabled)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        item(key = "delete") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding).padding(top = 8.dp, bottom = EtaPreferenceDefaults.GroupSpacing)) {
                EtaPreference(
                    title = stringResource(R.string.mcp_delete_server),
                    titleColor = BasicComponentColors(
                        color = MiuixTheme.colorScheme.error,
                        disabledColor = MiuixTheme.colorScheme.disabledOnSurface,
                    ),
                    summary = stringResource(R.string.mcp_delete_server_summary),
                    onClick = { showDelete = true },
                )
            }
        }
    }

    pendingEmptyCatalog?.let { empty ->
        EtaWindowDialog(
            show = true,
            title = "服务端返回了 0 个工具",
            summary = "原有 ${empty.previousToolCount} 个工具与已勾选项都还在。" +
                "确认后才会用空列表覆盖；如果只是网关重启或发布中，先取消再刷新一次。",
            onDismissRequest = { pendingEmptyCatalog = null },
        ) {
            MiuixDialogActions(
                confirmText = "仍然覆盖为空列表",
                destructive = true,
                onCancel = { pendingEmptyCatalog = null },
                onConfirm = {
                    scope.launch {
                        working = true
                        // 落库的就是刚才被拒绝的那一份空目录，不再重新握手。
                        val result = withContext(Dispatchers.IO) {
                            runCatching { McpServerManager.acceptEmptyCatalog(empty) }
                        }
                        working = false
                        result.onSuccess {
                            refreshNote = "已按服务端返回覆盖为空列表，工具勾选已清空"
                        }.onFailure {
                            refreshNote = "覆盖失败，原有 ${empty.previousToolCount} 个工具与勾选仍然保留：" +
                                (it.message ?: "未知原因")
                        }
                    }
                    pendingEmptyCatalog = null
                },
            )
        }
    }

    pendingRiskyTool?.let { tool ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.mcp_enable_risky_tool),
            summary = stringResource(R.string.mcp_enable_risky_tool_summary, tool.name, server?.name.orEmpty()),
            onDismissRequest = { pendingRiskyTool = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.mcp_enable_tool),
                destructive = true,
                onCancel = { pendingRiskyTool = null },
                onConfirm = {
                    scope.launch(Dispatchers.IO) {
                        McpServerRepository.setToolEnabled(serverId, tool.name, true)
                    }
                    pendingRiskyTool = null
                },
            )
        }
    }

    pendingBulk?.let { bulk ->
        EtaWindowDialog(
            show = true,
            title = "开启 ${bulk.names.size} 个工具",
            summary = buildString {
                append("其中 ${bulk.risky.size} 个可以修改数据：")
                append(bulk.risky.take(6).joinToString("、") { it.name })
                if (bulk.risky.size > 6) append(" 等")
            },
            onDismissRequest = { pendingBulk = null },
        ) {
            MiuixDialogActions(
                confirmText = "确认开启",
                destructive = true,
                onCancel = { pendingBulk = null },
                onConfirm = {
                    scope.launch(Dispatchers.IO) {
                        McpServerRepository.setToolsEnabled(serverId, bulk.names, true)
                    }
                    pendingBulk = null
                },
            )
        }
    }

    EtaWindowDialog(
        show = showToken,
        title = stringResource(R.string.mcp_update_token),
        summary = stringResource(R.string.mcp_discovery_credential_update_summary),
        onDismissRequest = { if (!working) showToken = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = token,
                onValueChange = { token = it },
                label = stringResource(
                    if (server?.let { McpAuthorizationType.queryParameterName(it.authorizationType) } != null) {
                        R.string.mcp_discovery_api_key
                    } else {
                        R.string.mcp_discovery_access_token
                    },
                ),
                singleLine = true,
                enabled = !working,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            server?.let { McpAuthorizationType.queryParameterName(it.authorizationType) }?.let { parameter ->
                Text(
                    text = stringResource(R.string.mcp_discovery_query_key_hint, parameter),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_save),
                confirmEnabled = !working,
                onCancel = { if (!working) showToken = false },
                onConfirm = {
                    server?.let { current ->
                        val update = McpCredentialUpdate.from(current.authorizationType, token)
                        scope.launch {
                            working = true
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    McpServerRepository.update(
                                        current.copy(authorizationType = update.authorizationType),
                                        bearerToken = update.value,
                                    )
                                }
                            }
                            working = false
                            result.onSuccess {
                                credentialConfigured = update.value.isNotBlank()
                                token = ""
                                showToken = false
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    R.string.mcp_discovery_credential_save_failed,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                },
            )
        }
    }

    EtaWindowDialog(
        show = showDelete,
        title = stringResource(R.string.mcp_delete_server),
        summary = stringResource(R.string.mcp_delete_confirm, server?.name.orEmpty()),
        onDismissRequest = { showDelete = false },
    ) {
        MiuixDialogActions(
            confirmText = stringResource(R.string.ui_delete_3755f5),
            destructive = true,
            onCancel = { showDelete = false },
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { McpServerRepository.delete(serverId) }
                    onBack()
                }
            },
        )
    }
}

@Composable
private fun toolSummary(tool: McpToolDefinition): String = when {
    tool.readOnlyHint == true -> tool.description.ifBlank { stringResource(R.string.mcp_read_only_tool) }
    else -> tool.description.ifBlank { stringResource(R.string.mcp_may_modify_data) }
}

/** 批量勾选的中转状态：先确认，再一次性写入。 */
private data class PendingBulk(val names: Set<String>, val risky: List<McpToolDefinition>)

/**
 * 刷新后给用户看的一句话。
 *
 * 工具数骤降（尤其降到 0）时如果只弹「已刷新 · N 个」，用户看到的是勾选莫名消失。
 * 这里把「服务端移除了哪些、哪几个勾选因此被取消」写清楚，让状态变化可追溯。
 */
private fun refreshNoteFor(
    refreshed: McpServerSetting,
    change: McpDiscoveryChange,
): String {
    if (change.isEmpty) {
        return "已刷新 · ${refreshed.tools.size} 个工具，目录没有变化"
    }
    return buildString {
        append("已刷新 · ${refreshed.tools.size} 个工具")
        if (change.added.isNotEmpty()) {
            append("；新增 ${change.added.size} 个：")
            append(change.added.take(6).joinToString("、"))
            if (change.added.size > 6) append(" 等")
        }
        if (change.removed.isNotEmpty()) {
            append("；服务端已移除 ${change.removed.size} 个：")
            append(change.removed.take(6).joinToString("、"))
            if (change.removed.size > 6) append(" 等")
        }
        if (change.disabledSelections.isNotEmpty()) {
            append("；其中 ${change.disabledSelections.size} 个原本已启用，勾选已同步取消")
        }
    }
}

/** 仅用于国内 / 全部范围切换；服务选择统一放在发现卡片。 */
@Composable
private fun ConnectorFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue = if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = tween(durationMillis = EtaDesign.Motion.FAST_MS),
        label = "connector_filter_container",
    )
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
        modifier = Modifier
            .semantics { this.selected = selected }
            .heightIn(min = 48.dp)
            .squircleBorder(
                width = 1.dp,
                color = if (selected) Color.Transparent else MiuixTheme.colorScheme.dividerLine,
                cornerRadius = 16.dp,
            )
            .squircleSurface(color = container, cornerRadius = 16.dp)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun ConnectorServiceCard(
    template: ConnectorCatalog.Template,
    enabled: Boolean,
    onConfigure: () -> Unit,
    onGuide: () -> Unit,
) {
    EtaCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EtaPreferenceDefaults.SidePadding, vertical = 4.dp),
        onClick = if (enabled) onConfigure else null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConnectorBrandIcon(template.badge, template.brandColor, enabled)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = template.name,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = connectorPurpose(template.category),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Text(
                text = template.hint,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = connectorSetupLabel(template),
                style = MiuixTheme.textStyles.footnote1,
                color = if (template.requiresGateway) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.primary
                },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EtaTextButton(
                    text = stringResource(R.string.mcp_discovery_configure),
                    onClick = onConfigure,
                    enabled = enabled,
                )
                if (template.guideUrl.isNotBlank()) {
                    EtaTextButton(
                        text = stringResource(R.string.mcp_discovery_guide),
                        onClick = onGuide,
                    )
                }
            }
        }
    }
}

@Composable
private fun connectorCategoryLabel(category: ConnectorCatalog.Category): String = stringResource(
    when (category) {
        ConnectorCatalog.Category.SEARCH -> R.string.mcp_discovery_category_search
        ConnectorCatalog.Category.MAPS -> R.string.mcp_discovery_category_maps
        ConnectorCatalog.Category.PRODUCTIVITY -> R.string.mcp_discovery_category_productivity
        ConnectorCatalog.Category.CODE -> R.string.mcp_discovery_category_code
        ConnectorCatalog.Category.DATA -> R.string.mcp_discovery_category_data
        ConnectorCatalog.Category.COMMUNICATION -> R.string.mcp_discovery_category_communication
        ConnectorCatalog.Category.IM -> R.string.mcp_discovery_category_im
        ConnectorCatalog.Category.DOCS -> R.string.mcp_discovery_category_docs
        ConnectorCatalog.Category.AUTOMATION -> R.string.mcp_discovery_category_automation
    },
)

@Composable
private fun connectorPurpose(category: ConnectorCatalog.Category): String = stringResource(
    when (category) {
        ConnectorCatalog.Category.SEARCH -> R.string.mcp_discovery_purpose_search
        ConnectorCatalog.Category.MAPS -> R.string.mcp_discovery_purpose_maps
        ConnectorCatalog.Category.PRODUCTIVITY -> R.string.mcp_discovery_purpose_productivity
        ConnectorCatalog.Category.CODE -> R.string.mcp_discovery_purpose_code
        ConnectorCatalog.Category.DATA -> R.string.mcp_discovery_purpose_data
        ConnectorCatalog.Category.COMMUNICATION, ConnectorCatalog.Category.IM -> R.string.mcp_discovery_purpose_communication
        ConnectorCatalog.Category.DOCS -> R.string.mcp_discovery_purpose_docs
        ConnectorCatalog.Category.AUTOMATION -> R.string.mcp_discovery_purpose_automation
    },
)

/** 与目录的 setupLabel 同一条件顺序，文案交给资源保持三语言同步。 */
@Composable
private fun connectorSetupLabel(template: ConnectorCatalog.Template): String = stringResource(
    when {
        template.noConnectorNeeded -> R.string.mcp_discovery_setup_local
        template.requiresGateway -> R.string.mcp_discovery_setup_gateway
        template.endpoint.isBlank() -> R.string.mcp_discovery_setup_personal_url
        template.authorization == McpAuthorizationType.NONE -> R.string.mcp_discovery_setup_url
        else -> R.string.mcp_discovery_setup_key
    },
)

/**
 * 连接器品牌图标：品牌色圆角方块 + 白色短标识。
 *
 * 尺寸固定 20dp 让整排胶囊图标对齐；标识 1~2 字符用 9sp、3 字符用 7sp，
 * 保证「PDF」「XL」这类三字母标识不溢出方块。禁用时整体降低不透明度。
 */
@Composable
private fun ConnectorBrandIcon(
    badge: String,
    brandColor: Long,
    enabled: Boolean,
) {
    val label = badge.ifBlank { "?" }.take(3)
    val brand = Color(brandColor).let { if (enabled) it else it.copy(alpha = 0.45f) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .squircleSurface(color = brand, cornerRadius = 6.dp)
            .size(EtaDesign.Size.IconSm),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2.copy(
                fontSize = if (label.length >= 3) 7.sp else 9.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            ),
            color = Color.White,
            maxLines = 1,
        )
    }
}

private data class ToolGroupSpec(val label: String, val names: Set<String>)

/** 按名字前缀把工具归类，给「一键勾选」用。 */
private fun toolGroups(tools: List<McpToolDefinition>): List<ToolGroupSpec> {
    fun match(vararg keys: String) = tools
        .filter { tool -> keys.any { tool.name.contains(it) } }
        .map { it.name }
        .toSet()
    return listOf(
        ToolGroupSpec("Issue", match("issue")),
        ToolGroupSpec("PR", match("pull_request")),
        ToolGroupSpec("仓库与文件", match("repo", "file", "branch", "commit", "gist")),
        ToolGroupSpec("Actions", match("workflow", "actions", "check")),
        ToolGroupSpec("搜索", match("search")),
    ).filter { it.names.isNotEmpty() }
}

/** GitHub 预设里额外开启的写操作；删除类一律不碰。 */
private val GITHUB_PRESET_WRITES = setOf(
    "issue_write",
    "add_issue_comment",
    "create_pull_request",
    "merge_pull_request",
    "create_branch",
    "create_or_update_file",
    "push_files",
    "create_repository",
)

private fun githubPresetNames(tools: List<McpToolDefinition>): Set<String> = tools
    .filter { it.readOnlyHint == true || it.name in GITHUB_PRESET_WRITES }
    .map { it.name }
    .toSet()

private fun isGitHubServer(server: McpServerSetting): Boolean =
    server.url.contains("githubcopilot", ignoreCase = true)
