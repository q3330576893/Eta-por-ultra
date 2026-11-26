package io.github.mangi.eta.ui.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.skill.GitHubSkillRepositoryParser
import io.github.mangi.eta.agent.skill.GitHubSkillSourceException
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.skill.SKILL_COMMIT_SCOPE_REF
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillPackageInstaller
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.SkillSourceMetadata
import io.github.mangi.eta.agent.skill.SkillUpdateService
import io.github.mangi.eta.agent.skill.describeUpdateCheckFailure
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.SkillGithubCandidateUi
import io.github.mangi.eta.ui.model.SkillGithubUiState
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.SkillFeedbackEntity
import io.github.mangi.eta.ui.model.AgentSkillsUiState
import io.github.mangi.eta.ui.model.SkillFeedbackAction
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.SkillNoticeUi
import io.github.mangi.eta.ui.model.SkillRatingUi
import io.github.mangi.eta.ui.model.SkillReplacementUi
import io.github.mangi.eta.ui.model.SkillUpdateUi
import io.github.mangi.eta.ui.model.canCheckForUpdate
import io.github.mangi.eta.ui.model.canDeleteUserSkill
import io.github.mangi.eta.ui.model.isSkillUpdateBusy
import io.github.mangi.eta.ui.model.sanitizeSkillFeedbackComment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Skills 管理页状态：列表、开关、删除与本地 ZIP 安装的二次确认流程。 */
internal class AgentSkillsStore(
    context: Context,
    private val scope: CoroutineScope,
    skillZipImportGateway: SkillZipImportGateway? = null,
) {
    private val appContext = context.applicationContext
    private val skillZipImportGateway = skillZipImportGateway ?: CoreSkillZipImportGateway(appContext)
    private var skillNoticeSequence = 0L
    private var pendingSkillZipUri: Uri? = null
    private var pendingSkillZipSha256: String? = null

    var skillsState by mutableStateOf(
        AgentSkillsUiState(
            isLoading = true,
            onSubmitFeedback = ::submitFeedback,
        )
    )
        private set

    fun refreshSkills() {
        scope.launch(Dispatchers.IO) {
            val indexService = runCatching { SkillRuntime.createIndexService(appContext) }.getOrNull()
            val entries = runCatching {
                requireNotNull(indexService) { "技能服务未初始化" }
                    .listSkillsForManagement(forceRefresh = true)
            }.getOrElse {
                withContext(Dispatchers.Main) {
                    skillsState = skillsState.copy(
                        isLoading = false,
                        notice = skillsState.notice ?: newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_read_skills_599082),
                            message = appContext.getString(R.string.state_the_skill_list_is_temporarily_unavailable_please_try_29b0be),
                            isError = true,
                        ),
                    )
                }
                return@launch
            }
            // 来源记录只读一次小文件；读不到（老 Skill / 手工装 / 文件损坏）就是「无来源」，
            // 该 Skill 只是不显示更新检查，其余功能不受影响。
            val sources = runCatching { indexService?.readInstalledSkillSources().orEmpty() }
                .getOrElse {
                    AndroidAgentLogger.warn("Skill source metadata load failed: type=${it.javaClass.simpleName}")
                    emptyMap()
                }
            val items = entries.map { entry ->
                val capabilities = buildList {
                    if (entry.hasScripts) add("scripts")
                    if (entry.hasReferences) add("references")
                    if (entry.hasAssets) add("assets")
                    if (entry.hasEvals) add("evals")
                }
                val source = sources[entry.id]
                SkillItemUi(
                    id = entry.id,
                    name = entry.name,
                    description = entry.description,
                    source = entry.source,
                    enabled = entry.enabled,
                    installed = entry.installed,
                    capabilities = capabilities,
                    hasGitHubSource = source != null,
                    sourceRepository = source?.repository,
                )
            }
            val ratingMap = runCatching {
                EtaDatabase.get(appContext).skillDao().ratingSummaries().associate { summary ->
                    summary.skillId to SkillRatingUi(
                        averageRating = summary.averageRating,
                        feedbackCount = summary.feedbackCount,
                    )
                }
            }.getOrElse {
                // 沿用 AgentLogger 风格：只记失败类型，不落评论正文。
                AndroidAgentLogger.warn("Skill rating load failed: type=${it.javaClass.simpleName}")
                emptyMap()
            }
            withContext(Dispatchers.Main) {
                val presentIds = items.mapTo(mutableSetOf()) { it.id }
                skillsState = skillsState.copy(
                    skills = items,
                    ratings = ratingMap,
                    isLoading = false,
                    // 已删除 / 已无来源记录的 Skill 不能继续留着旧结论，否则同名重装后
                    // 会把上一次的「有更新」直接贴回来；「已是最新」也要一并清掉。
                    updates = skillsState.updates.filterKeys { it in presentIds },
                )
            }
        }
    }

    /**
     * 提交本地评分反馈（3.3）。星级必须在 1..5，评论脱敏后限 500 字符，
     * 只写本地 Room；成功后刷新评分汇总。绝不把评论正文写进日志。
     */
    private fun submitFeedback(action: SkillFeedbackAction) {
        if (action.skillId.isBlank() || action.rating !in 1..5) return
        val sanitizedComment = sanitizeSkillFeedbackComment(action.comment)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                EtaDatabase.get(appContext).skillDao().insertFeedback(
                    SkillFeedbackEntity(
                        skillId = action.skillId,
                        rating = action.rating,
                        comment = sanitizedComment,
                        createdAt = System.currentTimeMillis(),
                    )
                ) > 0
            }.getOrElse {
                AndroidAgentLogger.warn("Skill feedback persist failed: type=${it.javaClass.simpleName}")
                false
            }
            if (succeeded) loadRatingsOnly()
        }
    }

    /**
     * GitHub 技能面板动作。
     *
     * 复用后端既有链路：`PublicGitHubSkillSource.inspect` 列出候选并固定 commit，
     * `SkillPackageInstaller.installRepositoryZip` 下载校验后安装。这里只负责状态流转。
     */
    fun handleGithubAction(action: AgentSkillsAction) {
        when (action) {
            is AgentSkillsAction.OpenGithubSource -> {
                skillsState = skillsState.copy(
                    github = SkillGithubUiState(repository = action.repository),
                )
                // 面板重开时把上次成功列出的仓库补回输入框；显式来源仓库优先，不覆盖。
                if (action.repository.isBlank()) restoreLastGithubRepository()
            }
            is AgentSkillsAction.GithubRepositoryChanged -> {
                val current = skillsState.github ?: SkillGithubUiState()
                skillsState = skillsState.copy(
                    github = current.copy(
                        repository = action.repository,
                        phase = SkillGithubUiState.Phase.Idle,
                        candidates = emptyList(),
                        selectedPaths = emptySet(),
                        error = null,
                        message = null,
                    ),
                )
            }
            AgentSkillsAction.GithubInspect -> inspectGithubRepository()
            is AgentSkillsAction.GithubToggleCandidate -> {
                val current = skillsState.github ?: return
                val selected = current.selectedPaths.toMutableSet()
                if (!selected.add(action.path)) selected.remove(action.path)
                skillsState = skillsState.copy(github = current.copy(selectedPaths = selected))
            }
            AgentSkillsAction.GithubInstall -> installGithubSelection()
            AgentSkillsAction.CloseGithub -> skillsState = skillsState.copy(github = null)
            // 更新检查动作走这里分发：AgentAppRoot 只显式处理导航/导入/开关等动作，
            // 其余全部落到本方法，因此新增的检查/更新动作必须在此消费。
            AgentSkillsAction.CheckUpdates -> checkSkillUpdates(skillIds = null)
            is AgentSkillsAction.CheckUpdate -> checkSkillUpdates(skillIds = listOf(action.skillId))
            is AgentSkillsAction.UpdateSkill -> updateSkillFromSource(action.skillId)
            else -> return
        }
    }

    private fun inspectGithubRepository() {
        val current = skillsState.github ?: return
        if (current.phase == SkillGithubUiState.Phase.Inspecting) return
        val repository = current.repository.trim()
        if (repository.isEmpty()) {
            skillsState = skillsState.copy(
                github = current.copy(error = "请先填写仓库地址，例如 openai/skills"),
            )
            return
        }
        skillsState = skillsState.copy(
            github = current.copy(phase = SkillGithubUiState.Phase.Inspecting, error = null, message = null),
        )
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val parsed = GitHubSkillRepositoryParser.parse(repository)
                val source = PublicGitHubSkillSource(appContext.cacheDir, AgentHttpClient.client)
                try {
                    source.inspect(parsed) to parsed
                } finally {
                    source.close()
                }
            }
            withContext(Dispatchers.Main) {
                val latest = skillsState.github ?: return@withContext
                result.fold(
                    onSuccess = { (inspection, parsed) ->
                        val candidates = inspection.candidates.map {
                            SkillGithubCandidateUi(name = it.name, path = it.path)
                        }
                        skillsState = skillsState.copy(
                            github = latest.copy(
                                repository = parsed.slug,
                                phase = SkillGithubUiState.Phase.Inspected,
                                candidates = candidates,
                                selectedPaths = candidates.map { it.path }.toSet(),
                                resolvedRef = inspection.ref,
                                error = null,
                                message = if (candidates.isEmpty()) {
                                    "该仓库没有找到可安装的 Skill"
                                } else {
                                    "找到 ${candidates.size} 个 Skill（commit ${inspection.commitSha.take(8)}）"
                                },
                                installTargetPath = null,
                            ),
                        )
                        // 只有真正列出成功才记忆仓库，避免把打错的地址存成默认值。
                        rememberLastGithubRepository(parsed.slug)
                    },
                    onFailure = { error ->
                        skillsState = skillsState.copy(
                            github = latest.copy(
                                phase = SkillGithubUiState.Phase.Idle,
                                candidates = emptyList(),
                                selectedPaths = emptySet(),
                                error = githubErrorMessage(error),
                            ),
                        )
                    },
                )
            }
        }
    }

    private fun installGithubSelection() {
        val current = skillsState.github ?: return
        if (current.phase == SkillGithubUiState.Phase.Installing) return
        val paths = current.selectedPaths.toList()
        if (paths.isEmpty()) {
            skillsState = skillsState.copy(github = current.copy(error = "请先勾选要安装的 Skill"))
            return
        }
        skillsState = skillsState.copy(
            github = current.copy(
                phase = SkillGithubUiState.Phase.Installing,
                error = null,
                message = "正在下载并安装 ${paths.size} 个 Skill…",
                installTargetPath = null,
            ),
        )
        scope.launch(Dispatchers.IO) {
            // 安装结果分三种：成功 / 冲突（可解释）/ 其它失败。冲突不能当成异常名甩给用户。
            val outcome = runCatching {
                val parsed = GitHubSkillRepositoryParser.parse(current.repository)
                val source = PublicGitHubSkillSource(appContext.cacheDir, AgentHttpClient.client)
                try {
                    val inspection = source.inspect(parsed)
                    // 下载的归档实现 Closeable：安装完必须关闭，否则临时 zip 会一直留在 cacheDir。
                    source.downloadArchive(
                        GitHubSkillRepositoryParser.resolve(
                            repository = parsed.slug,
                            explicitRef = inspection.ref,
                            explicitPath = null,
                        ),
                    ).use { archive ->
                        val installer = SkillRuntime.createPackageInstaller(appContext)
                        val installResult = installer.installRepositoryZip(
                            openStream = { archive.file.inputStream() },
                            selectedPaths = paths,
                        )
                        // 安装成功才写来源记录。commit 取归档实际固定的那个 SHA，
                        // 而不是 inspect 时的快照：两者之间作者可能又推了新提交，
                        // 记错就会让「有更新」永远对不上。
                        if (installResult is SkillInstallResult.Success) {
                            recordInstalledSources(
                                installed = installResult.installed.map { it.id },
                                repository = parsed.slug,
                                ref = archive.ref,
                                commitSha = archive.commitSha,
                                selectionPaths = paths,
                            )
                        }
                        installResult
                    }
                } finally {
                    source.close()
                }
            }
            val installRoot = runCatching { SkillRuntime.skillsRoot(appContext).absolutePath }.getOrNull()
            withContext(Dispatchers.Main) {
                val latest = skillsState.github ?: return@withContext
                outcome.fold(
                    onSuccess = { installResult ->
                        when (installResult) {
                            is SkillInstallResult.Success -> {
                                skillsState = skillsState.copy(
                                    github = latest.copy(
                                        phase = SkillGithubUiState.Phase.Installed,
                                        error = null,
                                        message = "已安装 ${installResult.installed.size} 个 Skill",
                                        installTargetPath = installRoot,
                                    ),
                                )
                            }
                            is SkillInstallResult.Conflict -> {
                                // 已安装过的 Skill 重复勾选：给出「先删除再重试」的明确动作，
                                // 而不是把 Conflict/异常类名丢给用户猜。
                                val names = installResult.conflicts.map { it.name }.distinct().take(3)
                                val detail = if (names.isEmpty()) "" else "（${names.joinToString()}）"
                                skillsState = skillsState.copy(
                                    github = latest.copy(
                                        phase = SkillGithubUiState.Phase.Inspected,
                                        error = "该 Skill 已存在$detail。如需覆盖，请先在列表中删除后重试",
                                        message = null,
                                        installTargetPath = null,
                                    ),
                                )
                            }
                            is SkillInstallResult.Failure -> {
                                skillsState = skillsState.copy(
                                    github = latest.copy(
                                        phase = SkillGithubUiState.Phase.Inspected,
                                        error = installResult.error.message.ifBlank { "安装失败，请稍后重试" },
                                        message = null,
                                        installTargetPath = null,
                                    ),
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        skillsState = skillsState.copy(
                            github = latest.copy(
                                phase = SkillGithubUiState.Phase.Inspected,
                                error = githubErrorMessage(error),
                                message = null,
                                installTargetPath = null,
                            ),
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    /**
     * 检查更新。
     *
     * 只检查「用户技能 + 有来源记录」的条目：手工装的 Skill 没有 slug/commit，无从判断新旧，
     * 直接跳过而不是报错。限流在 [SkillUpdateService] 内按 skillId 记忆，窗口内重复点击
     * 不会重复打网络。失败原因原样落到 [SkillUpdateUi.error]，绝不静默。
     */
    private fun checkSkillUpdates(skillIds: List<String>?) {
        val candidates = skillsState.skills
            .filter { it.canCheckForUpdate }
            .map { it.id }
            .let { all -> if (skillIds == null) all else all.filter { it in skillIds } }
            .distinct()
        if (candidates.isEmpty()) {
            if (skillIds != null) {
                // 单个 Skill 被点「检查更新」但已无来源记录：必须说清原因，不能什么都不发生。
                skillsState = skillsState.copy(
                    notice = newSkillNotice(
                        title = "无法检查更新",
                        message = "该 Skill 没有 GitHub 来源记录（可能是手工安装或从 ZIP 导入的），"
                            + "无法判断是否有新版本。需要更新时请重新安装。",
                        isError = true,
                    ),
                )
            }
            return
        }
        if (skillsState.isSkillUpdateBusy) return
        skillsState = skillsState.copy(checkingUpdateIds = candidates.toSet())
        scope.launch(Dispatchers.IO) {
            val statuses = runCatching {
                SkillRuntime.createUpdateService(appContext)
                    .checkForUpdates(candidates)
            }.getOrElse { error ->
                AndroidAgentLogger.warn("Skill update check failed: type=${error.javaClass.simpleName}")
                emptyMap()
            }
            withContext(Dispatchers.Main) {
                val updates = skillsState.updates.toMutableMap()
                statuses.forEach { (skillId, status) ->
                    updates[skillId] = SkillUpdateUi(
                        skillId = skillId,
                        installedCommit = status.source.commitSha.take(8),
                        latestCommit = status.latestCommitSha?.take(8),
                        hasUpdate = status.hasUpdate,
                        error = status.error,
                    )
                }
                val failed = statuses.values.filter { it.error != null }
                skillsState = skillsState.copy(
                    updates = updates,
                    checkingUpdateIds = skillsState.checkingUpdateIds - candidates.toSet(),
                    notice = when {
                        // 检查失败必须给出具体原因，不能静默当成「已是最新」。
                        failed.isNotEmpty() -> newSkillNotice(
                            title = "检查更新失败",
                            message = failed.joinToString("\n") { status ->
                                val name = skillsState.skills.firstOrNull { it.id == status.skillId }
                                    ?.name?.safeSkillDisplayName() ?: status.skillId
                                "$name：${status.error.orEmpty()}"
                            }.take(600),
                            isError = true,
                        )
                        skillIds != null -> {
                            val only = statuses.values.firstOrNull()
                            when {
                                only == null -> newSkillNotice(
                                    title = "检查更新失败",
                                    message = "未能读取该 Skill 的来源信息，请稍后重试",
                                    isError = true,
                                )
                                only.hasUpdate -> newSkillNotice(
                                    title = "发现新版本",
                                    message = "来源仓库已有更新（${only.source.commitSha.take(8)} → "
                                        + "${only.latestCommitSha?.take(8).orEmpty()}），可点击「更新」替换。",
                                    isError = false,
                                )
                                else -> newSkillNotice(
                                    title = "已是最新",
                                    message = "来源仓库该路径的最新 commit 与安装时一致"
                                        + "（${only.source.commitSha.take(8)}）。",
                                    isError = false,
                                )
                            }
                        }
                        else -> skillsState.notice
                    },
                )
            }
        }
    }

    /**
     * 走既有安装流程把该 Skill 替换为来源仓库的最新版本。
     *
     * 复用 `inspect → downloadArchive → installRepositoryZip(replaceUserSkills = true)`，
     * 因此备份、回滚、registry 与 SkillRecoveryJournal 语义全部不变。安装成功后把
     * 来源记录改写成新 commit，更新标记随之消失。
     */
    private fun updateSkillFromSource(skillId: String) {
        if (skillsState.isSkillImportingOrUpdating()) return
        val skill = skillsState.skills.firstOrNull { it.id == skillId && it.canCheckForUpdate } ?: return
        skillsState = skillsState.copy(updatingSkillId = skillId, notice = null)
        scope.launch(Dispatchers.IO) {
            val source = runCatching {
                SkillRuntime.createIndexService(appContext).readSkillSource(skillId)
            }.getOrNull()
            if (source == null) {
                withContext(Dispatchers.Main) {
                    skillsState = skillsState.copy(
                        updatingSkillId = null,
                        notice = newSkillNotice(
                            title = "无法更新 Skill",
                            message = "读不到该 Skill 的来源记录，请重新从 GitHub 安装后再试。",
                            isError = true,
                        ),
                    )
                }
                return@launch
            }
            val outcome = runCatching {
                val parsed = GitHubSkillRepositoryParser.parse(source.repository)
                val github = PublicGitHubSkillSource(appContext.cacheDir, AgentHttpClient.client)
                try {
                    // 必须沿用记录里的 ref：用户当初装的是 main，更新就只跟 main 走，
                    // 不能悄悄换成仓库当前的默认分支。
                    github.downloadArchive(
                        GitHubSkillRepositoryParser.resolve(
                            repository = parsed.slug,
                            explicitRef = source.ref,
                            explicitPath = null,
                        ),
                    ).use { archive ->
                        val result = SkillRuntime.createPackageInstaller(appContext).installRepositoryZip(
                            openStream = { archive.file.inputStream() },
                            selectedPaths = listOf(source.path),
                            replaceUserSkills = true,
                            expectedReplacementIds = setOf(skillId),
                        )
                        // 更新会整目录替换：新归档里没有 .eta-source.json，旧记录随目录一起没了。
                        // 必须在这里把来源改写成新 commit，否则「更新一次之后再也查不到更新」。
                        if (result is SkillInstallResult.Success) {
                            val newSource = source.copy(
                                ref = archive.ref,
                                commitSha = archive.commitSha.lowercase(),
                                // 与安装路径一致：archive.commitSha 是 ref 级，scope 必须同步为 ref。
                                commitScope = SKILL_COMMIT_SCOPE_REF,
                                installedAtMillis = System.currentTimeMillis(),
                            )
                            val written = runCatching {
                                SkillRuntime.createIndexService(appContext).recordSkillSource(
                                    skillId = skillId,
                                    metadata = newSource,
                                )
                            }.getOrDefault(false)
                            if (written) {
                                // 让限流窗口从「刚更新完」重新计时，否则紧接着的复查
                                // 会命中旧结论，把「有更新」标记又贴回来。
                                SkillRuntime.createUpdateService(appContext)
                                    .rememberInstalled(skillId, newSource)
                            } else {
                                AndroidAgentLogger.warn("Skill source metadata rewrite failed: skill=$skillId")
                            }
                        }
                        result
                    }
                } finally {
                    github.close()
                }
            }
            withContext(Dispatchers.Main) {
                when (val result = outcome.getOrNull()) {
                    is SkillInstallResult.Success -> {
                        skillsState = skillsState.copy(
                            updatingSkillId = null,
                            notice = newSkillNotice(
                                title = "Skill 已更新",
                                message = "${skill.name.safeSkillDisplayName()} 已更新到来源仓库的最新版本。",
                                isError = false,
                            ),
                        )
                    }
                    is SkillInstallResult.Conflict -> {
                        val names = result.conflicts.map { it.name }.distinct().take(3)
                        skillsState = skillsState.copy(
                            updatingSkillId = null,
                            notice = newSkillNotice(
                                title = "无法更新 Skill",
                                message = "目标目录不可替换"
                                    + (if (names.isEmpty()) "" else "（${names.joinToString()}）")
                                    + "；请先在列表中删除该 Skill 再重新安装。",
                                isError = true,
                            ),
                        )
                    }
                    is SkillInstallResult.Failure -> {
                        skillsState = skillsState.copy(
                            updatingSkillId = null,
                            notice = newSkillNotice(
                                title = "无法更新 Skill",
                                message = result.error.message.ifBlank { "安装新版本失败，请稍后重试" },
                                isError = true,
                            ),
                        )
                    }
                    null -> {
                        val error = outcome.exceptionOrNull()
                        skillsState = skillsState.copy(
                            updatingSkillId = null,
                            notice = newSkillNotice(
                                title = "无法更新 Skill",
                                message = when (error) {
                                    null -> "更新未完成，请稍后重试"
                                    else -> describeUpdateCheckFailure(error)
                                },
                                isError = true,
                            ),
                        )
                    }
                }
            }
            refreshSkills()
        }
    }

    /** 导入或更新进行中时，不允许再发起另一次写操作。 */
    private fun AgentSkillsUiState.isSkillImportingOrUpdating(): Boolean =
        isImporting || busySkillId != null || updatingSkillId != null || checkingUpdateIds.isNotEmpty()

    /** 安装成功后写入来源记录；失败只记日志，不影响已完成的安装。 */
    private fun recordInstalledSources(
        installed: List<String>,
        repository: String,
        ref: String,
        commitSha: String,
        selectionPaths: List<String>,
    ) {
        if (installed.isEmpty()) return
        // 批量安装时每条候选有自己的仓库内路径。候选名默认取路径末段，所以按末段反查；
        // 反查不到（作者自定义了 name）时降级成 ref 口径，仍然可比较，只是粒度更粗。
        val pathByDirectoryName = selectionPaths
            .filter { it != "." }
            .associateBy { it.substringAfterLast('/') }
        installed.forEach { skillId ->
            val path = pathByDirectoryName[skillId] ?: "."
            // commitSha 来自 ref 级解析（整个 ref 的最新 commit），口径必须与来源一致：
            // 若这里标成 path 级，检查更新时会拿 path 的 commit 去比 ref 的 commit，
            // 仓库任何无关提交都会让该 Skill 永久显示「有更新」。
            val scope = SKILL_COMMIT_SCOPE_REF
            val metadata = SkillSourceMetadata(
                repository = repository,
                ref = ref,
                path = path,
                commitSha = commitSha,
                installedAtMillis = System.currentTimeMillis(),
                commitScope = scope,
            )
            val written = runCatching {
                SkillRuntime.createIndexService(appContext).recordSkillSource(skillId, metadata)
            }.getOrDefault(false)
            if (written) {
                // 刚装完的版本就是基线：把限流窗口重置到这里，避免用户紧接着点
                // 「检查更新」时又被上一次的旧结论覆盖。
                runCatching {
                    SkillRuntime.createUpdateService(appContext).rememberInstalled(skillId, metadata)
                }
            } else {
                AndroidAgentLogger.warn("Skill source metadata write failed: skill=$skillId")
            }
        }
    }

    /** 把后端异常码翻成可操作的中文提示。 */
    private fun githubErrorMessage(error: Throwable): String = when {        // 冲突必须先判：GitHubSkillSourceException / 安装异常里也可能带 SKILL_EXISTS，
        // 若排在后面就永远走不到，用户只会看到异常原文。
        isSkillExistsError(error) -> "该 Skill 已存在。如需覆盖，请先在列表中删除后重试"
        error is GitHubSkillSourceException -> error.message ?: error.code
        error is IllegalArgumentException -> error.message ?: "仓库地址无效"
        else -> "操作失败：${error.message?.takeIf { it.isNotBlank() } ?: "请稍后重试"}"
    }

    private fun isSkillExistsError(error: Throwable): Boolean {
        val text = buildString {
            append(error.message.orEmpty())
            append(' ')
            append(error.javaClass.simpleName)
            append(' ')
            append(error.cause?.message.orEmpty())
        }
        return "SKILL_EXISTS" in text || "Conflict" in text || "conflict" in text
    }

    /** 面板重开时补回上次成功列出的仓库；读失败静默，不打扰用户。 */
    private fun restoreLastGithubRepository() {
        scope.launch(Dispatchers.IO) {
            val stored = runCatching {
                appContext.getSharedPreferences(SKILL_GITHUB_PREFS, Context.MODE_PRIVATE)
                    .getString(SKILL_GITHUB_LAST_REPO_KEY, null)
            }.getOrNull()?.trim().orEmpty()
            if (stored.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                val latest = skillsState.github ?: return@withContext
                // 用户已经手动输入内容时不覆盖，避免和真实输入抢焦点。
                if (latest.repository.isNotBlank()) return@withContext
                skillsState = skillsState.copy(github = latest.copy(repository = stored))
            }
        }
    }

    /** 只在成功列出仓库后落盘；写失败静默（记住地址是纯体验优化）。 */
    private suspend fun rememberLastGithubRepository(repository: String) {
        val slug = repository.trim()
        if (slug.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                appContext.getSharedPreferences(SKILL_GITHUB_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(SKILL_GITHUB_LAST_REPO_KEY, slug)
                    .apply()
            }
        }
    }

    /** 仅刷新评分汇总，不重置列表。 */
    private fun loadRatingsOnly() {
        scope.launch(Dispatchers.IO) {
            val ratingMap = runCatching {
                EtaDatabase.get(appContext).skillDao().ratingSummaries().associate { summary ->
                    summary.skillId to SkillRatingUi(
                        averageRating = summary.averageRating,
                        feedbackCount = summary.feedbackCount,
                    )
                }
            }.getOrElse { emptyMap() }
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(ratings = ratingMap)
            }
        }
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).setSkillEnabled(skillId, enabled)
            }.isSuccess
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        skillsState.notice
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_update_skills_04e56c),
                            message = appContext.getString(R.string.state_the_skill_switch_has_not_changed_please_try_again_la_fa262f),
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun deleteSkill(skillId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val skill = skillsState.skills.firstOrNull { it.id == skillId }
            ?.takeIf { it.canDeleteUserSkill }
            ?: return
        val skillName = skill.name.safeSkillDisplayName()
        skillsState = skillsState.copy(busySkillId = skillId, notice = null)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).deleteSkill(skillId)
            }.getOrDefault(false)
            if (succeeded) {
                runCatching { EtaDatabase.get(appContext).skillDao().deleteFeedbackForSkill(skillId) }
                    .onFailure { AndroidAgentLogger.warn("Skill feedback cleanup failed: type=${it.javaClass.simpleName}") }
            }
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_skill_has_been_deleted_34c29b),
                            message = appContext.getString(R.string.skill_deleted_message, skillName),
                            isError = false,
                        )
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_delete_skill_1583c9),
                            message = appContext.getString(R.string.state_deletion_is_not_complete_eta_will_try_to_recover_whe_c4297e),
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun importSkillZip(uriValue: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull()
            ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
        if (uri == null) {
            skillsState = skillsState.copy(
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_read_skill_pack_a53563),
                    message = appContext.getString(R.string.state_please_select_the_zip_file_provided_by_the_system_fi_fea145),
                    isError = true,
                ),
            )
            return
        }
        pendingSkillZipUri = uri
        pendingSkillZipSha256 = null
        launchSkillZipImport(
            uri = uri,
            replaceUserSkill = false,
            expectedReplacementId = null,
            expectedArchiveSha256 = null,
        )
    }

    fun confirmSkillZipReplacement() {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val uri = pendingSkillZipUri
        if (uri == null) {
            pendingSkillZipSha256 = null
            skillsState = skillsState.copy(
                replacement = null,
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_continue_installation_136d7c),
                    message = appContext.getString(R.string.state_skill_pack_is_no_longer_available_please_select_the__7cdfb4),
                    isError = true,
                ),
            )
            return
        }
        val replacementId = skillsState.replacement?.id
        val archiveSha256 = pendingSkillZipSha256
        if (replacementId == null || archiveSha256 == null) {
            pendingSkillZipUri = null
            pendingSkillZipSha256 = null
            skillsState = skillsState.copy(
                replacement = null,
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_continue_installation_136d7c),
                    message = appContext.getString(R.string.state_replacement_confirmation_has_expired_please_select_t_fce9f2),
                    isError = true,
                ),
            )
            return
        }
        launchSkillZipImport(
            uri = uri,
            replaceUserSkill = true,
            expectedReplacementId = replacementId,
            expectedArchiveSha256 = archiveSha256,
        )
    }

    fun cancelSkillZipReplacement() {
        if (skillsState.isImporting) return
        pendingSkillZipUri = null
        pendingSkillZipSha256 = null
        skillsState = skillsState.copy(replacement = null)
    }

    private fun launchSkillZipImport(
        uri: Uri,
        replaceUserSkill: Boolean,
        expectedReplacementId: String?,
        expectedArchiveSha256: String?,
    ) {
        skillsState = skillsState.copy(
            isImporting = true,
            replacement = null,
            notice = null,
        )
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                skillZipImportGateway.installLocalZip(
                    openStream = {
                        appContext.contentResolver.openInputStream(uri)
                            ?: error(appContext.getString(R.string.state_ui_unable_to_open_selection_9f0004))
                    },
                    replaceUserSkill = replaceUserSkill,
                    expectedReplacementId = expectedReplacementId,
                    expectedArchiveSha256 = expectedArchiveSha256,
                )
            }.getOrElse {
                SkillZipImportOutcome.Failure(SkillZipImportOutcome.FailureCode.READ_FAILED)
            }
            withContext(Dispatchers.Main) {
                applySkillZipImportOutcome(outcome)
            }
        }
    }

    private fun applySkillZipImportOutcome(outcome: SkillZipImportOutcome) {
        when (outcome) {
            is SkillZipImportOutcome.Success -> {
                val installed = outcome.skills.singleOrNull()
                pendingSkillZipUri = null
                pendingSkillZipSha256 = null
                skillsState = skillsState.copy(
                    isImporting = false,
                    replacement = null,
                    notice = if (installed == null) {
                        skillZipFailureNotice(SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS)
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_skill_installed_b07e54),
                            message = appContext.getString(
                                R.string.skill_enabled_message,
                                installed.name.safeSkillDisplayName(),
                            ),
                            isError = false,
                        )
                    },
                )
                if (installed != null) refreshSkills()
            }

            is SkillZipImportOutcome.Conflict -> {
                val conflict = outcome.skills.singleOrNull()
                val archiveSha256 = outcome.archiveSha256
                if (
                    conflict != null &&
                    conflict.source == "user" &&
                    conflict.replaceAllowed &&
                    archiveSha256 != null
                ) {
                    val existingName = skillsState.skills
                        .firstOrNull { it.id == conflict.id && it.installed }
                        ?.name
                        .orEmpty()
                        .ifBlank { conflict.name }
                    pendingSkillZipSha256 = archiveSha256
                    skillsState = skillsState.copy(
                        isImporting = false,
                        replacement = SkillReplacementUi(
                            id = conflict.id,
                            name = existingName.safeSkillDisplayName(),
                        ),
                        notice = null,
                    )
                } else {
                    pendingSkillZipUri = null
                    pendingSkillZipSha256 = null
                    skillsState = skillsState.copy(
                        isImporting = false,
                        replacement = null,
                        notice = skillZipFailureNotice(
                            if (conflict?.source == "builtin") {
                                SkillZipImportOutcome.FailureCode.BUILTIN_CONFLICT
                            } else if (conflict != null && conflict.replaceAllowed) {
                                SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED
                            } else if (conflict != null && !conflict.replaceAllowed) {
                                SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE
                            } else {
                                SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS
                            },
                        ),
                    )
                }
            }

            is SkillZipImportOutcome.Failure -> {
                pendingSkillZipUri = null
                pendingSkillZipSha256 = null
                skillsState = skillsState.copy(
                    isImporting = false,
                    replacement = null,
                    notice = skillZipFailureNotice(outcome.code),
                )
                if (outcome.code == SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED) {
                    refreshSkills()
                }
            }
        }
    }

    private fun skillZipFailureNotice(code: SkillZipImportOutcome.FailureCode): SkillNoticeUi {
        val message = when (code) {
            SkillZipImportOutcome.FailureCode.INVALID_ARCHIVE -> appContext.getString(R.string.state_ui_the_selected_file_is_not_a_valid_zip_package_bff052)
            SkillZipImportOutcome.FailureCode.ARCHIVE_LIMIT_EXCEEDED -> appContext.getString(R.string.state_ui_the_skill_pack_exceeds_the_safe_size_or_file_num_42e151)
            SkillZipImportOutcome.FailureCode.UNSAFE_ARCHIVE -> appContext.getString(R.string.state_ui_the_skill_pack_contains_an_unsafe_file_path_and__d8cfc0)
            SkillZipImportOutcome.FailureCode.NO_SKILL -> appContext.getString(R.string.state_ui_skill_md_not_found_in_zip_a57975)
            SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS -> appContext.getString(R.string.state_ui_the_local_zip_must_contain_only_one_skill_b89daf)
            SkillZipImportOutcome.FailureCode.INVALID_SKILL -> appContext.getString(R.string.state_ui_skill_md_is_missing_required_information_or_is_i_debe6e)
            SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED -> appContext.getString(R.string.state_ui_the_zip_content_has_changed_please_reselect_and__ab8b12)
            SkillZipImportOutcome.FailureCode.BUILTIN_CONFLICT -> appContext.getString(R.string.state_ui_built_in_skills_with_the_same_name_are_protected_446ad3)
            SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE ->
                appContext.getString(R.string.state_ui_the_target_with_the_same_name_is_not_a_user_skil_00474b)
            SkillZipImportOutcome.FailureCode.READ_FAILED -> appContext.getString(R.string.state_ui_the_selected_file_cannot_be_read_please_select_a_7265b9)
            SkillZipImportOutcome.FailureCode.STORAGE_FAILED -> appContext.getString(R.string.state_ui_unable_to_save_skills_original_skills_have_been__9d4748)
            SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED ->
                appContext.getString(R.string.state_ui_the_installation_failed_and_automatic_recovery_d_0d9e01)
        }
        return newSkillNotice(
            title = appContext.getString(R.string.state_unable_to_install_skill_0ec70b),
            message = message,
            isError = true,
        )
    }

    fun reinstallBuiltin(skillId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).installBuiltinSkill(skillId)
            }.isSuccess
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        skillsState.notice
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_restore_skills_6b3d23),
                            message = appContext.getString(R.string.state_the_built_in_skills_have_not_changed_please_try_agai_34e5e3),
                            isError = true,
                        )
                    },
                )
            }
            if (succeeded) refreshSkills()
        }
    }

    fun dismissSkillNotice() {
        skillsState = skillsState.copy(notice = null)
    }

    private fun newSkillNotice(
        title: String,
        message: String,
        isError: Boolean,
    ): SkillNoticeUi = SkillNoticeUi(
        id = ++skillNoticeSequence,
        title = title,
        message = message,
        isError = isError,
    )

    private fun String.safeSkillDisplayName(): String =
        lineSequence().firstOrNull().orEmpty().trim().ifBlank { appContext.getString(R.string.state_ui_unnamed_skill_a58008) }.take(80)

    private companion object {
        /** GitHub 面板的本地记忆：只存「上次成功列出的仓库 slug」，不含任何凭据。 */
        const val SKILL_GITHUB_PREFS = "eta_skill_github_panel"
        const val SKILL_GITHUB_LAST_REPO_KEY = "skill_github_last_repo"
    }
}
