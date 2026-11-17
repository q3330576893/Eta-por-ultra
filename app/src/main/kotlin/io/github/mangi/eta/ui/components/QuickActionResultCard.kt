package io.github.mangi.eta.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.quickactions.DailyCommandResult
import io.github.mangi.eta.agent.quickactions.WeatherCity
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Typed observations and handoffs retain their request; retries are provided by the owning session. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun QuickActionResultCard(
    result: DailyCommandResult,
    modifier: Modifier = Modifier,
    onCityInput: (String) -> Unit = {},
    onQuickTextInput: (String) -> Unit = {},
    onCitySelected: (WeatherCity) -> Unit = {},
    onRetry: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    val cityHint = stringResource(R.string.daily_commands_city_hint)
    val alarmHint = "例如明天早上7点、每天7点半或工作日08:30"
    val alarmSetLabel = "设置"
    val actionFailed = stringResource(R.string.daily_commands_action_failed)
    val copiedLabel = stringResource(R.string.copy_copied)
    var cityInput by remember(result.request) { mutableStateOf("") }
    var alarmInput by remember(result.request) { mutableStateOf("") }
    val shareText = remember(result) { buildString {
        appendLine(result.title)
        appendLine(result.summary)
        result.fields.forEach { appendLine("${it.label}：${it.value}") }
        append("${result.sourceLabel} · ${result.asOf.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT))}")
        result.sourceUrl?.let { append("\n$it") }
    } }
    fun open(intent: Intent) {
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: RuntimeException) { Toast.makeText(context, actionFailed, Toast.LENGTH_SHORT).show() }
    }
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(colors.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(result.title, style = MiuixTheme.textStyles.headline2, color = colors.onSurface)
        val handoff = result is DailyCommandResult.AlarmHandoff || result is DailyCommandResult.TimerHandoff
        if (handoff || result.verified) Text(
            stringResource(if (handoff) R.string.daily_commands_handoff_pending else R.string.daily_commands_data_received),
            color = colors.onSurfaceVariantSummary, fontSize = 12.sp,
        )
        Text(result.summary, color = colors.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        result.fields.forEach { field ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(field.label, color = colors.onSurfaceVariantSummary, modifier = Modifier.weight(0.33f))
                Text(field.value, color = colors.onSurface, modifier = Modifier.weight(0.67f))
            }
        }
        if (result is DailyCommandResult.NeedsCity) {
            BasicTextField(
                value = cityInput, onValueChange = { cityInput = it.take(40) }, singleLine = true,
                textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { cityInput.trim().takeIf { it.length >= 2 }?.let(onCityInput) }),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(colors.background).padding(12.dp).semantics { contentDescription = cityHint },
                decorationBox = { inner ->
                    if (cityInput.isBlank()) Text(cityHint, color = colors.onSurfaceVariantSummary)
                    inner()
                },
            )
            EtaTextButton(stringResource(R.string.daily_commands_query_city),
                onClick = { cityInput.trim().takeIf { it.length >= 2 }?.let(onCityInput) },
                modifier = Modifier.heightIn(min = 48.dp))
        }
        if (result is DailyCommandResult.NeedsAlarmTime) {
            BasicTextField(
                value = alarmInput, onValueChange = { alarmInput = it.take(40) }, singleLine = true,
                textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { alarmInput.trim().takeIf { it.isNotBlank() }?.let(onQuickTextInput) }),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(colors.background).padding(12.dp).semantics { contentDescription = alarmHint },
                decorationBox = { inner ->
                    if (alarmInput.isBlank()) Text(alarmHint, color = colors.onSurfaceVariantSummary)
                    inner()
                },
            )
            EtaTextButton(alarmSetLabel,
                onClick = { alarmInput.trim().takeIf { it.isNotBlank() }?.let(onQuickTextInput) },
                modifier = Modifier.heightIn(min = 48.dp))
        }
        if (result is DailyCommandResult.CityChoices) result.cities.forEach { city ->
            EtaTextButton(city.displayName, onClick = { onCitySelected(city) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
        }
        if (handoff) Text(stringResource(R.string.daily_commands_clock_confirmation),
            color = colors.onSurfaceVariantSummary, fontSize = 12.sp)
        if (result is DailyCommandResult.WeatherAnswer && result.stale) Text(
            stringResource(R.string.daily_commands_stale_weather), color = colors.onSurfaceVariantSummary, fontSize = 12.sp)
        val time = result.asOf.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT))
        Text(stringResource(R.string.daily_commands_source_time, result.sourceLabel, time),
            color = colors.onSurfaceVariantSummary, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            EtaTextButton(stringResource(R.string.capsule_copy_reply), onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(result.title, shareText))
                Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
            })
            EtaTextButton(stringResource(R.string.daily_commands_share), onClick = {
                open(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText), result.title))
            })
            if (result is DailyCommandResult.Failure && onRetry != null) EtaTextButton(
                stringResource(R.string.capsule_retry), onClick = onRetry)
            if (result is DailyCommandResult.WeatherAnswer && onRefresh != null) EtaTextButton(
                stringResource(R.string.daily_commands_refresh), onClick = onRefresh)
            if (handoff) EtaTextButton(stringResource(R.string.daily_commands_open_clock), onClick = {
                open(Intent(if (result is DailyCommandResult.TimerHandoff) AlarmClock.ACTION_SHOW_TIMERS else AlarmClock.ACTION_SHOW_ALARMS))
            })
            result.sourceUrl?.let { url -> EtaTextButton(stringResource(R.string.daily_commands_source), onClick = {
                open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }) }
        }
    }
}
