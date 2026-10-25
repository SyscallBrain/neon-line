package pt.tiago.neonline.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import pt.tiago.neonline.R

val TiltNeon = FontFamily(Font(R.font.tilt_neon, FontWeight.Normal))

val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
)

private val NeonLineTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = TiltNeon,
        fontWeight = FontWeight.Normal,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.02.em,
    ),
    displayMedium = TextStyle(
        fontFamily = TiltNeon,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Figtree,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Figtree,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Figtree,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Figtree,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.14.em,
    ),
    labelSmall = TextStyle(
        fontFamily = Figtree,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.14.em,
    ),
)

private val NeonLineColors = darkColorScheme(
    primary = Pink,
    onPrimary = OnPink,
    secondary = Amber,
    onSecondary = OnPink,
    background = Asphalt,
    onBackground = Text,
    surface = Shoulder,
    onSurface = Text,
    surfaceVariant = Rail,
    onSurfaceVariant = TextDim,
    error = Danger,
    // Cards, dialogs, sheets and chips take these roles; without them Material falls back to its
    // neutral greys, which do not belong on the neon road.
    surfaceTint = Pink,
    surfaceDim = Asphalt,
    surfaceBright = Rail,
    surfaceContainerLowest = Asphalt,
    surfaceContainerLow = Shoulder,
    surfaceContainer = Shoulder,
    surfaceContainerHigh = Shoulder,
    surfaceContainerHighest = Shoulder,
    primaryContainer = Rail,
    onPrimaryContainer = Text,
    secondaryContainer = PinkSelected,
    onSecondaryContainer = Text,
    outline = Outline,
    outlineVariant = Rail,
)

@Composable
fun NeonLineTheme(largerText: Boolean = false, content: @Composable () -> Unit) {
    // Always dark: it is always night on the road. The system theme is ignored on purpose.
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, density.fontScale * if (largerText) 1.15f else 1f),
    ) {
    MaterialTheme(
        colorScheme = NeonLineColors,
        typography = NeonLineTypography,
    ) {
        // The root surface gives every screen the dark background and the light content colour;
        // without it, Text with no explicit colour falls back to black on the dark road.
        androidx.compose.material3.Surface(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            color = NeonLineColors.background,
            contentColor = NeonLineColors.onBackground,
            content = content,
        )
    }
}
}
