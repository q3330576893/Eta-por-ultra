package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Locates only observed text/content descriptions; this is neither OCR nor an image parser. */
internal object ScreenNodeLocator {
    const val MAX_AGE_MS = 15_000L
    data class Node(val index: Int, val text: String, val desc: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val password: Boolean = false)
    data class Snapshot(val id: String, val displayId: Int, val sessionId: String, val width: Int, val height: Int,
        val nodes: List<Node>, val capturedAtMs: Long, val truncated: Boolean = false)

    fun locate(snapshot: Snapshot?, args: JSONObject, nowMs: Long): JSONObject {
        fun failure(code: String, message: String) = JSONObject().put("ok", false).put("code", code).put("message", message)
        if (snapshot == null || snapshot.nodes.isEmpty()) return failure("LOCATE_UNSUPPORTED_NO_UI_NODES",
            "当前观察缺少可读 UI 节点；定位仅匹配已采集节点文字或描述，截图像素的 OCR/视觉定位尚未实现")
        if (args.optString("observation_id") != snapshot.id || snapshot.id.isBlank() ||
            nowMs < snapshot.capturedAtMs || nowMs - snapshot.capturedAtMs > MAX_AGE_MS) {
            return failure("STALE_OBSERVATION", "请先重新 observe_screen，再携带同一次 observation_id 定位")
        }
        if (snapshot.width <= 1 || snapshot.height <= 1 ||
            (args.has("display_id") && args.optInt("display_id", -1) != snapshot.displayId)) {
            return failure("DISPLAY_SCOPE_MISMATCH", "屏幕范围与本次观察不同，请重新观察目标屏幕")
        }
        val target = args.optString("target").trim()
        if (target.isEmpty() || target.length > 256) return failure("INVALID_ARGUMENT", "target 需为 1–256 个字符的节点文字或描述")
        val candidates = snapshot.nodes.take(120).asSequence().filter { !it.password }.mapNotNull { node ->
            val labels = listOf(node.text, node.desc).filter { it.isNotBlank() }
            val confidence = when {
                labels.any { it.equals(target, true) } -> 1.0
                labels.any { it.contains(target, true) } -> 0.85
                labels.any { it.length >= 2 && target.contains(it, true) } -> 0.65
                else -> return@mapNotNull null
            }
            val left = node.left.coerceIn(0, snapshot.width - 1)
            val top = node.top.coerceIn(0, snapshot.height - 1)
            val right = node.right.coerceIn(0, snapshot.width - 1)
            val bottom = node.bottom.coerceIn(0, snapshot.height - 1)
            if (left >= right || top >= bottom) return@mapNotNull null
            node to JSONObject().put("index", node.index).put("text", node.text.take(256)).put("desc", node.desc.take(256))
                .put("confidence", confidence).put("method", "ui_node_text")
                .put("bbox", JSONObject().put("x1", normalize(left, snapshot.width)).put("y1", normalize(top, snapshot.height))
                    .put("x2", normalize(right, snapshot.width)).put("y2", normalize(bottom, snapshot.height)))
        }.sortedWith(compareByDescending<Pair<Node, JSONObject>> { it.second.getDouble("confidence") }.thenBy { it.first.index })
            .take(args.optInt("max_candidates", 3).coerceIn(1, 8)).map { it.second }.toList()
        val scope = JSONObject().put("type", if (snapshot.displayId == 0) "primary_display" else "virtual_display")
            .put("display_id", snapshot.displayId).put("session_id", snapshot.sessionId)
        return JSONObject().put("ok", true).put("tool", "locate_on_screen").put("observation_id", snapshot.id)
            .put("display_id", snapshot.displayId).put("display_scope", scope).put("coordinate_space", "normalized")
            .put("candidates", JSONArray(candidates)).put("count", candidates.size).put("ui_tree_truncated", snapshot.truncated)
            .put("method", "ui_node_text").put("ocr", false)
            .put("note", "仅由本次已观察的节点文本/描述生成候选框；置信度是匹配强度。点击候选区域时携带同一 observation_id 与 display_id；新观察或屏幕切换后重新定位。")
    }
    private fun normalize(value: Int, dimension: Int): Int = (value.toLong() * 999 / (dimension - 1)).toInt().coerceIn(0, 999)
}
