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
import io.github.mangi.eta.agent.skill.SkillPackageInstaller
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.SkillGithubCandidateUi
import io.github.mangi.eta.ui.model.SkillGithubUiState
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.SkillFeedbackEntity
import io.github.mangi.eta.ui.model.AgentSkillsUiState
import io.github.mangi.eta.ui.model.SkillFeedbackAction
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.SkillNoticeUi
import io.github.mangi.eta.ui.model.SkillRatingUi
import io.github.mangi.eta.ui.model.SkillReplacementUi
import io.github.mangi.eta.ui.model.canDeleteUserSkill
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
            val entries = runCatching {
                SkillRuntime.createIndexService(appContext)
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
            val items = entries.map { entry ->
                val capabilities = buildList {
                    if (entry.hasScripts) add("scripts")
                    if (entry.hasReferences) add("references")
                    if (entry.hasAssets) add("assets")
                    if (entry.hasEvals) add("evals")
                }
                SkillItemUi(
                    id = entry.id,
                    name = entry.name,
                    description = entry.description,
                    source = entry.source,
                    enabled = entry.enabled,
                    installed = entry.installed,
                    capabilities = capabilities,
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
                skillsState = skillsState.copy(
                    skills = items,
                    ratings = ratingMap,
                    isLoading = false,
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
                            ),
                        )
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
            github = current.copy(phase = SkillGithubUiState.Phase.Installing, error = null, message = null),
        )
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val parsed = GitHubSkillRepositoryParser.parse(current.repository)
                val source = PublicGitHubSkillSource(appContext.cacheDir, AgentHttpClient.client)
                try {
                    val inspection = source.inspect(parsed)
                    val archive = source.downloadArchive(
                        GitHubSkillRepositoryParser.resolve(
                            repository = parsed.slug,
                            explicitRef = inspection.ref,
                            explicitPath = null,
                        ),
                    )
                    val installer = SkillRuntime.createPackageInstaller(appContext)
                    val archiveFile = archive.file
                    installer.installRepositoryZip(
                        openStream = { archiveFile.inputStream() },
                        selectedPaths = paths,
                    )
                } finally {
                    source.close()
                }
            }
            withContext(Dispatchers.Main) {
                val latest = skillsState.github ?: return@withContext
                result.fold(
                    onSuccess = {
                        skillsState = skillsState.copy(
                            github = latest.copy(
                                phase = SkillGithubUiState.Phase.Installed,
                                error = null,
                                message = "已安装 ${paths.size} 个 Skill",
                            ),
                        )
                    },
                    onFailure = { error ->
                        skillsState = skillsState.copy(
                            github = latest.copy(
                                phase = SkillGithubUiState.Phase.Inspected,
                                error = githubErrorMessage(error),
                            ),
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    /** 把后端异常码翻成可操作的中文提示。 */
    private fun githubErrorMessage(error: Throwable): String = when {
        error is GitHubSkillSourceException -> error.message ?: error.code
        error is IllegalArgumentException -> error.message ?: "仓库地址无效"
        else -> "操作失败：${error.javaClass.simpleName}"
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
}
