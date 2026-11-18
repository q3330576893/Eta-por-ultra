package io.github.mangi.eta.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.prompt.SessionSamplingOverride
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaTextButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 会话级采样参数 + Instruct 模板覆盖面板。
 *
 * 【文案约定】按实施约束，本文件先用中文常量，不新增 R.string。
 * 报告里给出建议 key（见交付报告），后续本地化时替换这里的常量即可。
 * 组件全部走工程现有的 Miuix 造型（top.yukonga.miuix.kmp.*），不引入 material3。
 */
private object SamplingLabels {
    const val TITLE = "采样参数与指令模板（按会话）"
    const val SCOPE_NOTE = "仅对支持的模型生效；不支持的字段会自动跳过并标注。"
    const val ENABLED = "覆盖全局设置"
    const val TEMPERATURE = "温度（temperature）"
    const val TOP_P = "Top-p"
    const val MAX_OUTPUT = "最大输出 token"
    const val INSTRUCT = "Instruct 指令模板"
    const val INSTRUCT_HINT = "Instruct 指令模板（结论先行、要点回答…）"
    const val ROLE_PRESET = "角色预设名（可选）"
    const val RESET = "恢复跟随全局"
    const val VALUE_FORMAT = "%.2f"
}

/**
 * 采样覆盖编辑面板。
 *
 * 纯 UI：状态由调用方持有，改动通过 [onChange] 回传，持久化由会话发送链调用
 * PromptPresetRepository.saveSessionSampling 完成。开关关闭时传入 null 覆盖。
 */
@Composable
internal fun SessionSamplingPanel(
    override: SessionSamplingOverride?,
    onChange: (SessionSamplingOverride?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = override != null
    val current = override ?: SessionSamplingOverride()
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Text(text = SamplingLabels.TITLE, style = MiuixTheme.textStyles.headline2)
        Text(
            text = SamplingLabels.SCOPE_NOTE,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = SamplingLabels.ENABLED, modifier = Modifier.weight(1f))
            EtaSwitch(
                checked = active,
                onCheckedChange = { checked -> onChange(if (checked) current else null) },
            )
        }
        Spacer(Modifier.height(8.dp))

        // 温度与 Top-p 用滑块；未覆盖时显示中性默认值，但只有 active 后的改动才写入覆盖。
        SamplingSlider(
            label = SamplingLabels.TEMPERATURE,
            value = current.temperature ?: 0.7,
            valueRange = 0f..2f,
        ) { value -> onChange(current.copy(temperature = value)) }

        SamplingSlider(
            label = SamplingLabels.TOP_P,
            value = current.topP ?: 1.0,
            valueRange = 0f..1f,
        ) { value -> onChange(current.copy(topP = value)) }

        TextField(
            value = current.maxOutputTokens?.toString().orEmpty(),
            onValueChange = { raw ->
                onChange(current.copy(maxOutputTokens = raw.toIntOrNull()?.takeIf { it > 0 }))
            },
            label = SamplingLabels.MAX_OUTPUT,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        TextField(
            value = current.instructTemplate,
            onValueChange = { raw ->
                onChange(current.copy(instructTemplate = raw.take(SessionSamplingOverride.INSTRUCT_MAX_CHARS)))
            },
            label = if (current.instructTemplate.isBlank()) SamplingLabels.INSTRUCT_HINT else SamplingLabels.INSTRUCT,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        TextField(
            value = current.rolePresetName,
            onValueChange = { raw -> onChange(current.copy(rolePresetName = raw.take(120))) },
            label = SamplingLabels.ROLE_PRESET,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        EtaTextButton(
            text = SamplingLabels.RESET,
            onClick = { onChange(null) },
        )
    }
}

@Composable
private fun SamplingSlider(
    label: String,
    value: Double,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (Double) -> Unit,
) {
    Text(text = "$label  ${SamplingLabels.VALUE_FORMAT.format(value)}")
    Slider(
        value = value.toFloat().coerceIn(valueRange),
        onValueChange = { onChange(it.toDouble()) },
        valueRange = valueRange,
        modifier = Modifier.fillMaxWidth(),
    )
}
