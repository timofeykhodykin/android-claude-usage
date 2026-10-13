package dev.claudeusage.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.claudeusage.R
import dev.claudeusage.data.ThemeMode
import dev.claudeusage.ui.Palette

// Like borderless: a white page with warm, flat cards; the card colour is `surfaceContainer`, a stronger one
// (tracks, selected chips' neighbours) `surfaceContainerHighest`.
private val LightColors = lightColorScheme(
    primary = Color(0xFFC96442),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF9E3DA),
    onPrimaryContainer = Color(0xFFA9472A),
    secondary = Color(0xFF8A817A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2EEE9),
    onSecondaryContainer = Color(0xFF2B2422),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF2B2422),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF2B2422),
    surfaceVariant = Color(0xFFF7F4F1),
    onSurfaceVariant = Color(0xFF8A807A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF9F7),
    surfaceContainer = Color(0xFFF8F5F2),
    surfaceContainerHigh = Color(0xFFF1EDE9),
    surfaceContainerHighest = Color(0xFFEAE4DF),
    outline = Color(0xFFD9D1CB),
    outlineVariant = Color(0xFFEBE5E0),
    error = Color(0xFFC2453A),
    errorContainer = Color(0xFFFBE1DD),
    onErrorContainer = Color(0xFF7A231B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE08A6D),
    onPrimary = Color(0xFF2B130B),
    primaryContainer = Color(0xFF4A2A20),
    onPrimaryContainer = Color(0xFFF4B39C),
    secondary = Color(0xFFA6A39A),
    onSecondary = Color(0xFF1F1E1D),
    secondaryContainer = Color(0xFF2E2C2A),
    onSecondaryContainer = Color(0xFFF5F4EE),
    background = Color(0xFF181716),
    onBackground = Color(0xFFF2EFEA),
    surface = Color(0xFF181716),
    onSurface = Color(0xFFF2EFEA),
    surfaceVariant = Color(0xFF242321),
    onSurfaceVariant = Color(0xFFA29E96),
    surfaceContainerLowest = Color(0xFF121110),
    surfaceContainerLow = Color(0xFF1E1D1B),
    surfaceContainer = Color(0xFF242321),
    surfaceContainerHigh = Color(0xFF2C2B28),
    surfaceContainerHighest = Color(0xFF363431),
    outline = Color(0xFF4A4743),
    outlineVariant = Color(0xFF34322F),
    error = Color(0xFFF08A80),
    errorContainer = Color(0xFF4E1D18),
    onErrorContainer = Color(0xFFFFDAD5),
)

/** PT Serif (ParaType, SIL OFL): headings, card titles, numbers, the app name — like borderless. */
val Display = FontFamily(
    Font(R.font.pt_serif_regular, FontWeight.Normal),
    Font(R.font.pt_serif_bold, FontWeight.Bold),
)

/** PT Sans (ParaType, SIL OFL): all running text. */
val Body = FontFamily(
    Font(R.font.pt_sans_regular, FontWeight.Normal),
    Font(R.font.pt_sans_bold, FontWeight.Bold),
)

private val AppTypography = Typography().let { base ->
    // PT Sans is compact vertically; a 1.35 line height keeps two-line text from feeling cramped.
    fun TextStyle.body(weight: FontWeight = FontWeight.Normal) = copy(fontFamily = Body, fontWeight = weight, lineHeight = 1.35.em)
    fun TextStyle.display() = copy(fontFamily = Display, fontWeight = FontWeight.Bold)
    base.copy(
        displayLarge = base.displayLarge.display(),
        displayMedium = base.displayMedium.display(),
        displaySmall = base.displaySmall.display(),
        headlineLarge = base.headlineLarge.display(),
        headlineMedium = base.headlineMedium.display(),
        headlineSmall = base.headlineSmall.display(),
        titleLarge = base.titleLarge.copy(fontSize = 20.sp).display(),
        titleMedium = base.titleMedium.copy(fontSize = 16.sp, lineHeight = 21.sp).display(),
        titleSmall = base.titleSmall.copy(fontSize = 14.sp).body(FontWeight.Bold),
        bodyLarge = base.bodyLarge.copy(fontSize = 15.sp).body(),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp).body(),
        bodySmall = base.bodySmall.copy(fontSize = 12.5.sp).body(),
        labelLarge = base.labelLarge.copy(fontSize = 14.sp).body(FontWeight.Bold),
        labelMedium = base.labelMedium.copy(fontSize = 12.sp).body(FontWeight.Bold),
        labelSmall = base.labelSmall.body(),
    )
}

/** Percentages: PT Sans bold, sized to sit level with the card title and subtitle. */
val NumberStyle = TextStyle(fontFamily = Body, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")

/** The palette the shared drawing code (bars, chart) uses inside the app. */
val LocalPalette = staticCompositionLocalOf { Palette.claudeLight }

@Composable
fun ClaudeUsageTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && dark -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val palette = when {
        dynamicColor -> Palette.dynamic(context, dark).copy(background = colors.surfaceContainer.toArgb())
        dark -> Palette.claudeDark.copy(background = colors.surfaceContainer.toArgb())
        else -> Palette.claudeLight.copy(background = colors.surfaceContainer.toArgb())
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
    }
}
