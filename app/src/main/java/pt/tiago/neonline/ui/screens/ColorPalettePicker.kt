package pt.tiago.neonline.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.R
import pt.tiago.neonline.data.Settings
import pt.tiago.neonline.ui.art.*

private fun ColorPalette.labelResource(): Int = when (this) {
    ColorPalette.NORMAL -> R.string.palette_normal
    ColorPalette.DEUTERANOPIA -> R.string.palette_deuteranopia
    ColorPalette.PROTANOPIA -> R.string.palette_protanopia
    ColorPalette.TRITANOPIA -> R.string.palette_tritanopia
}

@Composable
internal fun ColorPalettePicker(settings: Settings, skin: BoardSkin, onSelect: (String) -> Unit) {
    Text(stringResource(R.string.palette_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.palette_note), style = MaterialTheme.typography.bodySmall)
    val selected = ColorPalette.resolve(settings.colorPalette)
    Column(Modifier.selectableGroup()) {
        for (palette in ColorPalette.entries) {
            Row(Modifier.fillMaxWidth().selectable(selected == palette, role = Role.RadioButton,
                onClick = { onSelect(palette.key) }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == palette, onClick = null)
                Text(stringResource(palette.labelResource()), modifier = Modifier.weight(1f).padding(start = 12.dp))
            }
        }
    }
    // Named players make this preview readable without depending on the color alone.
    val colors = BoardStyles.palette(skin, selected.key)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.palette_player, 1), color = Color(colors.p1), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.palette_player, 2), color = Color(colors.p2), style = MaterialTheme.typography.titleMedium)
    }
}
