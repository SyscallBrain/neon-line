package pt.tiago.neonline.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.data.*
import pt.tiago.neonline.ui.theme.*
import java.text.NumberFormat

@Composable
fun RivalsScreen(profile: Profile, levels: List<RivalLevel>, onChallenge: (Int, Difficulty) -> Unit, onFreePlay: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var difficulty by remember { mutableStateOf(profile.settings.lastDifficulty) }
    val cards = remember(profile, levels, difficulty) { RivalGallery.summarize(profile, levels, difficulty) }
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
        ScreenHeader(stringResource(R.string.nav_rivals), onBack)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.rivals_note), modifier = Modifier.padding(vertical = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Difficulty.entries.forEach { choice -> FilterChip(difficulty == choice, { difficulty = choice }, label = { Text(stringResource(choice.labelResource())) }) }
                }
                Text(stringResource(R.string.rivals_record_note), style = MaterialTheme.typography.bodySmall)
                if (levels.isEmpty()) Text(stringResource(R.string.rivals_content_missing))
            }
            items(cards, key = { it.definition.id }) { card ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Rivals still to meet stay compact and dim, so the ones already met stand out.
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RivalAvatar(card, Modifier.size(if (card.encountered) 64.dp else 44.dp))
                        Column(Modifier.weight(1f)) {
                            if (card.encountered) Text(rivalName(card.definition.id), style = MaterialTheme.typography.titleLarge)
                            else Text(stringResource(R.string.rivals_unknown), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.rivals_stop, card.definition.stop), style = if (card.encountered) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium)
                            Text(stringResource(if (card.bossBeaten) R.string.rivals_completed else if (card.encountered) R.string.rivals_encountered else R.string.rivals_hidden), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (card.encountered) {
                        Text(stringResource(styleResources[card.definition.stop - 1]))
                        Text(stringResource(R.string.rivals_record, numbers.format(card.record.wins), numbers.format(card.record.draws), numbers.format(card.record.losses)))
                        if (card.bossBeaten) {
                            val difficulties = Difficulty.entries.filter { it in card.bossDifficulties }.map { stringResource(it.labelResource()) }.joinToString(", ")
                            Text(stringResource(R.string.rivals_boss_difficulties, difficulties), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(quoteResources[card.definition.stop - 1]), color = TubeBlue)
                            Text(stringResource(signatureResources[card.definition.stop - 1]), style = MaterialTheme.typography.bodySmall)
                        } else Text(stringResource(R.string.rivals_complete_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    if (card.encountered && card.definition.id in FreePlay.supportedRivals && levels.any { it.rival == card.definition.id }) {
                        Button(onClick = { onFreePlay(card.definition.id) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.free_against, rivalName(card.definition.id))) }
                    }
                    card.challengeOrdinal?.let { ordinal ->
                        OutlinedButton(onClick = { onChallenge(ordinal, difficulty) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.rivals_challenge, ordinal))
                        }
                    }
                } }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
internal fun RivalAvatar(card: RivalCard, modifier: Modifier) {
    val color = if (card.bossBeaten) Amber else if (card.encountered) TubeBlue else Color(0xFF667080)
    Canvas(modifier.clearAndSetSemantics { }) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension * .46f
        drawCircle(color.copy(alpha = .12f), radius, center)
        drawCircle(color, radius, center, style = Stroke(if (card.bossBeaten) 3.dp.toPx() else 1.dp.toPx()))
        drawCircle(color, radius * .25f, center.copy(y = size.height * .35f))
        drawArc(color, 180f, 180f, true, Offset(size.width * .22f, size.height * .56f), Size(size.width * .56f, size.height * .38f))
        if (card.encountered) {
            // A stop-specific neon badge; future art can replace it without changing progression.
            for (index in 0 until card.definition.stop.coerceAtMost(12)) {
                val angle = index * (Math.PI * 2 / 12) - Math.PI / 2
                val point = center + Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat()) * (radius * .88f)
                drawCircle(color, 1.5.dp.toPx(), point)
            }
        }
    }
}

internal val styleResources = listOf(R.string.rival_style_1, R.string.rival_style_2, R.string.rival_style_3, R.string.rival_style_4, R.string.rival_style_5, R.string.rival_style_6, R.string.rival_style_7, R.string.rival_style_8, R.string.rival_style_9, R.string.rival_style_10, R.string.rival_style_11, R.string.rival_style_12)
private val quoteResources = listOf(R.string.rival_quote_1, R.string.rival_quote_2, R.string.rival_quote_3, R.string.rival_quote_4, R.string.rival_quote_5, R.string.rival_quote_6, R.string.rival_quote_7, R.string.rival_quote_8, R.string.rival_quote_9, R.string.rival_quote_10, R.string.rival_quote_11, R.string.rival_quote_12)
private val signatureResources = listOf(R.string.rival_signature_1, R.string.rival_signature_2, R.string.rival_signature_3, R.string.rival_signature_4, R.string.rival_signature_5, R.string.rival_signature_6, R.string.rival_signature_7, R.string.rival_signature_8, R.string.rival_signature_9, R.string.rival_signature_10, R.string.rival_signature_11, R.string.rival_signature_12)
