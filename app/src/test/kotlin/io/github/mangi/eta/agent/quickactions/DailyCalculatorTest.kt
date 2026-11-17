package io.github.mangi.eta.agent.quickactions

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyCalculatorTest {
    private val now = Instant.parse("2026-11-16T06:20:00Z")

    @Test fun decimalPrecedenceAndParenthesesProduceVerifiedTypedAnswers() {
        val examples = listOf(
            "1+2" to "3",
            "12*3" to "36",
            "(2+3)*4" to "20",
            "2+3*4" to "14",
            "(2+3)*(4-1)" to "15",
            "0.1+0.2" to "0.3",
            "1.25-0.75" to "0.5",
            "2.5*4" to "10",
            "10/4" to "2.5",
            "10%3" to "1",
            "5.5%2" to "1.5",
            "-7%3" to "-1",
            "-2*-3" to "6",
            "-(2+3)" to "-5",
            "+(2-5)" to "-3",
            "1--2" to "3",
            "8/4/2" to "1",
            "8-3-2" to "3",
            ".5 + .25" to "0.75",
            "2." to "2",
            "0001.2000+0.8" to "2",
            "1000*1000" to "1000000",
            "-0.0" to "0",
            "1/3" to "0.3333333333333333333333333333333333",
            "2/3" to "0.6666666666666666666666666666666667",
            "0.0000000000000000000000000000000001" to "0.0000000000000000000000000000000001",
            "9".repeat(34) to "9".repeat(34),
            "1/9999999999999999999999999999999999" to "0.0000000000000000000000000000000001",
        )
        examples.forEach { (expression, expected) ->
            assertEquals(expression, SimpleCalculator.Result.Answer(expected), SimpleCalculator.evaluate(expression))
            val request = DailyIntent.Calculate(expression)
            val result = QuickActionsExecutor.executeCalculation(request, now)
            assertTrue(expression, result is DailyCommandResult.CalculationAnswer)
            assertEquals(request, result.request)
            assertEquals("本地计算", result.sourceLabel)
            assertEquals("计算结果", result.title)
            assertEquals(now, result.asOf)
            assertTrue(result.verified)
            assertEquals(expected, result.fields.single { it.label == "结果" }.value)
        }
    }

    @Test fun malformedExpressionsAndZeroDivisorsReturnTypedFailures() {
        val examples = listOf(
            "", " ", ".", "1+", "*2", "()", "(1+2", "1+2)", "2(3+4)", "1 2", "1..2",
            "1,2", "1e3", "2^3", "NaN", "Infinity", "Math.sqrt(4)", "1;2", "1\n+2", "2//2",
            "1/0", "0/0", "4%0", "1/(2-2)",
        )
        examples.forEach { expression ->
            val result = QuickActionsExecutor.executeCalculation(DailyIntent.Calculate(expression), now)
            assertTrue(expression, result is DailyCommandResult.Failure)
            assertFalse(expression, result.verified)
            assertEquals("本地计算", result.sourceLabel)
            assertTrue(expression, result.summary.isNotBlank())
        }
        listOf("1/0", "4%0", "1/(2-2)").forEach { expression ->
            assertEquals("DIVISION_BY_ZERO",
                (SimpleCalculator.evaluate(expression) as SimpleCalculator.Result.Failure).code)
        }
    }

    @Test fun expressionTokenAndIntermediateNumberBoundsFailClosed() {
        val atTokenLimit = "+".repeat(127) + "1"
        assertEquals(SimpleCalculator.Result.Answer("1"), SimpleCalculator.evaluate(atTokenLimit))
        assertEquals(SimpleCalculator.Result.Answer("1"), SimpleCalculator.evaluate("1" + " ".repeat(255)))
        val failures = listOf(
            "+".repeat(128) + "1" to "TOO_MANY_TOKENS",
            "1" + " ".repeat(256) to "EXPRESSION_TOO_LONG",
            "9".repeat(35) to "CALCULATION_OVERFLOW",
            "9".repeat(34) + "+1" to "CALCULATION_OVERFLOW",
            "0." + "0".repeat(34) + "1" to "CALCULATION_OVERFLOW",
            "99999999999999999999*99999999999999999999" to "CALCULATION_OVERFLOW",
            "1/8000000000000000000000000000000000" to "CALCULATION_OVERFLOW",
            "((1+2)*" + "9".repeat(34) + ")-1" to "CALCULATION_OVERFLOW",
        )
        failures.forEach { (expression, code) ->
            val result = QuickActionsExecutor.executeCalculation(DailyIntent.Calculate(expression), now)
            assertTrue(expression, result is DailyCommandResult.Failure)
            assertEquals(expression, code, (result as DailyCommandResult.Failure).code)
        }
    }
}
