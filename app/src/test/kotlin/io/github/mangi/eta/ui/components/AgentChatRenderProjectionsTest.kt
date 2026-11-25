package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.app.AgentConversationRevisionReducer
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.MessageReferenceUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.latestContextUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染投影层的等价性 + 复用性测试。
 *
 * 每个 `*_matchesOriginal*` 用例把「改动前的原始实现」内联成参考实现，
 * 对同一批输入逐项比对结果，证明投影只改了开销、没改语义；
 * 每个 `*_reuses*` 用例断言未变化时返回同一实例，证明省掉了重建开销。
 */
class AgentChatRenderProjectionsTest {

    // ── collapsedPreview ─────────────────────────────────────────────

    @Test
    fun collapsedPreview_matchesRegexReplaceAndTake() {
        val samples = listOf(
            "",
            "   ",
            "单行文本",
            "多行\n文本\r\n带   连续   空格",
            "\t制表\u000B垂直\u000C换页\u000C和回车\r",
            "a".repeat(200),
            "前导空白 " + "字".repeat(80),
            "长空白" + " ".repeat(200) + "结尾",
        )
        for (sample in samples) {
            assertEquals(
                "sample=${sample.take(20)}",
                originalPreview(sample),
                collapsedPreview(sample),
            )
        }
    }

    @Test
    fun collapsedPreview_stopsAtLimitInsteadOfScanningWholeBody() {
        // 6 万字正文：只要前 60 字，结果与正则版一致（等价性由上一个用例保证）。
        val huge = "段落一\n\n" + "内容".repeat(30_000)
        assertEquals(originalPreview(huge), collapsedPreview(huge))
        assertEquals(PreviewLengthLimit, collapsedPreview(huge).length)
    }

    // ── projectVisibleMessages ───────────────────────────────────────

    @Test
    fun projectVisibleMessages_matchesOriginalFiltering() {
        val messages = listOf(
            UserMessageUi(id = "u1", content = "问题"),
            AgentMessageUi(id = "a1", content = "  "),
            AgentMessageUi(id = "a2", content = "回答"),
            ToolActivityMessageUi(
                id = "t1",
                toolName = "browser_use",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "q",
            ),
        )
        assertEquals(originalVisible(messages, null), projectVisibleMessages(messages, null, null).visible)
        assertEquals(originalVisible(messages, "a1"), projectVisibleMessages(messages, "a1", null).visible)
        assertEquals(originalVisible(messages, "a2"), projectVisibleMessages(messages, "a2", null).visible)
        assertEquals(
            originalVisible(messages, "missing"),
            projectVisibleMessages(messages, "missing", null).visible,
        )
    }

    @Test
    fun projectVisibleMessages_returnsUpstreamListWhenNothingDropped() {
        val messages = listOf(
            UserMessageUi(id = "u1", content = "问题"),
            AgentMessageUi(id = "a1", content = "回答"),
        )
        val first = projectVisibleMessages(messages, null, null)
        // 没有空白正文消息时直接指向上游列表：省掉一次 filterNot 全表拷贝。
        assertSame(messages, first.visible)

        // 流式追加一条：上游列表换了实例，但内容判定不变，仍然直接指向上游。
        val appended = messages + AgentMessageUi(id = "a2", content = "追加")
        val second = projectVisibleMessages(appended, null, first)
        assertSame(appended, second.visible)
    }

    @Test
    fun projectVisibleMessages_keepsFilteringWhenBlankAgentMessageAppears() {
        val messages = listOf(
            UserMessageUi(id = "u1", content = "问题"),
            AgentMessageUi(id = "a1", content = ""),
            AgentMessageUi(id = "a2", content = "回答"),
        )
        val projection = projectVisibleMessages(messages, null, null)
        assertEquals(originalVisible(messages, null), projection.visible)
        assertNotSame(messages, projection.visible)
        // 同样的输入再来一次也不能退化成「直接返回上游」（否则空白消息会漏出来）。
        val next = projectVisibleMessages(messages, null, projection)
        assertEquals(originalVisible(messages, null), next.visible)
        assertNotSame(messages, next.visible)
    }

    @Test
    fun projectVisibleMessages_reusesVisibleListWhenInstancesUnchanged() {
        val messages = listOf(
            UserMessageUi(id = "u1", content = "问题"),
            AgentMessageUi(id = "a1", content = "  "),
            AgentMessageUi(id = "a2", content = "回答"),
        )
        val first = projectVisibleMessages(messages, null, null)
        // 上游只换了列表包装，元素实例全同 ⇒ 过滤结果必须复用同一列表。
        val second = projectVisibleMessages(ArrayList(messages), null, first)
        assertSame(first.visible, second.visible)
        assertEquals(originalVisible(messages, null), second.visible)
    }

    // ── projectTimelineEntries ───────────────────────────────────────

    @Test
    fun projectTimelineEntries_matchesOriginalGrouping() {
        val thinking1 = ThinkingMessageUi(id = "think-1", content = "分析一", isStreaming = false)
        val thinking2 = ThinkingMessageUi(id = "think-2", content = "分析二", isStreaming = false)
        val tool = ToolActivityMessageUi(
            id = "tool-1",
            toolName = "browser_use",
            status = ToolActivityStatusUi.Success,
            argumentsSummary = "q",
        )
        val summary = ToolSummaryMessageUi(id = "summary-1", tools = listOf("browser_use"))
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "u1", content = "问题"),
            thinking1,
            tool,
            summary,
            AgentMessageUi(id = "a1", content = "回答"),
            UserMessageUi(id = "u2", content = "追问"),
            thinking2,
            AgentMessageUi(id = "a2", content = "回答 2"),
        )

        val expected = messages.toTimelineEntries()
        // 逐帧喂增量（每帧换掉一条消息实例），每一帧都要与原始分组结果一致。
        var previous: List<AgentTimelineEntry>? = null
        for (step in 0..messages.size) {
            val frame = messages.mapIndexed { index, message ->
                if (step > 0 && index == step - 1) {
                    (message as? AgentMessageUi)?.copy(content = "回答 v$step") ?: message
                } else {
                    message
                }
            }
            val actual = projectTimelineEntries(frame, previous)
            previous = actual
            assertEquals("step=$step keys", expected.map { it.key }, actual.map { it.key })
            assertEquals(
                "step=$step work sizes",
                expected.filterIsInstance<AgentTimelineEntry.WorkProcess>().map { it.messages.size },
                actual.filterIsInstance<AgentTimelineEntry.WorkProcess>().map { it.messages.size },
            )
        }
    }

    @Test
    fun projectTimelineEntries_keepsWorkProcessGroupWhenOnlyLaterTextStreams() {
        val thinking = ThinkingMessageUi(id = "think-1", content = "分析", isStreaming = false)
        val first: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "u1", content = "问题"),
            thinking,
            AgentMessageUi(id = "a1", content = "答", isStreaming = true),
        )
        val previous = projectTimelineEntries(first, null)

        val second: List<AgentChatMessageUi> = listOf(
            first[0],
            thinking,
            AgentMessageUi(id = "a1", content = "答案继续", isStreaming = true),
        )
        val next = projectTimelineEntries(second, previous)
        // 工作过程分组（think-1）成员实例全同 ⇒ 必须复用，分组不因正文流式而重组。
        assertSame(previous[1], next[1])
        assertEquals("work-think-1", next[1].key)
    }

    @Test
    fun projectTimelineEntries_reusesEveryUnchangedEntry() {
        val thinking = ThinkingMessageUi(id = "think-1", content = "分析", isStreaming = false)
        val first = listOf(
            UserMessageUi(id = "u1", content = "问题"),
            thinking,
            AgentMessageUi(id = "a1", content = "答", isStreaming = true),
        )
        val previous = projectTimelineEntries(first, null)

        // 只有流式正文追加：除 a1 外全部复用，a1 必须换成新条目。
        val second = listOf(
            first[0],
            thinking,
            AgentMessageUi(id = "a1", content = "答案", isStreaming = true),
        )
        val next = projectTimelineEntries(second, previous)
        assertSame(previous[0], next[0])
        assertSame(previous[1], next[1])
        assertNotSame(previous[2], next[2])
        assertEquals(
            "答案",
            when (val message = (next[2] as AgentTimelineEntry.Message).message) {
                is AgentMessageUi -> message.content
                is UserMessageUi -> message.content
                else -> error("unexpected message type")
            },
        )
    }

    @Test
    fun projectTimelineEntries_returnsSameListWhenNothingChanged() {
        val messages = listOf(
            UserMessageUi(id = "u1", content = "问题"),
            AgentMessageUi(id = "a1", content = "回答"),
        )
        val previous = projectTimelineEntries(messages, null)
        // 上游只换了列表包装（同实例元素），条目全部复用 ⇒ 返回同一列表实例，
        // LazyColumn items(items = ...) 拿到稳定引用。
        val next = projectTimelineEntries(ArrayList(messages), previous)
        assertSame(previous, next)
    }

    // ── projectFinalResultMessageIds ─────────────────────────────────

    @Test
    fun projectFinalResultMessageIds_matchesOriginalAcrossStreamingAppends() {
        // 模拟逐 token 追加：每一帧都要求与原始实现结果一致。
        var projection: FinalResultIdsProjection? = null
        var streamingMessages: List<AgentChatMessageUi> = listOf(UserMessageUi(id = "u1", content = "任务"))
        var content = ""
        for (step in 0..20) {
            content += "字"
            streamingMessages = listOf(
                streamingMessages[0],
                AgentMessageUi(id = "a1", content = content, isStreaming = true),
            )
            projection = projectFinalResultMessageIds(streamingMessages, true, projection)
            assertEquals(
                "step=$step",
                resolveFinalResultMessageIds(streamingMessages, isStreaming = true),
                projection!!.ids,
            )
        }
        // 收尾：流式结束，当前轮最终回答要出现。
        val settled = listOf(
            UserMessageUi(id = "u1", content = "任务"),
            AgentMessageUi(id = "a1", content = content, isStreaming = false),
        )
        val done = projectFinalResultMessageIds(settled, false, projection)
        assertEquals(resolveFinalResultMessageIds(settled, isStreaming = false), done.ids)
        assertEquals(setOf("a1"), done.ids)
    }

    @Test
    fun projectFinalResultMessageIds_matchesOriginalOnMultiTurnConversation() {
        val turns = (1..5).map { index ->
            listOf(
                UserMessageUi(id = "u$index", content = "问 $index"),
                AgentMessageUi(id = "a$index-1", content = "中间 $index"),
                toolActivity("t$index"),
                AgentMessageUi(id = "a$index-2", content = "答 $index"),
            )
        }.flatten()

        var projection: FinalResultIdsProjection? = null
        for (size in 1..turns.size) {
            val prefix = turns.subList(0, size)
            for (isStreaming in listOf(true, false)) {
                projection = projectFinalResultMessageIds(prefix, isStreaming, projection)
                assertEquals(
                    "size=$size streaming=$isStreaming",
                    resolveFinalResultMessageIds(prefix, isStreaming = isStreaming),
                    projection!!.ids,
                )
            }
        }
    }

    @Test
    fun projectFinalResultMessageIds_reusesSetInstanceWhileOnlyTailStreams() {
        val base = listOf(
            UserMessageUi(id = "u1", content = "第一问"),
            AgentMessageUi(id = "a1", content = "第一答"),
            UserMessageUi(id = "u2", content = "第二问"),
        )
        val first = projectFinalResultMessageIds(base, true, null)

        // 当前轮正文每帧都在变，但已封口轮次的 id 集合内容不变 ⇒ 必须复用同一实例，
        // 否则 `id in ids` 的下游 item 每个 token 都会重组。
        var previous = first
        for (step in 1..10) {
            val streaming = base + AgentMessageUi(id = "a2", content = "字".repeat(step), isStreaming = true)
            previous = projectFinalResultMessageIds(streaming, true, previous)
            assertSame(first.ids, previous.ids)
            assertEquals(setOf("a1"), previous.ids)
        }
    }

    // ── projectReferenceCandidates ───────────────────────────────────

    @Test
    fun projectReferenceCandidates_matchesOriginalSelection() {
        val messages = buildList {
            for (index in 1..200) {
                if (index % 3 == 0) {
                    add(UserMessageUi(id = "u$index", content = "  问题 $index\n第二行  "))
                } else {
                    add(AgentMessageUi(id = "a$index", content = "回答 $index  ${"内容 ".repeat(index % 7)}"))
                }
                if (index % 11 == 0) add(toolActivity("t$index"))
            }
        }
        val expected = originalReferenceCandidates(messages)
        val actual = projectReferenceCandidates(messages, 80, null).candidates
        assertEquals(expected, actual)
    }

    @Test
    fun projectReferenceCandidates_reusesUnchangedCandidatesWhileTailStreams() {
        val base = listOf(
            UserMessageUi(id = "u1", content = "问题一"),
            AgentMessageUi(id = "a1", content = "回答一"),
            UserMessageUi(id = "u2", content = "问题二"),
        )
        var previous = projectReferenceCandidates(base, 80, null)
        val stable = previous.candidates

        for (step in 1..10) {
            val streaming = base + AgentMessageUi(id = "a2", content = "字".repeat(step), isStreaming = true)
            previous = projectReferenceCandidates(streaming, 80, previous)
            // 未变化的历史候选必须复用同一实例（只有尾部那条流式消息重建）。
            assertSame(stable[0], previous.candidates[0])
            assertSame(stable[1], previous.candidates[1])
            assertSame(stable[2], previous.candidates[2])
            assertEquals(originalReferenceCandidates(streaming), previous.candidates)
        }
    }

    @Test
    fun projectReferenceCandidates_reusesWholeListWhenTailCandidateUnchanged() {
        val base = listOf(
            UserMessageUi(id = "u1", content = "问题一"),
            AgentMessageUi(id = "a1", content = "回答一"),
            // 尾部是工作过程消息：不参与候选，所以流式正文增长时候选集合完全不变。
            ThinkingMessageUi(id = "think-1", content = "分析", isStreaming = true),
        )
        val first = projectReferenceCandidates(base, 80, null)
        for (step in 1..10) {
            val streaming = listOf(
                base[0],
                base[1],
                ThinkingMessageUi(id = "think-1", content = "分析".repeat(step), isStreaming = true),
            )
            val next = projectReferenceCandidates(streaming, 80, first)
            // 候选集合逐项同一 ⇒ 整个候选列表实例复用，@ 面板不因思考流式而重组。
            assertSame(first.candidates, next.candidates)
        }
    }

    @Test
    fun projectReferenceCandidates_matchesOriginalWhenTailScrollsOutOfWindow() {
        // 超过 80 条候选后，窗口向后滚动：逐帧与原始实现比对，确认没有错位复用。
        var previous: ReferenceCandidatesProjection? = null
        val all = (1..120).map { AgentMessageUi(id = "a$it", content = "内容 $it") }
        for (size in 1..all.size) {
            val prefix = all.subList(0, size)
            previous = projectReferenceCandidates(prefix, 80, previous)
            assertEquals("size=$size", originalReferenceCandidates(prefix), previous!!.candidates)
        }
    }

    @Test
    fun projectReferenceCandidates_recomputesSequenceWhenHistoryIsTrimmed() {
        // 删除历史轮次后消息整体前移：sequence 与位置都变了，必须重建而不是错误复用。
        val all = (1..20).map { AgentMessageUi(id = "a$it", content = "内容 $it") }
        val full = projectReferenceCandidates(all, 80, null)
        val trimmed = projectReferenceCandidates(all.drop(5), 80, full)
        assertEquals(originalReferenceCandidates(all.drop(5)), trimmed.candidates)
    }

    // ── projectMentionMatches ────────────────────────────────────────

    @Test
    fun projectMentionMatches_matchesOriginalFiltering() {
        val candidates = originalReferenceCandidates(
            buildList {
                for (index in 1..60) {
                    add(AgentMessageUi(id = "a$index", content = "第 $index 条 DeepSeek 相关内容"))
                    if (index % 5 == 0) add(UserMessageUi(id = "u$index", content = "用户 $index 提问"))
                }
            },
        )
        for (query in listOf("", "deepseek", "DEEPSEEK", "第 1", "不存在的词", "用户")) {
            assertEquals(
                "query=$query",
                originalMentionMatches(candidates, query.trim(), 20),
                projectMentionMatches(candidates, query.trim(), 20, null).matches,
            )
        }
    }

    @Test
    fun projectMentionMatches_reusesMatchesWhenChangedCandidateDoesNotMatch() {
        val candidates = (1..40).map {
            MessageReferenceUi(
                messageId = "a$it",
                sequence = it,
                fromUser = false,
                preview = "预览 $it",
                searchText = "正文 $it",
            )
        }
        val first = projectMentionMatches(candidates, "正文 7", 20, null)
        assertEquals(listOf("a7"), first.matches.map { it.messageId })

        // 尾部追加一条**不命中**的候选：命中集合不变 ⇒ 结果列表实例必须复用。
        val appended = candidates + MessageReferenceUi("a41", 41, false, "预览 41", "正文 41")
        val second = projectMentionMatches(appended, "正文 7", 20, first)
        assertSame(first.matches, second.matches)

        // 追加一条命中的候选：结果变了，必须新建且与原逻辑一致。
        val withHit = candidates + MessageReferenceUi("a41", 41, false, "预览 41", "正文 7 补充")
        val third = projectMentionMatches(withHit, "正文 7", 20, second)
        assertEquals(originalMentionMatches(withHit, "正文 7", 20), third.matches)
        assertNotSame(first.matches, third.matches)
    }

    @Test
    fun projectMentionMatches_reusesInstanceWhenQueryEmptyAndCandidatesUnchanged() {
        val candidates = (1..5).map {
            MessageReferenceUi("a$it", it, false, "预览 $it", "正文 $it")
        }
        val first = projectMentionMatches(candidates, "", 20, null)
        assertEquals(listOf("a5", "a4", "a3", "a2", "a1"), first.matches.map { it.messageId })
        // 面板关闭 + 候选未变：整条投影原样返回，零分配。
        assertSame(first, projectMentionMatches(candidates, "", 20, first))
    }

    @Test
    fun projectMentionMatches_staysCorrectWhenWindowSlides() {
        // 候选窗口满 80 条后继续追加，头部会丢掉旧候选：位移对不上时必须回退到全量重算。
        val candidates = (1..80).map {
            MessageReferenceUi("a$it", it, false, "预览 $it", "正文 $it 关键词")
        }
        var previous = projectMentionMatches(candidates, "关键词", 20, null)
        var window = candidates
        for (step in 81..95) {
            window = (window + MessageReferenceUi("a$step", step, false, "预览 $step", "正文 $step 关键词"))
                .takeLast(80)
            previous = projectMentionMatches(window, "关键词", 20, previous)
            assertEquals("step=$step", originalMentionMatches(window, "关键词", 20), previous.matches)
        }
        // 窗口滑到底：命中集合稳定在尾部 20 条。
        assertEquals(20, previous.matches.size)
    }

    // ── projectContextUsage ──────────────────────────────────────────

    @Test
    fun projectContextUsage_matchesOriginalUsageSelection() {
        val model = agentModel(contextWindow = 128_000)
        val messages = listOf(
            UserMessageUi(id = "u1", content = "问"),
            AgentMessageUi(
                id = "a1",
                content = "答",
                usage = TokenUsageUi(
                    contextTokens = 10_000,
                    outputTokens = 500,
                    requestDurationMs = 2_000,
                    firstTokenMs = 400,
                ),
            ),
            AgentMessageUi(
                id = "a2",
                content = "答 2",
                usage = TokenUsageUi(
                    contextTokens = 20_000,
                    outputTokens = 900,
                    requestDurationMs = 3_000,
                    firstTokenMs = 300,
                ),
            ),
        )
        assertEquals(originalContextUsage(messages, model), projectContextUsage(messages, model, null).usage)
    }

    @Test
    fun projectContextUsage_matchesOriginalWhenCompactionResetsUsage() {
        val messages = listOf(
            UserMessageUi(id = "u1", content = "问"),
            AgentMessageUi(id = "a1", content = "答", usage = TokenUsageUi(contextTokens = 30_000)),
            SystemNoticeMessageUi(id = "n1", code = SystemNoticeCode.ContextCompaction),
        )
        assertEquals(originalContextUsage(messages, null), projectContextUsage(messages, null, null).usage)
    }

    @Test
    fun projectContextUsage_matchesOriginalWhenUsageLacksContextTokens() {
        // 只有 output/耗时、没有 contextTokens 的消息：不算用量来源，但仍是 TPS 来源。
        val messages = listOf(
            UserMessageUi(id = "u1", content = "问"),
            AgentMessageUi(id = "a1", content = "答", usage = TokenUsageUi(contextTokens = 5_000)),
            AgentMessageUi(
                id = "a2",
                content = "答 2",
                usage = TokenUsageUi(outputTokens = 200, requestDurationMs = 1_000),
            ),
        )
        assertEquals(originalContextUsage(messages, null), projectContextUsage(messages, null, null).usage)
    }

    @Test
    fun projectContextUsage_reusesObjectWhileOnlyStreamingTextChanges() {
        val measured = AgentMessageUi(id = "a1", content = "答", usage = TokenUsageUi(contextTokens = 12_345))
        val base = listOf(UserMessageUi(id = "u1", content = "问"), measured)
        val first = projectContextUsage(base, null, null)

        var previous = first
        for (step in 1..10) {
            val streaming = listOf(
                base[0],
                measured.copy(content = "答".repeat(step), isStreaming = true),
            )
            previous = projectContextUsage(streaming, null, previous)
            // usage 没变 ⇒ 结果对象必须复用，输入框的 token chip 不重组。
            assertSame(first.usage, previous.usage)
        }
    }

    // ── 原始实现（改动前的代码，作为等价性基准） ──────────────────────

    private fun originalPreview(content: String): String =
        content.replace(Regex("\\s+"), " ").take(60)

    private fun originalContextUsage(
        messages: List<AgentChatMessageUi>,
        model: AgentModelOptionUi?,
    ): AgentContextUsageUi {
        val usage = latestContextUsage(messages, model)
        val last = messages.asReversed()
            .firstOrNull { message -> message is AgentMessageUi && message.usage != null }
            ?.let { message -> (message as AgentMessageUi).usage }
        return usage.copy(
            averageTokensPerSecond = last?.generationTokensPerSecond ?: last?.averageTokensPerSecond,
            latestUsage = usage.latestUsage ?: last,
        )
    }

    private fun agentModel(contextWindow: Int?): AgentModelOptionUi = AgentModelOptionUi(
        id = "m1",
        providerId = "p1",
        providerName = "Provider",
        providerSourceType = "openai",
        modelId = "gpt",
        displayName = "GPT",
        contextWindow = contextWindow,
    )

    private fun originalVisible(
        messages: List<AgentChatMessageUi>,
        targetMessageId: String?,
    ): List<AgentChatMessageUi> = AgentConversationRevisionReducer.visibleMessagesForEdit(
        messages = messages,
        targetMessageId = targetMessageId,
    ).filterNot { message -> message is AgentMessageUi && message.content.isBlank() }

    private fun originalReferenceCandidates(
        messages: List<AgentChatMessageUi>,
        limit: Int = 80,
    ): List<MessageReferenceUi> = buildList(limit) {
        for (index in messages.lastIndex downTo 0) {
            val message = messages[index]
            val content = when (message) {
                is UserMessageUi -> message.content
                is AgentMessageUi -> message.content
                else -> null
            }?.trim().orEmpty()
            if (content.isEmpty()) continue
            add(
                MessageReferenceUi(
                    messageId = message.id,
                    sequence = index + 1,
                    fromUser = message is UserMessageUi,
                    preview = content.replace(Regex("\\s+"), " ").take(60),
                    searchText = content.take(4_000),
                ),
            )
            if (size == limit) break
        }
        reverse()
    }

    private fun originalMentionMatches(
        candidates: List<MessageReferenceUi>,
        query: String,
        limit: Int,
    ): List<MessageReferenceUi> {
        if (query.isEmpty()) return candidates.takeLast(limit).reversed()
        return candidates
            .filter { candidate ->
                candidate.searchText.contains(query, ignoreCase = true) ||
                    candidate.preview.contains(query, ignoreCase = true)
            }
            .takeLast(limit)
            .reversed()
    }

    private fun toolActivity(id: String): AgentChatMessageUi = ToolActivityMessageUi(
        id = id,
        toolName = "browser_use",
        status = ToolActivityStatusUi.Success,
        argumentsSummary = "query",
    )

    @Test
    fun collapsedPreview_handlesLeadingWhitespaceBeforeLimit() {
        // 前导空白 + 恰好 60 字正文：正则版与逐字符版都必须保留前导空格且不超长。
        val content = "   " + "字".repeat(70)
        val expected = originalPreview(content)
        val actual = collapsedPreview(content)
        assertEquals(expected, actual)
        assertTrue(actual.startsWith(" "))
        assertEquals(60, actual.length)
    }
}
