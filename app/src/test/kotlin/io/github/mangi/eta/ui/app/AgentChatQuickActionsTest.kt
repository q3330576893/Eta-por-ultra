package io.github.mangi.eta.ui.app

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.quickactions.DailyCommandResult
import io.github.mangi.eta.agent.quickactions.DailyIntent
import io.github.mangi.eta.agent.quickactions.DailyIntentRouter
import io.github.mangi.eta.agent.quickactions.DailyWeatherSnapshot
import io.github.mangi.eta.agent.quickactions.WeatherCity
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.ChatQuickActionUiState
import io.github.mangi.eta.ui.model.PendingImageUi
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Quick commands complete in the app state; background Runtime work stays unstarted in this scope. */
@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentChatQuickActionsTest {
    private val now = Instant.parse("2026-11-16T10:00:00Z")
    private val city = WeatherCity(1, "北京", "CN", "中国", "北京市", "北京市", 39.9, 116.4, "Asia/Shanghai")

    @Test
    fun explicitCommandCompletesWithoutModelOrRuntimeConversation() = withState { state ->
        assertFalse(state.modelPickerState.hasUsableModel)

        state.sendCurrentMessage("现在几点")

        val quick = state.homeState.quickAction!!
        assertEquals(DailyIntent.TimeNow, quick.request)
        assertTrue(quick.result is DailyCommandResult.ClockAnswer)
        assertFalse(state.homeState.isStreaming)
        assertTrue(state.homeState.messages.isEmpty())
        assertTrue(state.homeState.history.isEmpty())
        assertTrue(state.homeState.appliedRuntimeRunIds.isEmpty())
        assertNull(state.conversationPaneState.selectedConversationId)
        assertTrue(state.conversationPaneState.conversations.isEmpty())
    }

    @Test
    fun cityInputCopiesOriginalWeatherRequestAndCityChoiceUsesSelectedCity() {
        val calls = mutableListOf<Pair<DailyIntent, WeatherCity?>>()
        withState(executor = { _, request, selected ->
            calls += request to selected
            when (calls.size) {
                1 -> DailyCommandResult.NeedsCity(request as DailyIntent.Weather, now)
                2 -> DailyCommandResult.CityChoices(request as DailyIntent.Weather, listOf(city), now)
                else -> DailyCommandResult.Failure(request, "DONE", selected?.displayName.orEmpty(), now)
            }
        }) { state ->
            state.sendCurrentMessage("明天下雨吗")
            val first = state.homeState.quickAction!!
            state.submitQuickActionCity(first.generation, " 北京 ")
            val choices = state.homeState.quickAction!!
            val request = choices.request as DailyIntent.Weather
            assertEquals("北京", request.cityQuery)
            assertEquals((first.request as DailyIntent.Weather).period, request.period)
            assertTrue(request.rainOnly)
            assertTrue(choices.generation > first.generation)

            state.selectQuickActionCity(first.generation, city.id)
            assertEquals(2, calls.size)
            state.selectQuickActionCity(choices.generation, 999)
            assertEquals(2, calls.size)
            state.selectQuickActionCity(choices.generation, city.id)
            assertEquals(request, calls.last().first)
            assertEquals(city, calls.last().second)
            assertTrue(state.homeState.quickAction!!.generation > choices.generation)
        }
    }

    @Test
    fun cancellationAndReplacementDiscardLateExecutorResults() {
        val pending = mutableListOf<Continuation<DailyCommandResult>>()
        withState(executor = { _, _, _ -> suspendCoroutine { pending += it } }) { state ->
            state.sendCurrentMessage("现在几点")
            val first = state.homeState.quickAction!!
            state.sendCurrentMessage("今天几号")
            val second = state.homeState.quickAction!!
            assertTrue(second.generation > first.generation)
            pending[0].resume(answer(DailyIntent.TimeNow))
            assertEquals(second.generation, state.homeState.quickAction!!.generation)
            assertNull(state.homeState.quickAction!!.result)

            state.dismissQuickAction(first.generation)
            assertNotNull(state.homeState.quickAction)
            state.dismissQuickAction(second.generation)
            pending[1].resume(answer(DailyIntent.DateToday))
            assertNull(state.homeState.quickAction)
        }
    }

    @Test
    fun newOrdinaryMessageCancelsPendingQuickTaskAndUsesOriginalChatPath() {
        var pending: Continuation<DailyCommandResult>? = null
        withState(executor = { _, _, _ -> suspendCoroutine { pending = it } }) { state ->
            state.sendCurrentMessage("现在几点")
            assertTrue(state.homeState.quickAction!!.running)
            state.sendCurrentMessage("分析这周天气对旅行计划的影响")
            assertNull(state.homeState.quickAction)
            assertTrue(state.homeState.isStreaming)
            assertNotNull(state.conversationPaneState.selectedConversationId)
            pending!!.resume(answer(DailyIntent.TimeNow))
            assertNull(state.homeState.quickAction)
        }
    }

    @Test
    fun attachmentsAndRoleplayKeepOriginalModelPath() {
        val count = AtomicInteger()
        val imageState = emptyChat().copy(pendingImages = listOf(
            PendingImageUi("image", "content://example/image", "data:image/png;base64,AA==", "image/png"),
        ))
        withState(initialChat = imageState, executor = { _, request, _ -> count.incrementAndGet(); answer(request) }) { state ->
            state.sendCurrentMessage("现在几点")
            assertNull(state.homeState.quickAction)
            assertTrue(state.homeState.isStreaming)
        }
        val role = RoleplayBinding("character", CharacterCardCodec.encodeJson(CharacterCardCodec.create("旅人")), "旅人")
        withState(initialChat = emptyChat().copy(roleplay = role), executor = { _, request, _ -> count.incrementAndGet(); answer(request) }) { state ->
            state.sendCurrentMessage("现在几点")
            assertNull(state.homeState.quickAction)
            assertTrue(state.homeState.isStreaming)
        }
        assertEquals(0, count.get())
    }

    @Test
    fun retryAndRefreshUseNewGenerationAndRetainSelectedCityWithoutRuntime() {
        val calls = mutableListOf<Triple<DailyIntent, WeatherCity?, Boolean>>()
        withState(refreshExecutor = { _, request, selected, refresh ->
            calls += Triple(request, selected, refresh)
            if (calls.size == 1) DailyCommandResult.CityChoices(request as DailyIntent.Weather, listOf(city), now)
            else if (calls.size == 2) DailyCommandResult.Failure(request, "OFFLINE", "重试", now)
            else weatherAnswer(request as DailyIntent.Weather)
        }) { state ->
            state.sendCurrentMessage("北京天气")
            val choices = state.homeState.quickAction!!
            state.selectQuickActionCity(choices.generation, city.id)
            val failed = state.homeState.quickAction!!
            state.retryQuickAction(choices.generation)
            assertEquals(2, calls.size)
            state.retryQuickAction(failed.generation)
            val answered = state.homeState.quickAction!!
            assertTrue(answered.generation > failed.generation)
            assertEquals(city, calls.last().second)
            assertFalse(calls.last().third)
            state.refreshQuickAction(failed.generation)
            assertEquals(3, calls.size)
            state.refreshQuickAction(answered.generation)
            assertTrue(state.homeState.quickAction!!.generation > answered.generation)
            assertEquals(city, calls.last().second)
            assertTrue(calls.last().third)
            assertFalse(state.homeState.isStreaming)
            assertTrue(state.homeState.history.isEmpty())
            assertNull(state.conversationPaneState.selectedConversationId)
        }
    }

    @Test
    fun alarmSetupFollowUpUsesLocalRouterAndNeverStartsModel() {
        val requests = mutableListOf<DailyIntent>()
        withState(executor = { _, request, _ ->
            requests += request
            when (request) {
                DailyIntent.AlarmSetup -> DailyCommandResult.NeedsAlarmTime(asOf = now)
                else -> DailyCommandResult.AlarmHandoff(
                    request as DailyIntent.SetAlarm, "已交接", now, emptyList())
            }
        }) { state ->
            state.sendCurrentMessage("设置闹钟")
            val setup = state.homeState.quickAction!!
            assertTrue(setup.result is DailyCommandResult.NeedsAlarmTime)
            assertEquals(DailyIntent.AlarmSetup, setup.request)

            state.sendCurrentMessage("明天早上7点")

            val answered = state.homeState.quickAction!!
            assertTrue("追问应产生新的本地快捷任务，而不是启动模型", answered.generation > setup.generation)
            assertTrue("应走 followUp 生成 SetAlarm handoff", answered.result is DailyCommandResult.AlarmHandoff)
            val alarm = answered.request as DailyIntent.SetAlarm
            assertEquals(7, alarm.hour)
            assertEquals(0, alarm.minute)
            assertNotNull("明天闹钟应带目标日期", alarm.date)
            assertEquals(2, requests.size)
            assertFalse("不得进入模型思考", state.homeState.isStreaming)
            assertTrue(state.homeState.history.isEmpty())
            assertTrue(state.homeState.messages.isEmpty())
            assertNull(state.conversationPaneState.selectedConversationId)
        }
    }

    @Test
    fun negativeAndConditionalFollowUpNeverTriggersAlarmHandoff() {
        val nowZoned = ZonedDateTime.of(2026, 11, 16, 14, 20, 0, 0, ZoneId.of("Asia/Shanghai"))
        val pending = DailyCommandResult.NeedsAlarmTime(asOf = now)
        listOf("别设了", "不用了", "如果明天下雨就早上7点", "取消闹钟").forEach { text ->
            assertNull("否定/条件句不能误触发闹钟：$text", DailyIntentRouter.followUp(text, pending, nowZoned))
        }
    }

    @Test
    fun followUpNeedsAlarmTimeResolvesRelativeTimesAndRepeats() {
        val nowZoned = ZonedDateTime.of(2026, 11, 16, 14, 20, 0, 0, ZoneId.of("Asia/Shanghai"))
        val pending = DailyCommandResult.NeedsAlarmTime(asOf = now)
        assertEquals(DailyIntent.SetAlarm(7, 30), DailyIntentRouter.followUp("7点半", pending, nowZoned))
        assertEquals(DailyIntent.SetAlarm(8, 30,
            repeatDays = setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY,
                java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY)),
            DailyIntentRouter.followUp("工作日8:30", pending, nowZoned))
        assertEquals(DailyIntent.SetAlarm(7, 0, date = nowZoned.toLocalDate().plusDays(1)),
            DailyIntentRouter.followUp("明天早上7点", pending, nowZoned))
    }

    @Test
    fun clockHandoffAndClockFailuresNeverRecreateAlarmOrTimer() {
        var calls = 0
        withState(executor = { _, request, _ ->
            calls++
            DailyCommandResult.TimerHandoff(request as DailyIntent.SetTimer, "已交接", now, emptyList())
        }) { state ->
            state.sendCurrentMessage("倒计时5分钟")
            val handoff = state.homeState.quickAction!!
            state.retryQuickAction(handoff.generation)
            state.refreshQuickAction(handoff.generation)
            assertEquals(1, calls)
            assertFalse(handoff.canRetry)
            assertFalse(handoff.canRefresh)
        }
        val request = DailyIntent.SetAlarm(7, 0)
        assertFalse(ChatQuickActionUiState(1, request,
            DailyCommandResult.Failure(request, "CLOCK", "时钟", now)).canRetry)
    }

    @Test
    fun conversationSwitchDiscardsPendingResultAndOldCardActions() {
        var pending: Continuation<DailyCommandResult>? = null
        withState(initialChat = emptyChat(), executor = { _, _, _ -> suspendCoroutine { pending = it } }) { state ->
            state.sendCurrentMessage("现在几点")
            val old = state.homeState.quickAction!!
            state.createConversation()
            pending!!.resume(answer(DailyIntent.TimeNow))
            state.retryQuickAction(old.generation)
            state.refreshQuickAction(old.generation)
            assertNull(state.homeState.quickAction)
            assertFalse(state.homeState.isStreaming)
        }
    }

    @Test
    fun quickStateIsDefaultNullAndExcludedFromConversationPersistenceProjection() {
        val original = emptyChat()
        assertNull(original.quickAction)
        val current = original.copy(quickAction = ChatQuickActionUiState(4, DailyIntent.TimeNow, answer(DailyIntent.TimeNow)))
        assertEquals(
            AgentConversationPersistence.Content(original, "会话", 1L),
            AgentConversationPersistence.Content(current, "会话", 1L),
        )
    }

    private fun answer(request: DailyIntent) = DailyCommandResult.ClockAnswer(request, "时间", "本地结果", now, emptyList())

    private fun weatherAnswer(request: DailyIntent.Weather) = DailyCommandResult.WeatherAnswer(
        request, city, DailyWeatherSnapshot(city, now, now, 12.0, 0, 0.0, emptyList()),
        cached = true, stale = false, summary = "晴", fields = emptyList(),
    )

    private fun emptyChat() = AgentChatHomeUiState(messages = emptyList(), input = "", isStreaming = false, thinkingEnabled = false)

    private fun withState(
        initialChat: AgentChatHomeUiState? = null,
        executor: suspend (Context, DailyIntent, WeatherCity?) -> DailyCommandResult = { _, request, _ -> answer(request) },
        refreshExecutor: (suspend (Context, DailyIntent, WeatherCity?, Boolean) -> DailyCommandResult)? = null,
        block: (AgentAppState) -> Unit,
    ) {
        // AgentAppState normally starts recovery/provider observers on IO. Leave those queued so this test
        // proves the local shortcut independently of database, configuration or AgentRuntime services.
        val background = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + background)
        val snapshot = if (initialChat == null) {
            AgentConversationStore.Snapshot(null, emptyMap(), emptyMap(), emptyMap())
        } else {
            AgentConversationStore.Snapshot("test", mapOf("test" to initialChat), mapOf("test" to "测试"), mapOf("test" to 1L))
        }
        try {
            block(AgentAppState(RuntimeEnvironment.getApplication(), scope, initialConversations = snapshot,
                quickActionExecutor = refreshExecutor ?: { app, request, selected, _ -> executor(app, request, selected) },
                observeRuntimeOnStart = false))
        } finally {
            scope.cancel()
        }
    }
}
