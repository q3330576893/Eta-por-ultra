package io.github.mangi.eta.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.prompt.SessionSamplingOverride
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaTextButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

@Composable
internal fun SessionSamplingPanel(
    override: SessionSamplingOverride?,
    onChange: (SessionSamplingOverride?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = override ?: SessionSamplingOverride()
    Column(modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.conversation_sampling_title))
        Text(stringResource(R.string.conversation_sampling_scope))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.conversation_sampling_enabled), Modifier.weight(1f))
            EtaSwitch(checked = override != null,
                onCheckedChange = { onChange(if (it) SessionSamplingOverride(temperature = 0.7) else null) })
        }
        Text("${stringResource(R.string.conversation_sampling_temperature)}: ${current.temperature ?: 0.7}")
        Slider(value = (current.temperature ?: 0.7).toFloat().coerceIn(0f, 2f), valueRange = 0f..2f,
            onValueChange = { onChange(current.copy(temperature = it.toDouble())) })
        Text("Top-p: ${current.topP ?: 1.0}")
        Slider(value = (current.topP ?: 1.0).toFloat().coerceIn(0f, 1f), valueRange = 0f..1f,
            onValueChange = { onChange(current.copy(topP = it.toDouble())) })
        TextField(value = current.maxOutputTokens?.toString().orEmpty(),
            onValueChange = { raw ->
                if (raw.isBlank()) onChange(current.copy(maxOutputTokens = null))
                else raw.toIntOrNull()?.takeIf { it in 1..1_000_000 }?.let { onChange(current.copy(maxOutputTokens = it)) }
            }, label = stringResource(R.string.conversation_sampling_max_output), singleLine = true)
        TextField(value = current.instructTemplate,
            onValueChange = { onChange(current.copy(instructTemplate = it.take(SessionSamplingOverride.INSTRUCT_MAX_CHARS))) },
            label = stringResource(R.string.conversation_sampling_instruct), modifier = Modifier.fillMaxWidth())
        EtaTextButton(text = stringResource(R.string.conversation_sampling_reset), onClick = { onChange(null) })
    }
}
