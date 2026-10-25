package pt.tiago.neonline.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pt.tiago.neonline.R

/** The localized display name of a rival; content ids stay language-neutral. */
internal fun rivalNameResource(id: String): Int? = when (id) {
    "sparky" -> R.string.rival_name_sparky
    "dolores" -> R.string.rival_name_dolores
    "flamingo" -> R.string.rival_name_flamingo
    "strike" -> R.string.rival_name_strike
    "projectionist" -> R.string.rival_name_projectionist
    "centrifuge" -> R.string.rival_name_centrifuge
    "skater" -> R.string.rival_name_skater
    "diva" -> R.string.rival_name_diva
    "pixel" -> R.string.rival_name_pixel
    "wheel" -> R.string.rival_name_wheel
    "ace" -> R.string.rival_name_ace
    "queen" -> R.string.rival_name_queen
    else -> null
}

@Composable
internal fun rivalName(id: String): String =
    rivalNameResource(id)?.let { stringResource(it) } ?: id.replaceFirstChar { it.uppercase() }
