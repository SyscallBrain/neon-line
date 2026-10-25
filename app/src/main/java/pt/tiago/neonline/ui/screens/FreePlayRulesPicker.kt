package pt.tiago.neonline.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.R
import pt.tiago.neonline.data.FreePlayRules
import pt.tiago.neonline.data.FreePlayBoardShape

@Composable
internal fun FreePlayRulesPicker(rules: FreePlayRules, onChange: (FreePlayRules) -> Unit) {
    Text(stringResource(R.string.free_rules), style = MaterialTheme.typography.titleMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((width, height) in listOf(7 to 6, 9 to 7)) FilterChip(rules.width == width,
            { onChange(rules.copy(width = width, height = height, rotation = rules.rotation && width == 7)) }, label = { Text("$width×$height") })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (connect in 4..5) FilterChip(rules.connect == connect, { onChange(rules.copy(connect = connect)) }, label = { Text(stringResource(R.string.local_connect, connect)) })
    }
    Text(stringResource(R.string.free_board_shape), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FreePlayBoardShape.entries.forEach { shape -> FilterChip(rules.shape == shape,
            { onChange(rules.withShape(shape, allowMaskedSpecials = true)) }, label = { Text(stringResource(shape.labelResource())) }) }
    }
    val rectangular = rules.shape == FreePlayBoardShape.RECTANGLE
    if (!rectangular) Text(stringResource(R.string.free_shape_help), style = MaterialTheme.typography.bodySmall)
    RuleToggle(R.string.local_pop, rules.popOut, rectangular) { onChange(rules.copy(popOut = it)) }
    RuleToggle(R.string.local_rotation, rules.rotation, rules.width == 7 && rectangular) { onChange(rules.copy(rotation = it)) }
    RuleToggle(R.string.local_specials, rules.specials) { onChange(rules.copy(specials = it)) }
    RuleToggle(R.string.free_fade, rules.fade) { onChange(rules.copy(fade = it)) }
    if (rules.fade) Text(stringResource(R.string.free_fade_help), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.local_clock), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (seconds in listOf(0,10,20)) FilterChip(rules.clockSeconds == seconds, { onChange(rules.copy(clockSeconds = seconds)) },
            label = { Text(if (seconds == 0) stringResource(R.string.local_clock_off) else stringResource(R.string.free_clock_short, seconds)) })
    }
}

@Composable
private fun RuleToggle(label: Int, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f).padding(end = 8.dp), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun freePlayRulesSummary(rules: FreePlayRules, difficulty: pt.tiago.neonline.core.model.Difficulty): String = buildList {
    add(stringResource(R.string.free_board_short, rules.width, rules.height, rules.connect))
    if (rules.shape != FreePlayBoardShape.RECTANGLE) add(stringResource(rules.shape.labelResource()))
    rules.fadeAfter(difficulty)?.let { add(pluralStringResource(R.plurals.free_fade_short, it, it)) }
    if (rules.popOut) add(stringResource(R.string.free_pop_short))
    if (rules.rotation) add(stringResource(R.string.free_rotation_short))
    if (rules.specials) add(stringResource(R.string.free_specials_short))
    if (rules.clockSeconds > 0) add(stringResource(R.string.free_clock_short, rules.clockSeconds))
}.joinToString(" · ")

private fun FreePlayBoardShape.labelResource(): Int = when (this) {
    FreePlayBoardShape.RECTANGLE -> R.string.free_shape_rectangle
    FreePlayBoardShape.PILLARS -> R.string.free_shape_pillars
    FreePlayBoardShape.STEPS -> R.string.free_shape_steps
}
