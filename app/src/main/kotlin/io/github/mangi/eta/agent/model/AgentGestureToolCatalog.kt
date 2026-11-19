package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 屏幕手势与节点交互工具 schema。 */
internal object AgentGestureToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "tap",
                    description = "点击坐标。能用节点时优先 tap_element；看截图定位时用 coordinate_space=normalized（0–999），坐标来自 ui_nodes 时用 screen。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                                .put("observation_id", JSONObject().put("type", "string").put("description", "坐标来源的最新观察 ID；tap_area 必填，防止重放旧候选框。"))
                                .put("display_id", JSONObject().put("type", "integer").put("description", "坐标来源的 display_id；须与当前屏幕匹配。"))
                        )
                        .put("required", JSONArray().put("x").put("y").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_smart",
                    description = "智能点击：给 text 或 desc，先按无障碍节点点击；节点点不动时自动改用该节点中心坐标点击；再不行按传入 x/y 点击。灵屏任务优先用这个工具，比盲点坐标准。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("text", JSONObject().put("type", "string").put("description", "目标控件上的可见文字，包含匹配即可。"))
                                .put("desc", JSONObject().put("type", "string").put("description", "目标控件的 content-desc，包含匹配即可。"))
                                .put("x", JSONObject().put("type", "integer").put("description", "兜底坐标：节点点击都失败时才使用。"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                                .put("observation_id", JSONObject().put("type", "string").put("description", "坐标来源的最新观察 ID；tap_area 必填，防止重放旧候选框。"))
                                .put("display_id", JSONObject().put("type", "integer").put("description", "坐标来源的 display_id；须与当前屏幕匹配。"))
                                .put("max_nodes", JSONObject().put("type", "integer").put("description", "本次最多读取的节点数，默认 60。"))
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_area",
                    description = "点击矩形区域中心。大按钮、大列表项和可见文字区域优先用这个工具；坐标系同 tap，必须显式填写 coordinate_space。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                                .put("observation_id", JSONObject().put("type", "string").put("description", "坐标来源的最新观察 ID；tap_area 必填，防止重放旧候选框。"))
                                .put("display_id", JSONObject().put("type", "integer").put("description", "坐标来源的 display_id；须与当前屏幕匹配。"))
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2").put("coordinate_space").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "locate_on_screen",
                    description = "在最近一次已采集的 ui_nodes 中按文字或 content-desc 匹配目标。返回候选节点的 normalized bbox、observation_id、display scope 和文本匹配 confidence。它是节点检索，不是 OmniParser、OCR 或截图视觉理解；截图缺少可读节点时明确 unsupported。调用时携带当前 observation_id；tap_area 使用同一 observation_id 与 display_id，新观察或屏幕切换后重新定位。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "target",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 256)
                                        .put("description", "目标节点的实际文字或 content-desc，例如『保存』；颜色、图像等视觉特征不参与匹配。")
                                )
                                .put(
                                    "max_candidates",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1).put("maximum", 8)
                                        .put("description", "最多返回几个候选框，默认 3，范围 1 到 8")
                                )
                        )
                        .apply {
                            getJSONObject("properties")
                                .put("observation_id", JSONObject().put("type", "string").put("description", "最近一次 observe_screen 的 observation_id。"))
                                .put("display_id", JSONObject().put("type", "integer").put("description", "可选，须与观察返回的 display_id 相同。"))
                        }
                        .put("required", JSONArray().put("target").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_element",
                    description = "点击指定观察快照中的 UI 节点。index 与 observation_id 必须来自同一次最近的 observe_screen；若观察已过期，先重新观察。Runtime 会在执行前确认 Eta 无障碍服务已经连接。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "同一次 observe_screen 返回的 UI 节点 index。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "与 index 来自同一次最近 observe_screen 的 observation_id。")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "long_press",
                    description = "长按坐标。能用节点时优先 long_press_element；坐标系同 tap，必须显式填写 coordinate_space。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "长按时长，300 到 3000，默认 800")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                                .put("observation_id", JSONObject().put("type", "string").put("description", "坐标来源的最新观察 ID；tap_area 必填，防止重放旧候选框。"))
                                .put("display_id", JSONObject().put("type", "integer").put("description", "坐标来源的 display_id；须与当前屏幕匹配。"))
                        )
                        .put("required", JSONArray().put("x").put("y").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "long_press_element",
                    description = "长按指定观察快照中的 UI 节点。index 与 observation_id 必须来自同一次最近的 observe_screen；若观察已过期，先重新观察。Runtime 会在执行前确认 Eta 无障碍服务已经连接。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "同一次 observe_screen 返回的 UI 节点 index。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "与 index 来自同一次最近 observe_screen 的 observation_id。")
                                )
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "长按时长，300 到 3000，默认 800")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "swipe",
                    description = "从一个坐标拖到另一个坐标（手指方向），用于拖动滑块、轮播、地图等精确手势。浏览列表请用 scroll。坐标系同 tap，必须显式填写 coordinate_space。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "滑动时长，100 到 2000，默认 500")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                                .put("observation_id", JSONObject().put("type", "string").put("description", "坐标来源的最新观察 ID；tap_area 必填，防止重放旧候选框。"))
                                .put("display_id", JSONObject().put("type", "integer").put("description", "坐标来源的 display_id；须与当前屏幕匹配。"))
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2").put("coordinate_space").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "scroll",
                    description = "按内容浏览方向滚动当前屏幕的主列表：down 显示下方内容，up 显示上方内容，left/right 同理。结果里 moved=false 且 at_boundary=true 表示已到底，换方向或结束；页面有多个列表时用 scroll_element 指定目标。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                )
                                .put(
                                    "amount",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("page").put("small"))
                                        .put("description", "page 约翻一屏（默认），small 只露出少量新内容，用于把目标项微调进视野。"),
                                )
                        )
                        .put("required", JSONArray().put("direction"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "scroll_element",
                    description = "按内容浏览方向滚动指定观察快照中的可滚动 UI 节点：down 显示下方内容，up 显示上方内容，left 显示左侧内容，right 显示右侧内容。index 与 observation_id 必须来自同一次最近的 observe_screen；若观察已过期，先重新观察。Runtime 会在执行前确认 Eta 无障碍服务已经连接。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "同一次 observe_screen 返回的可滚动 UI 节点 index。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "与 index 来自同一次最近 observe_screen 的 observation_id。")
                                )
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                        .put("description", "内容浏览方向；down 显示下方内容，up 显示上方内容。")
                                )
                                .put(
                                    "amount",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("page").put("small"))
                                        .put("description", "page 约翻一屏（默认），small 只露出少量新内容，用于把目标项微调进视野。"),
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id").put("direction"))
                )
            )
    }
}
