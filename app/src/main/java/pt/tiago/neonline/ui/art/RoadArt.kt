package pt.tiago.neonline.ui.art

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.ui.theme.Amber

/** Decorative animation has no engine stream and is entirely absent in reduced-motion mode. */
@Composable
fun RoadArt(reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val phase = if (reducedMotion) 0f else {
        val transition = rememberInfiniteTransition(label = "road")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(8_000, easing = LinearEasing)), label = "road-markings").value
    }
    Canvas(modifier.clearAndSetSemantics {}) {
        val center = size.height - 24.dp.toPx()
        repeat(40) { star ->
            val x = ((star * 73 + 19) % 997) / 997f * size.width
            val y = ((star * 137 + 29) % 991) / 991f * (center - 32.dp.toPx()).coerceAtLeast(0f)
            drawCircle(Color.White.copy(alpha = .12f), if (star % 5 == 0) 1.5.dp.toPx() else .7.dp.toPx(), Offset(x,y))
        }
        for (offset in listOf(-19,19)) drawLine(Color.White.copy(alpha = .1f), Offset(0f, center + offset.dp.toPx()), Offset(size.width,center + offset.dp.toPx()), 1.dp.toPx())
        val stride = 36.dp.toPx()
        var x = -stride + phase * stride
        while (x < size.width) {
            drawLine(Amber.copy(alpha = .13f), Offset(x,center), Offset(x+18.dp.toPx(),center), 2.dp.toPx())
            x += stride
        }
    }
}
