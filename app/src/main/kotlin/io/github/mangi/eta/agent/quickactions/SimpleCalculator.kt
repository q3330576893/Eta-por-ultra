package io.github.mangi.eta.agent.quickactions

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Decimal arithmetic only; every token and intermediate value has a fixed bound. */
internal object SimpleCalculator {
    const val MAX_EXPRESSION_LENGTH = 256
    const val MAX_TOKENS = 128
    private const val MAX_DIGITS = 34
    private const val MAX_SCALE = 34
    private val arithmetic = MathContext(MAX_DIGITS, RoundingMode.HALF_EVEN)

    sealed interface Result {
        data class Answer(val value: String) : Result
        data class Failure(val code: String, val message: String) : Result
    }

    /** Full-string allowlist, also used before routing a natural-language calculation. */
    fun hasExpressionAlphabet(expression: String): Boolean = expression.all {
        it in '0'..'9' || it in ".+-*/%()" || it == ' ' || it == '\t'
    }

    fun evaluate(expression: String): Result = try {
        if (expression.length > MAX_EXPRESSION_LENGTH) fail("EXPRESSION_TOO_LONG", "计算式最多支持 256 个字符。")
        if (expression.isBlank() || !hasExpressionAlphabet(expression)) invalid()
        val value = Parser(tokenize(expression)).parse()
        Result.Answer(value.toPlainString())
    } catch (failure: CalculationException) {
        Result.Failure(failure.code, failure.message.orEmpty())
    } catch (_: ArithmeticException) {
        Result.Failure("CALCULATION_OVERFLOW", "计算超出 34 位有效数字或小数位的范围。")
    }

    private data class Token(val symbol: Char, val number: BigDecimal? = null)

    private fun tokenize(expression: String): List<Token> {
        val tokens = ArrayList<Token>()
        var index = 0
        while (index < expression.length) {
            val symbol = expression[index]
            if (symbol == ' ' || symbol == '\t') { index++; continue }
            if (tokens.size == MAX_TOKENS) fail("TOO_MANY_TOKENS", "计算式最多支持 128 个数字或运算符。")
            if (symbol in '0'..'9' || symbol == '.') {
                val start = index
                var decimalPoints = 0
                var digits = 0
                while (index < expression.length && (expression[index] in '0'..'9' || expression[index] == '.')) {
                    if (expression[index] == '.') decimalPoints++ else digits++
                    index++
                }
                if (decimalPoints > 1 || digits == 0) invalid()
                val value = BigDecimal(expression.substring(start, index))
                if (value.precision() > MAX_DIGITS || value.scale() > MAX_SCALE) overflow()
                tokens += Token('n', checked(value))
            } else {
                tokens += Token(symbol)
                index++
            }
        }
        if (tokens.isEmpty()) invalid()
        return tokens
    }

    private class Parser(private val tokens: List<Token>) {
        private var index = 0

        fun parse(): BigDecimal {
            val result = sum()
            if (index != tokens.size) invalid()
            return checked(result)
        }

        private fun sum(): BigDecimal {
            var value = product()
            while (peek() == '+' || peek() == '-') {
                val operation = tokens[index++].symbol
                val right = product()
                value = checked(if (operation == '+') value.add(right) else value.subtract(right))
            }
            return value
        }

        private fun product(): BigDecimal {
            var value = unary()
            while (peek() == '*' || peek() == '/' || peek() == '%') {
                val operation = tokens[index++].symbol
                val right = unary()
                if (operation != '*' && right.signum() == 0) {
                    fail("DIVISION_BY_ZERO", "除零或对零取余无效，请修改除数。")
                }
                value = checked(when (operation) {
                    '*' -> value.multiply(right)
                    '/' -> value.divide(right, arithmetic)
                    else -> value.remainder(right)
                })
            }
            return value
        }

        private fun unary(): BigDecimal = when (peek()) {
            '+' -> { index++; unary() }
            '-' -> { index++; checked(unary().negate()) }
            else -> primary()
        }

        private fun primary(): BigDecimal {
            if (peek() == '(') {
                index++
                val value = sum()
                if (peek() != ')') invalid()
                index++
                return value
            }
            val token = tokens.getOrNull(index) ?: invalid()
            val value = token.number ?: invalid()
            index++
            return value
        }

        private fun peek(): Char? = tokens.getOrNull(index)?.symbol
    }

    private fun checked(value: BigDecimal): BigDecimal {
        if (value.signum() == 0) return BigDecimal.ZERO
        val normalized = value.stripTrailingZeros()
        val integerDigits = normalized.precision() - normalized.scale()
        if (normalized.precision() > MAX_DIGITS || normalized.scale() > MAX_SCALE || integerDigits > MAX_DIGITS) overflow()
        return normalized
    }

    private fun invalid(): Nothing = fail("INVALID_EXPRESSION", "请输入只含十进制数字、加减乘除、取余和括号的完整计算式。")
    private fun overflow(): Nothing = fail("CALCULATION_OVERFLOW", "计算超出 34 位有效数字或小数位的范围。")
    private fun fail(code: String, message: String): Nothing = throw CalculationException(code, message)
    private class CalculationException(val code: String, message: String) : RuntimeException(message)
}
