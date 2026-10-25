package pt.tiago.neonline.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.R
import pt.tiago.neonline.data.Settings

@Composable
fun SettingsScreen(settings: Settings, stars: Int, onTheme: (pt.tiago.neonline.ui.art.BoardSkin) -> Unit, onBack: () -> Unit, onChange: (Settings) -> Unit,
    backupBusy: Boolean, onExport: () -> Unit, onImport: () -> Unit, onDiagnostic: () -> Unit, onReset: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
        ScreenHeader(stringResource(R.string.nav_settings), onBack)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsGroup(R.string.settings_feedback) {
                SettingsToggle(R.string.settings_sound, settings.sound) { onChange(settings.copy(sound = it)) }
                var volume by remember(settings.soundVolume) { mutableFloatStateOf(settings.soundVolume) }
                Text(stringResource(R.string.settings_volume, (volume * 100).toInt()))
                Slider(value = volume, onValueChange = { volume = it }, enabled = settings.sound,
                    onValueChangeFinished = { onChange(settings.copy(soundVolume = volume)) })
                SettingsToggle(R.string.settings_vibration, settings.vibration) { onChange(settings.copy(vibration = it)) }
                Text(stringResource(R.string.settings_silent_note), style = MaterialTheme.typography.bodySmall)
            }
            SettingsGroup(R.string.settings_game) {
                SettingsToggle(R.string.settings_confirm, settings.confirmMove) { onChange(settings.copy(confirmMove = it)) }
                SettingsToggle(R.string.settings_preview, settings.showPreview) { onChange(settings.copy(showPreview = it)) }
                Text(stringResource(R.string.settings_ai_speed))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = settings.aiSpeed == "normal", onClick = { onChange(settings.copy(aiSpeed = "normal")) }, label = { Text(stringResource(R.string.settings_ai_normal)) })
                    FilterChip(selected = settings.aiSpeed == "fast", onClick = { onChange(settings.copy(aiSpeed = "fast")) }, label = { Text(stringResource(R.string.settings_ai_fast)) })
                }
                Text(stringResource(R.string.settings_ai_note), style = MaterialTheme.typography.bodySmall)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) SettingsGroup(R.string.settings_language) { LanguagePicker() }
            SettingsGroup(R.string.settings_readability) {
                ColorPalettePicker(settings, pt.tiago.neonline.data.BoardThemes.resolve(settings.skin, stars)) { onChange(settings.copy(colorPalette = it)) }
                SettingsToggle(R.string.settings_symbols, settings.symbolsOnPieces) { onChange(settings.copy(symbolsOnPieces = it)) }
                SettingsToggle(R.string.settings_motion, settings.reducedMotion) { onChange(settings.copy(reducedMotion = it)) }
                SettingsToggle(R.string.settings_large_text, settings.largerText) { onChange(settings.copy(largerText = it)) }
            }
            SettingsGroup(R.string.theme_title) {
                BoardThemePicker(pt.tiago.neonline.data.BoardThemes.resolve(settings.skin, stars), stars, settings.symbolsOnPieces, onTheme, colorPalette = settings.colorPalette)
            }
            SettingsGroup(R.string.backup_title) {
                Text(stringResource(R.string.backup_note), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onExport, enabled = !backupBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.backup_export))
                }
                OutlinedButton(onClick = onImport, enabled = !backupBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.backup_import))
                }
                Text(stringResource(R.string.diagnostic_note), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onDiagnostic, enabled = !backupBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.diagnostic_export))
                }
                TextButton(onClick = onReset, enabled = !backupBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.reset_title), color = MaterialTheme.colorScheme.error)
                }
                if (backupBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Android 13+ per-app language. Older Android versions follow the system language; the app ships
 * English (default) and Portuguese. Changing it recreates the activity, as the system does.
 */
@androidx.annotation.RequiresApi(33)
@Composable
private fun LanguagePicker() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember(context) { context.getSystemService(android.app.LocaleManager::class.java) }
    val current = remember(manager) { manager.applicationLocales.toLanguageTags().substringBefore(',').substringBefore('-') }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("" to R.string.settings_language_system, "en" to R.string.settings_language_en, "pt" to R.string.settings_language_pt).forEach { (tag, label) ->
            FilterChip(selected = current == tag, onClick = {
                if (current != tag) manager.applicationLocales =
                    if (tag.isEmpty()) android.os.LocaleList.getEmptyLocaleList() else android.os.LocaleList.forLanguageTags(tag)
            }, label = { Text(stringResource(label)) })
        }
    }
}

@Composable
private fun SettingsGroup(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun SettingsToggle(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    // A single labelled control avoids separate, duplicate TalkBack focus targets.
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
