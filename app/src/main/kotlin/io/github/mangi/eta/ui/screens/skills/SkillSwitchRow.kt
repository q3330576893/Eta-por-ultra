package io.github.mangi.eta.ui.screens.skills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.SkillRatingUi
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SkillSwitchRow(
    skill: SkillItemUi,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
    rating: SkillRatingUi? = null,
    onRate: ((Int, String) -> Unit)? = null,
) {
    var showDescription by remember(skill.id) { mutableStateOf(false) }
    var showRating by remember(skill.id) { mutableStateOf(false) }
    val description = skill.description.ifBlank { stringResource(R.string.skills_no_description) }
    EtaPreference(
        startAction = { EtaPreferenceIcon(iconForSkill(skill.id), enabled = enabled, tint = EtaPreferenceColors.Green) },
        endActions = {
            OverlayIconDropdownMenu(
                modifier = Modifier.align(Alignment.CenterVertically),
                entry = DropdownEntry(
                    items = listOfNotNull(
                        DropdownItem(
                            text = stringResource(R.string.ui_view_description),
                            onClick = { showDescription = true },
                        ),
                        onRate?.let {
                            DropdownItem(
                                text = "评分反馈",
                                onClick = { showRating = true },
                            )
                        },
                        onDelete?.let { delete ->
                            DropdownItem(
                                text = stringResource(R.string.ui_delete_3755f5),
                                enabled = enabled,
                                onClick = { if (enabled) delete() },
                            )
                        },
                    ),
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreHoriz,
                    contentDescription = stringResource(R.string.skills_more_named, skill.name),
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            EtaSwitch(
                checked = skill.enabled,
                onCheckedChange = onToggle,
                enabled = enabled,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        },
    ) {
        Text(
            text = skill.name,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.disabledOnSurface,
        )
        Text(
            text = description,
            style = MiuixTheme.textStyles.body2,
            color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.disabledOnSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (rating != null && rating.feedbackCount > 0) {
            SkillRatingLine(
                average = rating.averageRating,
                count = rating.feedbackCount,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    if (showDescription) {
        ItemDescriptionDialog(
            title = skill.name,
            description = description,
            onDismiss = { showDescription = false },
        )
    }
    if (showRating && onRate != null) {
        SkillFeedbackDialog(
            skillName = skill.name,
            onDismiss = { showRating = false },
            onSubmit = { stars, comment ->
                showRating = false
                onRate(stars, comment)
            },
        )
    }
}

/** 只读星级展示行：平均分 + 反馈条数。 */
@Composable
private fun SkillRatingLine(
    average: Double,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val rounded = average.coerceIn(0.0, 5.0).let { kotlin.math.round(it).toInt() }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { index ->
            Icon(
                imageVector = if (index < rounded) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (index < rounded) Color(0xFFFFC107) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "%.1f · %d 条评价".format(average.coerceIn(0.0, 5.0), count),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 评分反馈弹窗：星级点击选择 + 可选评论（≤500 字符）。 */
@Composable
private fun SkillFeedbackDialog(
    skillName: String,
    onDismiss: () -> Unit,
    onSubmit: (Int, String) -> Unit,
) {
    var stars by remember { mutableStateOf(5) }
    var comment by remember { mutableStateOf("") }
    EtaWindowDialog(
        show = true,
        title = "评分反馈 · $skillName",
        summary = "评分仅保存在本机，不会联网上传",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(5) { index ->
                    val star = index + 1
                    Icon(
                        imageVector = if (star <= stars) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = "$star 星",
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { stars = star },
                        tint = if (star <= stars) Color(0xFFFFC107) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            BasicTextField(
                value = comment,
                onValueChange = { comment = it.take(500) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                ),
                decorationBox = { inner ->
                    if (comment.isEmpty()) {
                        Text(
                            text = "补充说明（可选，最多 500 字）",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    inner()
                },
            )
            Text(
                text = "${comment.length}/500",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(modifier = Modifier.heightIn(min = 12.dp))
            MiuixDialogActions(
                confirmText = "提交评分",
                confirmEnabled = true,
                onCancel = onDismiss,
                onConfirm = { onSubmit(stars, comment) },
            )
        }
    }
}

internal fun iconForSkill(skillId: String): ImageVector = when (skillId) {
    "self-improving-agent" -> Icons.Rounded.Refresh
    "skill-creator" -> Icons.Rounded.DesignServices
    else -> Icons.Rounded.Extension
}
