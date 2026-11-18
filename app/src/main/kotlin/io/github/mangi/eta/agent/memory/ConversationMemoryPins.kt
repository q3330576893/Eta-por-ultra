package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot

/**
 * 记忆上下文按会话固定，保证系统提示在多轮对话间逐字节不变。
 *
 * 记忆位于系统提示中，而系统提示是请求前缀的开头：每次写入都重建它，会让该会话此前的
 * 全部历史失去缓存。本会话自己写入的内容已经以工具调用的形式留在历史里，模型无需从系统
 * 提示再看一遍，因此只要记忆没有被其他来源（别的会话、用户在设置页编辑）改动，就继续沿用
 * 首次注入的快照；被外部改动或上下文被压缩（历史里的写入可能已被摘要掉）时才重新注入。
 *
 * 只在进程内保存：进程重启后首轮重新注入一次，代价是一次缓存未命中，不影响正确性；
 * 写入仍以 revision 做乐观并发校验，沿用旧快照不会覆盖他处的修改。
 *
 * 4.3 扩展：同一会话还固定一组 KB pin（文档知识库引用），只保存引用与分数、不常驻正文，
 * 随记忆快照一起按会话缓存。KB pin 为空时不改变既有返回对象，保持缓存命中语义不变。
 */
internal object ConversationMemoryPins {
    private class Pin(
        val context: AgentMemoryContext,
        val contextWindow: Int?,
        var knownRevision: String,
        val kbPins: LinkedHashMap<String, AgentKbPin> = LinkedHashMap(),
    )

    private const val MAX_PINS = 32
    private const val MAX_KB_PINS_PER_CONVERSATION = 16

    private val pins = object : LinkedHashMap<String, Pin>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pin>?): Boolean = size > MAX_PINS
    }

    @Synchronized
    fun contextFor(conversationId: String?, snapshot: AgentMemorySnapshot, contextWindow: Int?): AgentMemoryContext {
        if (conversationId != null) {
            val pin = pins[conversationId]
            if (pin != null && pin.knownRevision == snapshot.revision && pin.contextWindow == contextWindow) {
                // KB pin 为空时返回原对象，沿用缓存命中的 assertSame 语义；
                // 非空时把固定引用投影进上下文副本，供提示词/预览读取。
                return if (pin.kbPins.isEmpty()) pin.context else pin.context.copy(kbPins = pin.kbPins.values.toList())
            }
        }
        val context = AgentMemoryContextBuilder.build(snapshot, contextWindow)
        if (conversationId != null) {
            val pin = Pin(context, contextWindow, snapshot.revision)
            pins[conversationId] = pin
            return if (pin.kbPins.isEmpty()) context else context.copy(kbPins = pin.kbPins.values.toList())
        }
        return context
    }

    /** 本会话的 memory_write 成功后调用：这次变化已在历史中，不算外部改动。 */
    @Synchronized
    fun recordOwnWrite(conversationId: String?, revision: String) {
        if (conversationId == null || revision.isBlank()) return
        pins[conversationId]?.knownRevision = revision
    }

    /** 把一个 KB 命中钉进本会话；同 docId 重复钉住时保留分数更高者。 */
    @Synchronized
    fun pinKb(conversationId: String?, pin: AgentKbPin): Boolean {
        if (conversationId == null || pin.docId.isBlank()) return false
        val session = pins[conversationId] ?: return false
        val existing = session.kbPins[pin.docId]
        if (existing != null && existing.score >= pin.score) return false
        if (existing == null && session.kbPins.size >= MAX_KB_PINS_PER_CONVERSATION) return false
        session.kbPins[pin.docId] = pin
        return true
    }

    /** 取消本会话的一个 KB pin。 */
    @Synchronized
    fun unpinKb(conversationId: String?, docId: String): Boolean =
        conversationId != null && pins[conversationId]?.kbPins?.remove(docId) != null

    /** 读取当前会话已固定的 KB 引用（副本）。 */
    @Synchronized
    fun kbPinsFor(conversationId: String?): List<AgentKbPin> =
        if (conversationId == null) emptyList() else pins[conversationId]?.kbPins?.values?.toList().orEmpty()

    @Synchronized
    fun forget(conversationId: String?) {
        if (conversationId != null) pins.remove(conversationId)
    }
}
