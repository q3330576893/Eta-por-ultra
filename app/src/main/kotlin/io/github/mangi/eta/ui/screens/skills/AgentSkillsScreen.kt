package io.github.mangi.eta.ui.screens.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import io.github.mangi.eta.ui.model.SKILL_GITHUB_CANDIDATE_FILTER_THRESHOLD
import io.github.mangi.eta.ui.model.SkillGithubUiState
import io.github.mangi.eta.ui.model.filterSkillGithubCandidates
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaDesign
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.data.model.PluginCatalog
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.AgentSkillsUiState
import io.github.mangi.eta.ui.model.SkillFeedbackAction
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.SkillUpdateUi
import io.github.mangi.eta.ui.model.canCheckForUpdate
import io.github.mangi.eta.ui.model.canDeleteUserSkill
import io.github.mangi.eta.ui.model.updateBadgeFor
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val CardHorizontalPadding = 16.dp
private val CardBottomPadding = 16.dp

@Composable
fun AgentSkillsScreen(
    state: AgentSkillsUiState,
    onAction: (AgentSkillsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var deleteTarget by remember { mutableStateOf<SkillItemUi?>(null) }
    val operationPending = state.isImporting || state.busySkillId != null
    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onAction(AgentSkillsAction.ImportZip(uri.toString()))
    }
    val openZipPicker = {
        zipPicker.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
            ),
        )
    }

    // 评分只用于本地展示排序，绝不改动启用状态或安装状态。
    fun SkillItemUi.sortRating(): Double = state.ratings[this.id]?.averageRating ?: -1.0
    val installed = state.skills.filter { it.installed }
    val builtinInstalled = installed.filter { it.source == "builtin" }.sortedByDescending { it.sortRating() }
    val userInstalled = installed.filter { it.canDeleteUserSkill }.sortedByDescending { it.sortRating() }
    val removed = state.skills.filter { !it.installed }
    val submitRating: (String, Int, String) -> Unit = { skillId, stars, comment ->
        state.onSubmitFeedback?.invoke(SkillFeedbackAction(skillId, stars, comment))
    }

    // 「查看已安装」的落点：按下面 LazyColumn 的固定发射顺序算出内置/用户技能区的下标。
    // 顺序本身没有动态增删，所以这里用纯算术，不引入额外的滚动状态耦合。
    var sectionCursor = 2
    if (state.github != null) sectionCursor += 2
    val zipSectionIndex = sectionCursor
    sectionCursor += 2
    val builtinSectionIndex = if (builtinInstalled.isNotEmpty()) sectionCursor else -1
    if (builtinSectionIndex >= 0) sectionCursor += 2
    val userSectionIndex = if (userInstalled.isNotEmpty()) sectionCursor else -1
    val installedSectionIndex = when {
        builtinSectionIndex >= 0 -> builtinSectionIndex
        userSectionIndex >= 0 -> userSectionIndex
        else -> zipSectionIndex
    }

    val listState = rememberLazyListState()
    var revealInstalledRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(revealInstalledRequest) {
        if (revealInstalledRequest > 0) listState.animateScrollToItem(installedSectionIndex)
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.route_skills),
        onBack = { onAction(AgentSkillsAction.NavigateBack) },
        modifier = modifier,
        listState = listState,
    ) {
        item(key = "plugin-source-title") { EtaPreferenceGroupTitle("插件来源") }
        item(key = "plugin-source-card") {
            // 官方目录与社区精选分开列：来源可信度不同，先让人看清「这批 Skill 是谁维护的」。
            val sourceSections = PluginCatalog.Kind.entries.mapNotNull { kind ->
                PluginCatalog.grouped()[kind]?.takeIf { it.isNotEmpty() }?.let { kind to it }
            }
            EtaPreferenceGroup(
                modifier = Modifier
                    .padding(horizontal = CardHorizontalPadding)
                    .padding(bottom = CardBottomPadding),
            ) {
                Text(
                    text = "点任意来源直接进入安装面板：先列出仓库里可用的 Skill，勾选后固定 commit 下载并校验，再写入本地。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(
                        horizontal = EtaPreferenceDefaults.SidePadding,
                        vertical = EtaDesign.Space.MD,
                    ),
                )
                sourceSections.forEach { (kind, sources) ->
                    EtaPreferenceDivider(hasLeading = false)
                    Text(
                        text = kind.label,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(
                            start = EtaPreferenceDefaults.SidePadding,
                            end = EtaPreferenceDefaults.SidePadding,
                            top = EtaDesign.Space.MD,
                            bottom = EtaDesign.Space.XS,
                        ),
                    )
                    sources.forEachIndexed { index, source ->
                        if (index > 0) EtaPreferenceDivider()
                        val installable = source.id == "custom-repo" || source.repository.isNotBlank()
                        EtaPreference(
                            title = source.name,
                            summary = if (source.repository.isNotBlank()) {
                                source.summary + "\n" + source.repository
                            } else {
                                source.summary
                            },
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = when (kind) {
                                        PluginCatalog.Kind.OFFICIAL -> Icons.Rounded.VerifiedUser
                                        PluginCatalog.Kind.COMMUNITY -> Icons.Rounded.Groups
                                        PluginCatalog.Kind.TOOLCHAIN -> Icons.Rounded.Extension
                                    },
                                    tint = when (kind) {
                                        PluginCatalog.Kind.OFFICIAL -> EtaPreferenceColors.Blue
                                        PluginCatalog.Kind.COMMUNITY -> EtaPreferenceColors.Green
                                        PluginCatalog.Kind.TOOLCHAIN -> EtaPreferenceColors.Orange
                                    },
                                )
                            },
                            onClick = if (installable) {
                                {
                                    onAction(
                                        AgentSkillsAction.OpenGithubSource(source.repository),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }

        state.github?.let { github ->
            item(key = "github-panel-title") { EtaPreferenceGroupTitle("从 GitHub 安装") }
            item(key = "github-panel") {
                GithubInstallPanel(
                    github = github,
                    onAction = onAction,
                    onRevealInstalled = { revealInstalledRequest++ },
                )
            }
        }

        item(key = "zip-import-title") { EtaPreferenceGroupTitle(stringResource(R.string.ui_install_087db6)) }
        item(key = "zip-import-card") {
            EtaPreferenceGroup(
                modifier = Modifier
                    .padding(horizontal = CardHorizontalPadding)
                    .padding(bottom = CardBottomPadding),
            ) {
                EtaPreference(
                    title = if (state.isImporting) stringResource(R.string.skills_checking_package) else stringResource(R.string.skills_import_zip),
                    summary = if (state.isImporting) {
                        stringResource(R.string.skills_installing_package)
                    } else {
                        stringResource(R.string.skills_choose_package)
                    },
                    startAction = {
                        if (state.isImporting) {
                            // 加载态与图标同宽，切换时标题不横向跳动。
                            Box(
                                modifier = Modifier.size(EtaPreferenceDefaults.IconSize),
                                contentAlignment = Alignment.Center,
                            ) {
                                InfiniteProgressIndicator(size = 20.dp)
                            }
                        } else {
                            EtaPreferenceIcon(Icons.Rounded.FolderZip, enabled = !operationPending, tint = EtaPreferenceColors.Orange)
                        }
                    },
                    enabled = !operationPending,
                    onClick = openZipPicker,
                    onClickLabel = stringResource(R.string.skills_choose_zip),
                )
            }
        }

        if (builtinInstalled.isNotEmpty()) {
            item(key = "builtin-title") { EtaPreferenceGroupTitle(stringResource(R.string.ui_built_in_skills_1ceedf)) }
            item(key = "builtin-card") {
                EtaPreferenceGroup(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    builtinInstalled.forEachIndexed { index, skill ->
                        if (index > 0) EtaPreferenceDivider()
                        SkillSwitchRow(
                            skill = skill,
                            enabled = !operationPending,
                            onToggle = { enabled ->
                                onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                            },
                            rating = state.ratings[skill.id],
                            onRate = { stars, comment -> submitRating(skill.id, stars, comment) },
                        )
                    }
                }
            }
        }

        if (userInstalled.isNotEmpty()) {
            item(key = "user-title") { EtaPreferenceGroupTitle(stringResource(R.string.ui_user_skills_748e7f)) }
            item(key = "user-card") {
                val updatable = userInstalled.filter { it.canCheckForUpdate }
                val pendingUpdates = updatable.count { state.updateBadgeFor(it.id) != null }
                val checking = state.checkingUpdateIds.isNotEmpty()
                EtaPreferenceGroup(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    // 检查更新入口只在「至少有一个 Skill 带来源记录」时出现：
                    // 全是手工装的 Skill 时它只会带来一次必然失败的点击。
                    if (updatable.isNotEmpty()) {
                        EtaPreference(
                            title = if (checking) "正在检查更新…" else "检查更新",
                            summary = when {
                                checking -> "正在向来源仓库查询最新 commit，请稍候"
                                pendingUpdates > 0 -> "$pendingUpdates 个 Skill 有新版本，可在下方直接更新"
                                else -> "可检查 ${updatable.size} 个从 GitHub 安装的 Skill"
                            },
                            startAction = {
                                Box(
                                    modifier = Modifier.size(EtaPreferenceDefaults.IconSize),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (checking) {
                                        InfiniteProgressIndicator(size = 20.dp)
                                    } else {
                                        EtaPreferenceIcon(
                                            Icons.Rounded.Refresh,
                                            enabled = !operationPending && !checking,
                                            tint = EtaPreferenceColors.Blue,
                                        )
                                    }
                                }
                            },
                            enabled = !operationPending && !checking && state.updatingSkillId == null,
                            onClick = { onAction(AgentSkillsAction.CheckUpdates) },
                            onClickLabel = "检查所有已安装 Skill 的更新",
                        )
                        EtaPreferenceDivider()
                    }
                    userInstalled.forEachIndexed { index, skill ->
                        if (index > 0) EtaPreferenceDivider()
                        val update = state.updateBadgeFor(skill.id)
                        Column {
                            SkillSwitchRow(
                                skill = skill,
                                enabled = !operationPending,
                                onToggle = { enabled ->
                                    onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                                },
                                onDelete = { deleteTarget = skill },
                                rating = state.ratings[skill.id],
                                onRate = { stars, comment -> submitRating(skill.id, stars, comment) },
                            )
                            if (update != null) {
                                SkillUpdateRow(
                                    update = update,
                                    updating = state.updatingSkillId == skill.id,
                                    enabled = !operationPending && state.updatingSkillId == null,
                                    onCheck = { onAction(AgentSkillsAction.CheckUpdate(skill.id)) },
                                    onUpdate = { onAction(AgentSkillsAction.UpdateSkill(skill.id)) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (removed.isNotEmpty()) {
            item(key = "removed-title") { EtaPreferenceGroupTitle(stringResource(R.string.ui_removed_4e5c49)) }
            item(key = "removed-card") {
                EtaPreferenceGroup(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    removed.forEachIndexed { index, skill ->
                        if (index > 0) EtaPreferenceDivider()
                        EtaPreference(
                            title = skill.name,
                            summary = stringResource(R.string.ui_click_to_reinstall_dc60de),
                            startAction = { EtaPreferenceIcon(iconForSkill(skill.id), enabled = !operationPending, tint = EtaPreferenceColors.Green) },
                            enabled = !operationPending,
                            onClick = {
                                onAction(AgentSkillsAction.ReinstallBuiltin(skill.id))
                            },
                        )
                    }
                }
            }
        }

        if (state.skills.isEmpty() && state.isLoading) {
            item(key = "loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator(size = 24.dp)
                }
            }
        }
        if (state.skills.isEmpty() && !state.isLoading) {
            item(key = "empty") {
                ListEmptyState(
                    title = stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                    summary = stringResource(R.string.skills_choose_package),
                    icon = Icons.Rounded.FolderZip,
                    action = {
                        EtaTextButton(
                            text = stringResource(R.string.skills_import_zip),
                            enabled = !operationPending,
                            onClick = openZipPicker,
                        )
                    },
                )
            }
        }
    }

    state.replacement?.let { replacement ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.ui_replace_user_skills_250e98),
            summary = stringResource(R.string.skills_replace_summary, replacement.name, replacement.id),
            onDismissRequest = { onAction(AgentSkillsAction.CancelZipReplacement) },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.skills_replace),
                confirmEnabled = !operationPending,
                onCancel = { onAction(AgentSkillsAction.CancelZipReplacement) },
                onConfirm = { onAction(AgentSkillsAction.ConfirmZipReplacement) },
            )
        }
    }

    deleteTarget?.let { skill ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.ui_delete_user_skills_f319a9),
            summary = stringResource(R.string.skills_delete_summary, skill.name),
            onDismissRequest = { deleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.ui_delete_3755f5),
                destructive = true,
                confirmEnabled = !operationPending,
                onCancel = { deleteTarget = null },
                onConfirm = {
                    deleteTarget = null
                    onAction(AgentSkillsAction.DeleteSkill(skill.id))
                },
            )
        }
    }

    state.notice?.let { notice ->
        EtaWindowDialog(
            show = true,
            title = notice.title,
            summary = notice.message,
            onDismissRequest = { onAction(AgentSkillsAction.DismissNotice) },
        ) {
            EtaTextButton(
                text = stringResource(R.string.ui_knew_cb63c6),
                onClick = { onAction(AgentSkillsAction.DismissNotice) },
                modifier = Modifier.fillMaxWidth(),
                colors = if (notice.isError) {
                    ButtonDefaults.textButtonColors()
                } else {
                    ButtonDefaults.textButtonColorsPrimary()
                },
            )
        }
    }
}

/**
 * 「有更新」标记 + 更新按钮。
 *
 * 只在真的查到更新时出现（见 [updateBadgeFor]），因此不会给「已是最新」的 Skill
 * 加一行噪音；[update] 里的 commit 是安装时与最新两端的短 SHA，便于核对。
 */
@Composable
private fun SkillUpdateRow(
    update: SkillUpdateUi,
    updating: Boolean,
    enabled: Boolean,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = EtaPreferenceDefaults.SidePadding + 8.dp,
                end = EtaPreferenceDefaults.SidePadding,
                bottom = EtaDesign.Space.SM,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EtaDesign.Space.SM),
    ) {
        Text(
            text = "有更新 ${update.installedCommit} → ${update.latestCommit.orEmpty()}",
            style = MiuixTheme.textStyles.footnote2,
            color = EtaPreferenceColors.Orange,
            modifier = Modifier.weight(1f),
        )
        EtaTextButton(
            text = if (updating) "正在更新…" else "更新",
            enabled = enabled && !updating,
            onClick = onUpdate,
        )
        EtaTextButton(
            text = "复查",
            enabled = enabled && !updating,
            onClick = onCheck,
        )
    }
}

/**
 * GitHub 安装面板。
 *
 * 五个体验点都收在这里，避免把「正在下载 / 装到哪 / 冲突怎么办 / 候选太多 / 上次仓库」
 * 散落到页面其它分区：
 * 1. 列出/安装期间给 InfiniteProgressIndicator，并禁用会改变状态的按钮；
 * 2. 成功后显示落盘路径，并提供「查看已安装」滚动到内置/用户技能区；
 * 3. 冲突文案由 Store 翻译成「先删除再重试」，这里只负责展示；
 * 4. 候选超过阈值时出现过滤输入框（name/path 子串，忽略大小写）；
 * 5. 上次仓库由 Store 从 SharedPreferences 预填，这里不做 IO。
 */
@Composable
private fun GithubInstallPanel(
    github: SkillGithubUiState,
    onAction: (AgentSkillsAction) -> Unit,
    onRevealInstalled: () -> Unit,
) {
    val inspecting = github.phase == SkillGithubUiState.Phase.Inspecting
    val installing = github.phase == SkillGithubUiState.Phase.Installing
    val busy = inspecting || installing
    var filterQuery by remember { mutableStateOf("") }
    val showFilter = github.candidates.size > SKILL_GITHUB_CANDIDATE_FILTER_THRESHOLD
    val visibleCandidates = remember(github.candidates, filterQuery) {
        filterSkillGithubCandidates(github.candidates, filterQuery)
    }

    EtaPreferenceGroup(
        modifier = Modifier
            .padding(horizontal = CardHorizontalPadding)
            .padding(bottom = CardBottomPadding),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = EtaPreferenceDefaults.SidePadding,
                vertical = EtaDesign.Space.MD,
            ),
            verticalArrangement = Arrangement.spacedBy(EtaDesign.Space.SM),
        ) {
            TextField(
                value = github.repository,
                onValueChange = { onAction(AgentSkillsAction.GithubRepositoryChanged(it)) },
                label = "仓库，例如 openai/skills",
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(EtaDesign.Space.SM)) {
                EtaTextButton(
                    text = if (inspecting) "正在列出…" else "列出可用 Skill",
                    enabled = !busy,
                    onClick = { onAction(AgentSkillsAction.GithubInspect) },
                )
                EtaTextButton(
                    text = if (installing) "正在安装…" else "安装已选（${github.selectedPaths.size}）",
                    enabled = github.candidates.isNotEmpty() && !busy,
                    onClick = { onAction(AgentSkillsAction.GithubInstall) },
                )
                EtaTextButton(
                    text = "关闭",
                    enabled = !installing,
                    onClick = { onAction(AgentSkillsAction.CloseGithub) },
                )
            }
            if (busy) {
                // 大仓库的列出与安装都要好几秒，没有进度感会被当成卡死。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(EtaDesign.Space.SM),
                ) {
                    InfiniteProgressIndicator(size = 20.dp)
                    Text(
                        text = if (installing) {
                            "正在下载并安装已选 Skill，请勿关闭面板…"
                        } else {
                            "正在读取仓库目录并列出 Skill…"
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            github.message?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            github.installTargetPath?.takeIf { github.phase == SkillGithubUiState.Phase.Installed }?.let { path ->
                Text(
                    text = "已安装到 $path",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(EtaDesign.Space.SM)) {
                    EtaTextButton(
                        text = "查看已安装",
                        enabled = !busy,
                        onClick = onRevealInstalled,
                    )
                }
                Text(
                    text = "在下方「内置技能 / 用户技能」里打开开关即可启用；GitHub 安装的 Skill 会出现在用户技能区。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            github.error?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.error,
                )
            }
            if (showFilter) {
                TextField(
                    value = filterQuery,
                    onValueChange = { filterQuery = it },
                    label = "过滤候选（名称或路径）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (filterQuery.isBlank()) {
                        "共 ${github.candidates.size} 个候选"
                    } else {
                        "匹配 ${visibleCandidates.size} / ${github.candidates.size} 个候选"
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (showFilter && visibleCandidates.isEmpty()) {
            EtaPreferenceDivider()
            Text(
                text = "没有名称或路径匹配「${filterQuery.trim()}」的 Skill",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(
                    horizontal = EtaPreferenceDefaults.SidePadding,
                    vertical = EtaDesign.Space.MD,
                ),
            )
        }
        visibleCandidates.forEach { candidate ->
            EtaPreferenceDivider()
            EtaPreference(
                title = candidate.name,
                summary = candidate.path,
                enabled = !installing,
                endActions = {
                    if (candidate.path in github.selectedPaths) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "已选择",
                            tint = EtaPreferenceColors.Blue,
                        )
                    }
                },
                onClick = {
                    onAction(AgentSkillsAction.GithubToggleCandidate(candidate.path))
                },
            )
        }
    }
}
