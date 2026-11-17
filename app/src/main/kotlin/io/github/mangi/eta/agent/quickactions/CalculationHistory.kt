package io.github.mangi.eta.agent.quickactions

import java.util.ArrayDeque

/**
 * 计算历史栈：保存最近若干次计算的数字结果（字符串形式，保持 SimpleCalculator 的规范化输出），
 * 供「刚才那个计算再加5 / 再减3」这类追算复用。纯 JVM 对象，有界，不持有 Context。
 */
internal object CalculationHistory {
    internal const val MAX_ENTRIES = 20

    private val stack = ArrayDeque<String>()

    /** 内部可见，便于测试重置。 */
    internal fun clear() = stack.clear()

    internal fun push(value: String) {
        if (value.isBlank()) return
        stack.addLast(value)
        while (stack.size > MAX_ENTRIES) stack.removeFirst()
    }

    internal fun last(): String? = stack.lastOrNull()

    internal fun size(): Int = stack.size

    /** 把上次结果与操作数拼成完整表达式，交给 SimpleCalculator 统一求值。 */
    internal fun expressionFor(op: CalcFollowOp, operand: String): String? {
        val previous = last() ?: return null
        val raw = operand.trim()
        if (raw.isEmpty()) return null
        // 同时接受阿拉伯数字（含小数）与中文整数，统一规范化为十进制文本。
        val clean = if (raw.all { it.isDigit() || it == '.' }) {
            raw
        } else {
            ChineseDailyNumbers.parse(raw)?.toString() ?: return null
        }
        val symbol = when (op) {
            CalcFollowOp.ADD -> "+"
            CalcFollowOp.SUBTRACT -> "-"
        }
        // 上次结果可能是负数，加括号保证 "-(3)+5" 之类不发生符号歧义。
        return "($previous)$symbol$clean"
    }
}
