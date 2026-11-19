package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Shared schema/executor observation limits and honest structural change reporting. */
internal object AgentScreenObservationContract {
    const val DEFAULT_INCLUDE_SCREENSHOT = false
    const val DEFAULT_INCLUDE_UI_TREE = true
    const val DEFAULT_MAX_NODES = 60
    const val MIN_MAX_NODES = 1
    const val MAX_MAX_NODES = 120
    const val DEFAULT_INCLUDE_VISUAL_HINT = true
    private const val MAX_DIFF_NODES = 12
    private const val MAX_FIELD_CHARS = 256

    data class Options(val includeScreenshot: Boolean, val includeUiTree: Boolean, val maxNodes: Int,
        val includeVisualHint: Boolean = DEFAULT_INCLUDE_VISUAL_HINT)

    fun resolve(arguments: JSONObject): Options = Options(
        arguments.optBoolean("include_screenshot", DEFAULT_INCLUDE_SCREENSHOT),
        arguments.optBoolean("include_ui_tree", DEFAULT_INCLUDE_UI_TREE),
        arguments.optInt("max_nodes", DEFAULT_MAX_NODES).coerceIn(MIN_MAX_NODES, MAX_MAX_NODES),
        arguments.optBoolean("include_visual_hint", DEFAULT_INCLUDE_VISUAL_HINT),
    )

    data class ObservedNode(
        val viewId: String = "", val text: String = "", val desc: String = "", val checked: Boolean? = null,
        val enabled: Boolean = true, val className: String = "", val packageName: String = "",
    ) {
        val signature: String get() = listOf(viewId, text, desc, checked?.toString().orEmpty(), enabled.toString()).joinToString("\u001f")
        fun toJson(): JSONObject = JSONObject().put("view_id", viewId.take(MAX_FIELD_CHARS))
            .put("text", text.take(MAX_FIELD_CHARS)).put("desc", desc.take(MAX_FIELD_CHARS))
            .put("checked", checked ?: JSONObject.NULL).put("enabled", enabled)
    }
    data class KeywordHit(val keyword: String, val before: Int, val after: Int)
    data class NodeChange(val from: ObservedNode, val to: ObservedNode)
    data class GoalProgress(
        /** changed | unknown. Node changes and keyword counts never establish goal progress. */
        val status: String,
        val added: List<ObservedNode>, val removed: List<ObservedNode>, val changed: List<NodeChange>,
        val keywordHits: List<KeywordHit>, val note: String,
    ) {
        fun toJson(): JSONObject = JSONObject().put("status", status)
            .put("added", JSONArray(added.take(MAX_DIFF_NODES).map { it.toJson() }))
            .put("removed", JSONArray(removed.take(MAX_DIFF_NODES).map { it.toJson() }))
            .put("changed", JSONArray(changed.take(MAX_DIFF_NODES).map { JSONObject().put("from", it.from.toJson()).put("to", it.to.toJson()) }))
            .put("added_count", added.size).put("removed_count", removed.size).put("changed_count", changed.size)
            .put("diff_truncated", added.size > MAX_DIFF_NODES || removed.size > MAX_DIFF_NODES || changed.size > MAX_DIFF_NODES)
            .put("keyword_hits", JSONArray(keywordHits.take(16).map {
                JSONObject().put("keyword", it.keyword.take(64)).put("before", it.before).put("after", it.after)
            })).put("note", note).put("goal_verified", false)
    }

    fun computeGoalProgress(
        before: List<ObservedNode>?, after: List<ObservedNode>?, keywords: List<String>, afterTruncated: Boolean,
        beforeTruncated: Boolean = false, nodesStable: Boolean = true,
    ): GoalProgress {
        fun unknown(note: String) = GoalProgress("unknown", emptyList(), emptyList(), emptyList(), emptyList(), note)
        if (before.isNullOrEmpty() || after.isNullOrEmpty() || afterTruncated || beforeTruncated || !nodesStable ||
            before.size > MAX_MAX_NODES || after.size > MAX_MAX_NODES) {
            return unknown("动作前后缺少完整、稳定且非空的可比节点树；目标结果未知，请重新观察。")
        }
        // Repeated view IDs with different values cannot be paired by array order reliably.
        fun ambiguous(nodes: List<ObservedNode>) = nodes.filter { it.viewId.isNotBlank() }
            .groupBy { it.packageName + "|" + it.viewId }.values.any { group -> group.size > 1 && group.map { it.signature }.distinct().size > 1 }
        if (ambiguous(before) || ambiguous(after)) return unknown("重复节点身份无法可靠配对，界面变化和目标结果均未知。")
        val oldKeys = keyed(before)
        val newKeys = keyed(after)
        val added = newKeys.filterKeys { it !in oldKeys }.values.toList()
        val removed = oldKeys.filterKeys { it !in newKeys }.values.toList()
        val changed = oldKeys.keys.intersect(newKeys.keys).mapNotNull { key ->
            val old = oldKeys.getValue(key)
            val new = newKeys.getValue(key)
            if (old.signature == new.signature) null else NodeChange(old, new)
        }
        val hits = keywords.asSequence().filter { it.isNotBlank() }.map { it.take(64) }.distinct().take(16).map { keyword ->
            KeywordHit(keyword, before.count { it.hit(keyword) }, after.count { it.hit(keyword) })
        }.toList()
        val moved = added.isNotEmpty() || removed.isNotEmpty() || changed.isNotEmpty()
        return GoalProgress(if (moved) "changed" else "unknown", added, removed, changed, hits,
            if (moved) "已观察到节点变化；关键词计数仅为线索，目标完成状态未知。"
            else "关键节点未观察到变化；目标完成状态未知，请依据任务结果核实。")
    }

    fun ObservedNode.hit(keyword: String): Boolean = text.contains(keyword, true) || desc.contains(keyword, true)
    fun keyed(nodes: List<ObservedNode>): Map<String, ObservedNode> {
        val counter = mutableMapOf<String, Int>()
        return nodes.associate { node ->
            val base = node.viewId.takeIf { it.isNotBlank() }
                ?: node.text.takeIf { it.isNotBlank() }?.let { "T:$it" }
                ?: node.desc.takeIf { it.isNotBlank() }?.let { "D:$it" }
                ?: node.className.takeIf { it.isNotBlank() }?.let { "C:$it" } ?: "?"
            val key = node.packageName + "|" + base
            val occurrence = counter.getOrDefault(key, 0)
            counter[key] = occurrence + 1
            "$key#$occurrence" to node
        }
    }
    fun visualHintNote(): String = "UI 树为空或被截断，当前界面可能以自绘控件为主。可依据附带截图用 normalized 归一化坐标（0–999）操作；" +
        "locate_on_screen 仅匹配可读节点文字与描述，缺少节点时返回 unsupported。截图 OCR/视觉解析尚未实现，文字与状态均按 unknown 处理。"
}
