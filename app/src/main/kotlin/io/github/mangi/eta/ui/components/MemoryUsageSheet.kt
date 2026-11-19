package io.github.mangi.eta.ui.components
import android.content.pm.ApplicationInfo
import android.os.Process
import android.widget.Toast
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.MemoryProcessProbe
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.ui.model.MemoryRecallHit
import io.github.mangi.eta.ui.model.MemoryRecallProjection
import io.github.mangi.eta.ui.model.MemoryRecallResult
import io.github.mangi.eta.ui.model.MemoryRecallZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 内存面板：概览 + 按应用聚合的占用排行 + Eta 自己起的后台任务。
 *
 * 弹出动效不在本文件里另写一套：直接交给 [CornerScaleDialog]，
 * 与输入框右上角同一行的 MCP / 技能面板、以及对话页右上角 ⋯ 菜单共用同一条进度轨道
 * （spring 缩放 + 底边 clip-reveal + 同色遮罩）。
 */
@Composable
internal fun MemoryUsageSheet(show: Boolean, onDismiss: () -> Unit) {
    CornerScaleDialog(
        show = show,
        onDismiss = onDismiss,
    ) { requestClose ->
        MemorySheetCard(onDismiss = requestClose)
    }
}

@Composable
private fun MemorySheetCard(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selfPid = remember { Process.myPid() }
    val selfPackage = remember { context.packageName }
    var procs by remember { mutableStateOf<List<MemoryProcessProbe.Proc>>(emptyList()) }
    var appRows by remember { mutableStateOf<List<AppUsageRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    var usageTick by remember { mutableStateOf(0) }
    var armedPackage by remember { mutableStateOf<String?>(null) }
    // 2.4 召回自检入口：本地展开、本地展示，不联网不外发。
    var showRecall by remember { mutableStateOf(false) }
    val usage = remember(loaded, usageTick) { readMemoryUsage(context) }

    suspend fun reload() {
        // 包名与应用名解析以前放在 composition 里同步跑：每一行两次 PackageManager 调用，
        // 30 行就是 60 次主线程查询，面板打开会明显卡。现在整段搬到 IO。
        val (nextProcs, nextRows) = withContext(Dispatchers.IO) {
            val snapshot = MemoryProcessProbe.snapshot()
            val usage = MemoryProcessProbe.appUsage(snapshot, MemoryRankLimit)
            snapshot to resolveAppRows(context, usage, selfPackage)
        }
        procs = nextProcs
        appRows = nextRows
        loaded = true
        usageTick += 1
    }

    LaunchedEffect(Unit) { reload() }

    val children = remember(procs, selfPid) {
        MemoryProcessProbe.descendants(procs, selfPid)
    }
    val builds = remember(procs) { MemoryProcessProbe.buildProcesses(procs) }
    val apps = appRows

    fun terminate(pids: List<Int>) {
        if (pids.isEmpty() || busy) return
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { MemoryProcessProbe.terminate(pids) }
            // 结束要一会儿才反映到 ps，稍等一下再抓，避免列表回到旧状态。
            delay(400)
            reload()
            busy = false
        }
    }

    fun quickClean() {
        if (cleaning) return
        cleaning = true
        scope.launch {
            var result = withContext(Dispatchers.IO) { MemoryProcessProbe.quickClean() }
            if (result.failure == MemoryProcessProbe.CleanFailure.NO_ROOT) {
                // 之前这里只弹一句"需要 Root"，用户没法在这个界面里恢复；
                // 现在主动申请一次授权后自动重试。
                withContext(Dispatchers.IO) { RootAccess.request(context).join() }
                result = withContext(Dispatchers.IO) { MemoryProcessProbe.quickClean() }
            }
            delay(300)
            reload()
            cleaning = false
            // 失败原因分开提示：以前"没授权"和"脚本没跑完"共用一句话，误报过很多次。
            val message = when {
                result.failure == MemoryProcessProbe.CleanFailure.NO_ROOT ->
                    context.getString(R.string.memory_sheet_clean_no_root)
                result.failure == MemoryProcessProbe.CleanFailure.DENIED ->
                    context.getString(R.string.memory_sheet_clean_denied)
                result.failure == MemoryProcessProbe.CleanFailure.TIMEOUT ->
                    context.getString(R.string.memory_sheet_clean_timeout)
                result.failure != null ->
                    context.getString(R.string.memory_sheet_clean_script)
                result.killedDaemons > 0 && result.killFailed > 0 ->
                    context.getString(R.string.memory_sheet_clean_partial, result.killedDaemons, result.killFailed)
                result.killedDaemons == 0 && result.killFailed > 0 ->
                    context.getString(R.string.memory_sheet_clean_partial, 0, result.killFailed)
                result.killedDaemons == 0 ->
                    context.getString(R.string.memory_sheet_clean_none)
                result.cacheFlushed ->
                    context.getString(R.string.memory_sheet_clean_ok, result.killedDaemons, result.cacheFreedMb)
                else ->
                    context.getString(R.string.memory_sheet_clean_ok_no_cache, result.killedDaemons)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun release(packageName: String, label: String) {
        if (busy) return
        busy = true
        armedPackage = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) { MemoryProcessProbe.release(packageName) }
            delay(400)
            reload()
            busy = false
            Toast.makeText(
                context,
                if (ok) {
                    context.getString(R.string.memory_sheet_release_done, label)
                } else {
                    context.getString(R.string.memory_sheet_release_failed, label)
                },
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.memory_sheet_title),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                text = stringResource(R.string.memory_sheet_cancel),
                onClick = onDismiss,
                minWidth = 0.dp,
                minHeight = 30.dp,
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColors(),
            )
        }
        Spacer(Modifier.height(6.dp))
        UsageSummary(usage = usage, cleaning = cleaning, onClean = ::quickClean)
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            if (builds.isNotEmpty()) {
                item(key = "build-title") {
                    SectionTitle(
                        stringResource(R.string.memory_sheet_build_title),
                        trailing = stringResource(
                            R.string.memory_sheet_build_count,
                            builds.size,
                            formatMegabytes(builds.sumOf { it.rssBytes }),
                        ),
                    )
                }
                items(items = builds, key = { "b-${it.pid}" }) { proc ->
                    TaskRow(proc = proc, onKill = { terminate(listOf(proc.pid)) })
                }
            }
            item(key = "rank-title") {
                SectionTitle(
                    stringResource(R.string.memory_sheet_rank_title),
                    trailing = stringResource(R.string.memory_sheet_release_hint),
                )
            }
            items(items = apps, key = { "a-${it.packageName}" }) { row ->
                AppUsageRow(
                    row = row,
                    armed = armedPackage == row.packageName,
                    onArm = { armedPackage = row.packageName },
                    onCancel = { armedPackage = null },
                    onConfirm = { release(row.packageName, row.label) },
                )
            }
            item(key = "task-title") {
                SectionTitle(
                    stringResource(R.string.memory_sheet_own_tasks_title),
                    trailing = if (children.isEmpty()) null else stringResource(
                        R.string.memory_sheet_own_tasks_count,
                        children.size,
                    ),
                )
            }
            if (children.isEmpty()) {
                item(key = "task-empty") {
                    Text(
                        text = stringResource(R.string.memory_sheet_own_tasks_empty),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(items = children, key = { "c-${it.pid}" }) { proc ->
                TaskRow(
                    proc = proc,
                    onKill = { terminate(listOf(proc.pid)) },
                )
            }
            // 2.4：召回测试入口。放在列表末尾，与进程/任务区隔开。
            item(key = "recall-entry") {
                SectionTitle(RecallStrings.TITLE)
                TextButton(
                    text = if (showRecall) RecallStrings.HIDE else RecallStrings.OPEN,
                    onClick = { showRecall = !showRecall },
                    minWidth = 0.dp,
                    minHeight = 36.dp,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showRecall) {
                    MemoryRecallTester()
                }
            }
        }
        if (builds.isNotEmpty()) {
            TextButton(
                text = stringResource(R.string.memory_sheet_build_kill_all, builds.size),
                onClick = { terminate(builds.map { it.pid }) },
                enabled = !busy,
                minWidth = 0.dp,
                minHeight = 36.dp,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            )
        }
        if (children.isNotEmpty()) {
            TextButton(
                text = stringResource(R.string.memory_sheet_kill_all, children.size),
                onClick = { terminate(children.map { it.pid }) },
                enabled = !busy,
                minWidth = 0.dp,
                minHeight = 36.dp,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            )
        }
    }
}

private const val MemoryRankLimit = 8

/** 超过这个使用率就把「立即清理」标出来，和胶囊/守护脚本的口径一致。 */
private const val HIGH_PRESSURE_PERCENT = 85

/** 排行里的一行：解析成应用名，并判断能不能释放。 */
private data class AppUsageRow(
    val packageName: String,
    val label: String,
    val rssBytes: Long,
    val releasable: Boolean,
)

private fun resolveAppRows(
    context: android.content.Context,
    usage: List<MemoryProcessProbe.AppUsage>,
    selfPackage: String,
): List<AppUsageRow> {
    val pm = context.packageManager
    return usage.map { item ->
        // 包名 -> (显示名, 是否系统应用) 缓存：同一包在多次刷新间不再重复查询。
        val cached = appInfoCache[item.packageName] ?: run {
            val info = runCatching {
                pm.getPackageInfo(item.packageName, 0).applicationInfo
            }.getOrNull()
            val label = info
                ?.let { app -> runCatching { pm.getApplicationLabel(app).toString() }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
                ?: item.packageName
            val systemApp = info == null ||
                (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            (label to systemApp).also { appInfoCache[item.packageName] = it }
        }
        val label = cached.first
        val systemApp = cached.second
        AppUsageRow(
            packageName = item.packageName,
            label = label,
            rssBytes = item.rssBytes,
            releasable = !systemApp && item.packageName != selfPackage,
        )
    }
}

@Composable
private fun UsageSummary(usage: MemoryUsage, cleaning: Boolean, onClean: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    R.string.memory_sheet_used,
                    formatGigabytes(usage.usedBytes),
                    formatGigabytes(usage.totalBytes),
                ),
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.memory_sheet_available, formatGigabytes(usage.availableBytes)),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(3.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(usage.usedPercent / 100f)
                    .height(6.dp)
                    .background(MiuixTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.memory_sheet_percent, usage.usedPercent),
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (usage.usedPercent >= HIGH_PRESSURE_PERCENT) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.memory_sheet_pressure_high),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                text = stringResource(
                    if (cleaning) R.string.memory_sheet_quick_cleaning else R.string.memory_sheet_quick_clean,
                ),
                onClick = onClean,
                enabled = !cleaning,
                minWidth = 0.dp,
                minHeight = 30.dp,
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
        if (usage.usedPercent >= HIGH_PRESSURE_PERCENT) {
            Text(
                text = stringResource(R.string.memory_sheet_pressure_note),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
            )
        }
        if (usage.swapTotalBytes > 0L) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.memory_sheet_swap_line,
                    formatGigabytes(usage.swapUsedBytes),
                    formatGigabytes(usage.swapTotalBytes),
                ),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.memory_sheet_scope_note),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = trailing,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun AppUsageRow(
    row: AppUsageRow,
    armed: Boolean,
    onArm: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 应用名和包名不同才补一行，方便对号和排查。
            if (row.label != row.packageName) {
                Text(
                    text = row.packageName,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatMegabytes(row.rssBytes),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (row.releasable) {
            Spacer(Modifier.width(2.dp))
            if (armed) {
                TextButton(
                    text = stringResource(R.string.memory_sheet_cancel),
                    onClick = onCancel,
                    minWidth = 0.dp,
                    minHeight = 30.dp,
                    insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.textButtonColors(),
                )
                TextButton(
                    text = stringResource(R.string.memory_sheet_confirm),
                    onClick = onConfirm,
                    minWidth = 0.dp,
                    minHeight = 30.dp,
                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            } else {
                TextButton(
                    text = stringResource(R.string.memory_sheet_release),
                    onClick = onArm,
                    minWidth = 0.dp,
                    minHeight = 30.dp,
                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun TaskRow(proc: MemoryProcessProbe.Proc, onKill: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = proc.name,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatMegabytes(proc.rssBytes),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(4.dp))
        TextButton(
            text = stringResource(R.string.memory_sheet_kill),
            onClick = onKill,
            minWidth = 0.dp,
            minHeight = 30.dp,
            insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

/** 包名解析缓存：面板每次刷新都重新查 PackageManager 会拖慢打开速度。 */
private val appInfoCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Boolean>>()

/**
 * 2.4 召回面板文案。strings.xml 属另一代理独占，本文件不新增资源 key 以保证即时可编译；
 * 需三语化时把下列常量迁入 strings.xml，建议 key 见收尾报告。
 */
private object RecallStrings {
    const val TITLE = "记忆召回自检"
    const val OPEN = "召回测试"
    const val HIDE = "收起召回测试"
    const val HINT = "输入关键词检索记忆…"
    const val SEARCH = "检索"
    const val SEARCHING = "检索中…"
    const val EMPTY = "没有命中"
    const val TRUNCATED = "结果已截断（仅显示前 30 行）"
    const val HIT = "命中"
    const val ZONE_HOT = "热区"
    const val ZONE_COLD = "冷区"
    fun summary(zone: String, count: Int) = "区域：$zone · 命中 $count 行"
}

/**
 * 2.4 召回测试：输入 query -> 调 AgentMemoryRepository.read(query=...)（内部即 search()）
 * -> 经 MemoryRecallProjection 投影展示命中行/热区冷区/时间戳。
 * 纯本地展示，凭据遮罩由投影层完成，不联网、不外发。
 */
@Composable
internal fun MemoryRecallTester() {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<MemoryRecallResult?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 34.dp)
                .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = RecallStrings.HINT,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                inner()
            },
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            text = if (busy) RecallStrings.SEARCHING else RecallStrings.SEARCH,
            onClick = {
                if (busy || query.isBlank()) return@TextButton
                busy = true
                val submittedQuery = query.trim()
                scope.launch {
                    result = withContext(Dispatchers.IO) {
                        runCatching {
                            // 公开入口 read(query) 内部对热区 search 未命中再搜冷区归档。
                            val raw = AgentMemoryRepository.read(query = submittedQuery)
                            MemoryRecallProjection.project(raw, submittedQuery)
                        }.getOrDefault(null)
                    }
                    busy = false
                }
            },
            enabled = !busy && query.isNotBlank(),
            minWidth = 0.dp,
            minHeight = 34.dp,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        )
        result?.let { recall ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = RecallStrings.summary(
                    if (recall.zone == MemoryRecallZone.HOT) RecallStrings.ZONE_HOT else RecallStrings.ZONE_COLD,
                    recall.matchedLines,
                ),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            recall.latestUpdateDate?.let { date ->
                Text("最近记录日期：$date", style = MiuixTheme.textStyles.footnote2)
            }
            recall.staleNotices.forEach { notice ->
                Text(notice, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            recall.sensitivityNotice?.let { notice ->
                Text(
                    text = notice,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.error,
                )
            }
            if (recall.isEmpty) {
                Text(
                    text = RecallStrings.EMPTY,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                recall.hits.take(30).forEach { hit -> MemoryRecallHitRow(hit = hit) }
                if (recall.truncated) {
                    Text(
                        text = RecallStrings.TRUNCATED,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryRecallHitRow(hit: MemoryRecallHit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${hit.lineNumber}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.width(6.dp))
            if (hit.isMatched) {
                Text(
                    text = RecallStrings.HIT,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
            }
            hit.date?.let { date ->
                Text(
                    text = date,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            hit.score?.let { score ->
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "%.2f".format(score),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Text(
            text = hit.text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
