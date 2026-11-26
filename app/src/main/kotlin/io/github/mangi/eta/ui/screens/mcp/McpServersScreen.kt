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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
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

@Composable
internal fun McpServersScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedConnectorId by remember { mutableStateOf<String?>(null) }
    var connectorHint by remember { mutableStateOf<String?>(null) }
    var connectorCredentialHint by remember { mutableStateOf<String?>(null) }
    // 选中的模板是不是「服务方只给 REST/Bot API，必须自建网关」：决定提示语气与错误解读。
    var connectorRequiresGateway by remember { mutableStateOf(false) }
    // 本地文件类模板：连上去没有任何收益，界面劝阻而不是让用户白填一次地址。
    var connectorNoConnectorNeeded by remember { mutableStateOf(false) }

    MiuixScaffoldPage(
        title = stringResource(R.string.route_mcp_servers),
        onBack = onBack,
        actions = {
            IconButton(onClick = { showAdd = true }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.mcp_add_server),
                )
            }
        },
    ) {
        item(key = "servers") {
            EtaPreferenceGroupTitle(stringResource(R.string.mcp_configured_servers, servers.size))
            EtaPreferenceGroup {
                if (servers.isEmpty()) {
                    ListEmptyState(
                        title = stringResource(R.string.mcp_empty_title),
                        summary = stringResource(R.string.mcp_empty_summary),
                        icon = Icons.Rounded.Hub,
                        action = {
                            EtaTextButton(
                                text = stringResource(R.string.mcp_add_server),
                                onClick = { showAdd = true },
                            )
                        },
                    )
                } else {
                    servers.forEachIndexed { index, server ->
                        if (index > 0) EtaPreferenceDivider(hasLeading = false)
                        EtaArrowPreference(
                            title = server.name,
                            // 「已启用 0/0」看不出是没握手还是服务端没工具，列表行就要说清：
                            // 空目录的连接确实已经保存了，只是没有可用工具。
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

    EtaWindowDialog(
        show = showAdd,
        title = stringResource(R.string.mcp_add_server),
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
                Text(
                    text = "从生态连接器开始",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                // 按分类分组：一长排 Chip 看不出归属，分组后「效率 / 代码 / 数据 / 沟通 / 自动化」各自成段。
                ConnectorCatalog.groupedInOrder().forEach { (category, templates) ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = category.label,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            templates.forEach { template ->
                                ConnectorChip(
                                    badge = template.badge,
                                    brandColor = template.brandColor,
                                    text = template.name,
                                    selected = selectedConnectorId == template.id,
                                    enabled = !working,
                                    onClick = {
                                        selectedConnectorId = template.id
                                        name = template.name
                                        url = template.endpoint
                                        connectorHint = template.hint
                                        connectorCredentialHint = template.credentialHint
                                            .takeIf { it.isNotBlank() }
                                        connectorRequiresGateway = template.requiresGateway
                                        connectorNoConnectorNeeded = template.noConnectorNeeded
                                        // 换模板等于换目标：上一条错误属于旧地址，留着只会误导。
                                        error = null
                                    },
                                )
                            }
                        }
                    }
                }
                // 端点为空的模板不会自动带出地址：明确告诉用户这里要自己填，而不是留个空输入框。
                // 需要网关的模板把「为什么必须自建」讲在前面，用户才不会去粘官方 API 地址。
                if (selectedConnectorId?.let { ConnectorCatalog.byId(it) }?.endpoint?.isEmpty() == true) {
                    InlineAlert(
                        text = if (connectorRequiresGateway) {
                            "该服务需要你填写自建网关的地址：官方只提供 REST/Bot API，没有 MCP 端点，直接粘官方 API 地址一定连不上"
                        } else {
                            "该服务需要你填写实例地址"
                        },
                        level = InlineAlertLevel.Warning,
                    )
                }
                if (connectorNoConnectorNeeded) {
                    InlineAlert(
                        text = "本地文件不需要连接器：交给 Agent 的文件工具即可，这里只是入口占位",
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
                    onValueChange = { name = it },
                    label = stringResource(R.string.mcp_server_name),
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = url,
                    onValueChange = { url = it },
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
                    onValueChange = { token = it },
                    label = stringResource(R.string.mcp_bearer_optional),
                    singleLine = true,
                    enabled = !working,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
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
                                val draft = McpServerSetting(
                                    id = "",
                                    name = normalizedName,
                                    url = normalizedUrl,
                                    protocolMode = McpProtocolMode.AUTO,
                                    authorizationType = if (token.isBlank()) {
                                        McpAuthorizationType.NONE
                                    } else {
                                        McpAuthorizationType.BEARER
                                    },
                                )
                                val discovered = McpServerManager.discover(draft, token)
                                McpServerRepository.add(discovered, token)
                            }
                        }
                        working = false
                        result.onSuccess { saved ->
                            showAdd = false
                            name = ""
                            url = ""
                            token = ""
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

@Composable
internal fun McpServerDetailScreen(
    serverId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    val server = servers.firstOrNull { it.id == serverId }
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
                        if (server.authorizationType == McpAuthorizationType.BEARER) {
                            R.string.mcp_token_configured
                        } else {
                            R.string.mcp_no_authentication
                        }
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
        summary = stringResource(R.string.mcp_update_token_summary),
        onDismissRequest = { showToken = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = token,
                onValueChange = { token = it },
                label = stringResource(R.string.mcp_bearer_token),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_save),
                onCancel = { showToken = false },
                onConfirm = {
                    server?.let { current ->
                        scope.launch(Dispatchers.IO) {
                            McpServerRepository.update(
                                current.copy(
                                    authorizationType = if (token.isBlank()) {
                                        McpAuthorizationType.NONE
                                    } else {
                                        McpAuthorizationType.BEARER
                                    },
                                ),
                                bearerToken = token,
                            )
                        }
                        token = ""
                        showToken = false
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

/**
 * 带品牌图标的连接器胶囊。
 *
 * 与通用选择胶囊保持同一套视觉（描边 + 选中浅底 + 同一圆角与内边距），
 * 左侧换成「品牌色圆角方块 + 白色短标识」的组合图标：纯 Compose 绘制，
 * 不引入图片资源，每个服务靠品牌色 + 1~3 字符标识即可一眼区分。
 */
@Composable
private fun ConnectorChip(
    badge: String,
    brandColor: Long,
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue = if (selected) {
            MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = EtaDesign.Motion.FAST_MS),
        label = "connector_chip_container",
    )
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
            selected -> MiuixTheme.colorScheme.primary
            else -> MiuixTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = EtaDesign.Motion.FAST_MS),
        label = "connector_chip_content",
    )
    val border by animateColorAsState(
        targetValue = if (selected) Color.Transparent else MiuixTheme.colorScheme.dividerLine,
        animationSpec = tween(durationMillis = EtaDesign.Motion.FAST_MS),
        label = "connector_chip_border",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .semantics { this.selected = selected }
            .squircleBorder(width = 1.dp, color = border, cornerRadius = 16.dp)
            .squircleSurface(color = container, cornerRadius = 16.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        ConnectorBrandIcon(
            badge = badge,
            brandColor = brandColor,
            enabled = enabled,
        )
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = content,
            maxLines = 1,
        )
    }
}

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
