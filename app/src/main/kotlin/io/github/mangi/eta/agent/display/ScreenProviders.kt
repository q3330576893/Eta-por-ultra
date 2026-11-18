package io.github.mangi.eta.agent.display

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject

/**
 * 1.4 观察/执行 Provider 接口化（借鉴 mobilerun）。
 *
 * 把 VirtualScreenUiTools 的 observe / dispatch 两个入口抽成内部接口，方便测试注入 fake、
 * 日后接视觉解析器时不动上层工具路由。默认实现就走现有 a11y 树 + VirtualDisplay，
 * 对外工具名与参数完全兼容；这里只做接口抽取，不改变任何行为。
 */
internal interface ScreenObservationProvider {
    fun observe(args: JSONObject, info: VirtualDisplayInfo): AgentModelClient.ToolResult
}

internal interface ScreenActionProvider {
    fun dispatch(
        name: String,
        args: JSONObject,
        info: VirtualDisplayInfo,
    ): AgentModelClient.ToolResult
}

/** 把既有私有实现适配为接口默认的函数式委托，行为与抽取前逐字一致。 */
internal class DelegatingScreenObservationProvider(
    private val delegate: (JSONObject, VirtualDisplayInfo) -> AgentModelClient.ToolResult,
) : ScreenObservationProvider {
    override fun observe(args: JSONObject, info: VirtualDisplayInfo): AgentModelClient.ToolResult =
        delegate(args, info)
}

internal class DelegatingScreenActionProvider(
    private val delegate: (String, JSONObject, VirtualDisplayInfo) -> AgentModelClient.ToolResult,
) : ScreenActionProvider {
    override fun dispatch(
        name: String,
        args: JSONObject,
        info: VirtualDisplayInfo,
    ): AgentModelClient.ToolResult = delegate(name, args, info)
}
