package io.github.mangi.eta.ui.skills

import io.github.mangi.eta.ui.model.SKILL_FEEDBACK_COMMENT_MAX
import io.github.mangi.eta.ui.model.sanitizeSkillFeedbackComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.3 评分反馈闭环的评论脱敏/限长纯 JVM 测试。
 *
 * 不依赖 Android/Room，避免 linux-aarch64 下 Robolectric 被 skip 而失去覆盖。
 */
class SkillFeedbackCommentTest {

    @Test
    fun trimsAndFoldsControlCharactersIntoSingleSpaces() {
        val result = sanitizeSkillFeedbackComment("  第一行\n第二行\t第三行\r第四行  ")
        assertEquals("第一行 第二行 第三行 第四行", result)
    }

    @Test
    fun dropsNonPrintableCharacters() {
        val result = sanitizeSkillFeedbackComment("好\u0000用\u007F!")
        assertEquals("好用!", result)
    }

    @Test
    fun limitsToMaxChars() {
        val result = sanitizeSkillFeedbackComment("x".repeat(900))
        assertEquals(SKILL_FEEDBACK_COMMENT_MAX, result.length)
        assertTrue(result.all { it == 'x' })
    }

    @Test
    fun blankCommentStaysEmpty() {
        assertEquals("", sanitizeSkillFeedbackComment("   \n\t  "))
    }
}
