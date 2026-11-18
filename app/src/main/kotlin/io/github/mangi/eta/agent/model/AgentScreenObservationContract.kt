package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 屏幕观察工具在模型 schema、执行器与运行轨迹之间共享的默认合同。 */
internal object AgentScreenObservationContract {
    const val DEFAULT_INCLUDE_SCREENSHOT = false
    const val DEFAULT_INCLUDE_UI_TREE = true
    const val DEFAULT_MAX_NODES = 60
    const val MIN_MAX_NODES = 1
    const val MAX_MAX_NODES = 120
    const val DEFAULT_INCLUDE_VISUAL_HINT = true

    data class Options(
        val includeScreenshot: Boolean,
        val includeUiTree: Boolean,
        val maxNodes: Int,
        val includeVisualHint: Boolean = DEFAULT_INCLUDE_VISUAL_HINT,
    )

    fun resolve(arguments: JSONObject): Options = Options(
        includeScreenshot = arguments.optBoolean(
            "include_screenshot",
            DEFAULT_INCLUDE_SCREENSHOT,
        ),
        includeUiTree = arguments.optBoolean(
            "include_ui_tree",
            DEFAULT_INCLUDE_UI_TREE,
        ),
        maxNodes = arguments.optInt("max_nodes", DEFAULT_MAX_NODES),
        includeVisualHint = arguments.optBoolean(
            "include_visual_hint",
            DEFAULT_INCLUDE_VISUAL_HINT,
        ),
    )

    /**
     * 1.1 动作后目标级验证：ui_nodes 里与"进度"相关的五个关键字段的纯 JVM 投影。
     * 不依赖 Android（Rect/Context），因此可在单元轨迹测试里直接构造与比对。
     */
    data class ObservedNode(
        val viewId: String = "",
        val text: String = "",
        val desc: String = "",
        val checked: Boolean? = null,
        val enabled: Boolean = true,
        val className: String = "",
        val packageName: String = "",
    ) {
        /** 动作前后用于判"变没变"的五元组签名：(viewId,text,desc,checked,enabled)。 */
        val signature: String
            get() = listOf(
                viewId, text, desc,
                checked?.toString().orEmpty(),
                enabled.toString(),
            ).joinToString("\u001f")

        fun toJson(): JSONObject = JSONObject()
            .put("view_id", viewId)
            .put("text", text)
            .put("desc", desc)
            .put("checked", checked ?: JSONObject.NULL)
            .put("enabled", enabled)
    }

    data class KeywordHit(val keyword: String, val before: Int, val after: Int)

    data class NodeChange(val from: ObservedNode, val to: ObservedNode)

    data class GoalProgress(
        /** progress | no_change | regressed | unknown。unknown 表示节点不稳，绝不猜成功。 */
        val status: String,
        val added: List<ObservedNode>,
        val removed: List<ObservedNode>,
        val changed: List<NodeChange>,
        val keywordHits: List<KeywordHit>,
        val note: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("status", status)
            .put("added", JSONArray(added.map { it.toJson() }))
            .put("removed", JSONArray(removed.map { it.toJson() }))
            .put(
                "changed", JSONArray(changed.map { change ->
                    JSONObject()
                        .put("from", change.from.toJson())
                        .put("to", change.to.toJson())
                }),
            )
            .put(
                "keyword_hits", JSONArray(keywordHits.map { hit ->
                    JSONObject()
                        .put("keyword", hit.keyword)
                        .put("before", hit.before)
                        .put("after", hit.after)
                }),
            )
            .put("note", note)
    }

    /**
     * 对比动作前后节点集合。无基线 / 树为空 / 树被截断时如实返回 unknown，不猜成功。
     * 身份键优先 viewId，再 text、desc、className；同键重复按出现序号区分，避免列表项误配。
     */
    fun computeGoalProgress(
        before: List<ObservedNode>?,
        after: List<ObservedNode>?,
        keywords: List<String>,
        afterTruncated: Boolean,
    ): GoalProgress {
        if (before == null || after == null || afterTruncated || after.isEmpty()) {
            return GoalProgress(
                "unknown", emptyList(), emptyList(), emptyList(), emptyList(),
                "动作后没有可比对的有效节点树（缺失、为空或被截断），无法判断目标进度；请重新观察，不要假定成功。",
            )
        }
        val beforeKeys = keyed(before)
        val afterKeys = keyed(after)
        val added = afterKeys.keys.filterNot(beforeKeys::containsKey).map(afterKeys::getValue)
        val removed = beforeKeys.keys.filterNot(afterKeys::containsKey).map(beforeKeys::getValue)
        val changed = beforeKeys.keys.intersect(afterKeys.keys).mapNotNull { key ->
            val old = beforeKeys.getValue(key)
            val new = afterKeys.getValue(key)
            if (old.signature != new.signature) NodeChange(old, new) else null
        }
        val hits = keywords.filter { it.isNotBlank() }.distinct().map { keyword ->
            KeywordHit(
                keyword,
                before.count { it.hit(keyword) },
                after.count { it.hit(keyword) },
            )
        }
        val beforeHits = hits.sumOf { it.before }
        val afterHits = hits.sumOf { it.after }
        val moved = added.isNotEmpty() || removed.isNotEmpty() || changed.isNotEmpty()
        val (status, note) = when {
            hits.isNotEmpty() && afterHits > beforeHits ->
                "progress" to "目标关键词在动作后命中更多节点，界面朝目标推进。"
            hits.isNotEmpty() && afterHits < beforeHits ->
                "regressed" to "目标关键词命中减少，可能偏离目标；请重新观察确认。"
            moved ->
                "progress" to "节点集合发生变化（新增/移除/状态改变），但关键词命中未增加；仅表示界面有变化，不等于目标已达成。"
            else ->
                "no_change" to "动作前后关键节点没有变化，动作可能未生效；请换目标或方式，不要原样重复。"
        }
        return GoalProgress(status, added, removed, changed, hits, note)
    }

    fun ObservedNode.hit(keyword: String): Boolean =
        text.contains(keyword, true) || desc.contains(keyword, true)

    fun keyed(nodes: List<ObservedNode>): Map<String, ObservedNode> {
        val counter = mutableMapOf<String, Int>()
        return nodes.associate { node ->
            val base = node.viewId.takeIf { it.isNotBlank() }
                ?: node.text.takeIf { it.isNotBlank() }?.let { "T:$it" }
                ?: node.desc.takeIf { it.isNotBlank() }?.let { "D:$it" }
                ?: node.className.takeIf { it.isNotBlank() }?.let { "C:$it" }
                ?: "?"
            val key = node.packageName + "|" + base
            val occurrence = counter.getOrDefault(key, 0)
            counter[key] = occurrence + 1
            "$key#$occurrence" to node
        }
    }

    /** 1.2 a11y 空/截断树且有截图时的视觉回退提示：只提示坐标，不做 OCR、不猜文字。 */
    fun visualHintNote(): String =
        "UI 树为空或被截断，当前界面可能以自绘控件为主。可依据附带的截图用 normalized 归一化坐标（0–999）" +
            "通过 tap/tap_area 操作；没有 OCR 结果，控件文字与状态一律按 unknown 处理，不要猜测文本内容。"
}
