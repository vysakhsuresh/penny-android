package app.penny.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.penny.R
import app.penny.domain.Appearance
import app.penny.domain.ThemeMode

/**
 * Penny design tokens (UX-06). One accent; amber for "near" or "ahead of pace", orange for "over". Never red for money
 * states: red is reserved for form validation. Light and dark are designed separately, not inverted. Contrast of every
 * text pair meets WCAG 2.2 AA (4.5:1 body, 3:1 large); the high-contrast theme goes to 7:1.
 */
@Immutable
data class StatusColors(
    val near: Color,
    val onNear: Color,
    val nearContainer: Color,
    val over: Color,
    val onOver: Color,
    val overContainer: Color,
    val positive: Color,
    val positiveContainer: Color,
    val track: Color,
)

@Immutable
data class Spacing(val xs: Int = 4, val s: Int = 8, val m: Int = 12, val l: Int = 16, val xl: Int = 24, val xxl: Int = 32, val gutter: Int = 16, val row: Int = 64)

@Immutable
data class PennyExtras(
    val status: StatusColors,
    val spacing: Spacing,
    val dense: Boolean,
    val reduceMotion: Boolean,
    val haptics: Boolean,
    val hideAmounts: Boolean,
    /** Stable palette for entities (categories, people, accounts) — never reuses semantic colours (UX-06). */
    val entityPalette: List<Color>,
    val isDark: Boolean,
)

/** Money-in green: a status colour (positive), no longer the brand accent. */
private val Accent = Color(0xFF007D54)
private val AccentDark = Color(0xFF52E0A6)

/**
 * Brand: plum with gold, matching the launcher icon. Plum carries actions and selection; gold is a sparing highlight
 * (tertiary). Neutrals lean slightly violet so surfaces and the accent belong together. All text pairs meet AA:
 * white on plum 9.6:1, plum on background 9.2:1; in dark, lavender on background 9.9:1.
 */
private val Plum = Color(0xFF5B2D8E)
private val PlumDark = Color(0xFFD4B8FF)

private val LightScheme = lightColorScheme(
    primary = Plum, onPrimary = Color.White, primaryContainer = Color(0xFFEFE4FB), onPrimaryContainer = Color(0xFF24093F),
    secondary = Color(0xFF5E5470), onSecondary = Color.White, secondaryContainer = Color(0xFFE9E2F2), onSecondaryContainer = Color(0xFF1C1527),
    tertiary = Color(0xFF7A5600), onTertiary = Color.White, tertiaryContainer = Color(0xFFFFE6A8), onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFF8F6FB), onBackground = Color(0xFF1C1724),
    surface = Color(0xFFF8F6FB), onSurface = Color(0xFF1C1724),
    surfaceVariant = Color(0xFFE7E1EE), onSurfaceVariant = Color(0xFF4D4659),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF3F0F8), surfaceContainer = Color(0xFFEEEAF4),
    surfaceContainerHigh = Color(0xFFE8E3EF), surfaceContainerHighest = Color(0xFFE2DCEA),
    outline = Color(0xFF7B7387), outlineVariant = Color(0xFFCEC6DA),
    error = Color(0xFFB3261E), onError = Color.White, errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF312B39), inverseOnSurface = Color(0xFFF3EEF8), inversePrimary = PlumDark,
)

private val DarkScheme = darkColorScheme(
    primary = PlumDark, onPrimary = Color(0xFF3A1468), primaryContainer = Color(0xFF41206B), onPrimaryContainer = Color(0xFFEDDFFF),
    secondary = Color(0xFFCDC1DC), onSecondary = Color(0xFF332B40), secondaryContainer = Color(0xFF3B3348), onSecondaryContainer = Color(0xFFE9DEF7),
    tertiary = Color(0xFFF2C14E), onTertiary = Color(0xFF3D2B00), tertiaryContainer = Color(0xFF5A4200), onTertiaryContainer = Color(0xFFFFE6A8),
    background = Color(0xFF141118), onBackground = Color(0xFFE9E3EE),
    surface = Color(0xFF141118), onSurface = Color(0xFFE9E3EE),
    surfaceVariant = Color(0xFF4A4352), onSurfaceVariant = Color(0xFFCBC3D3),
    surfaceContainerLowest = Color(0xFF0F0C13), surfaceContainerLow = Color(0xFF1B1721), surfaceContainer = Color(0xFF201B26),
    surfaceContainerHigh = Color(0xFF2A2431), surfaceContainerHighest = Color(0xFF352E3C),
    outline = Color(0xFF958D9F), outlineVariant = Color(0xFF4A4352),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Color(0xFFE9E3EE), inverseOnSurface = Color(0xFF312B39), inversePrimary = Plum,
)

private val BlackScheme = DarkScheme.copy(
    background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0B0D0C),
    surfaceContainer = Color(0xFF111413), surfaceContainerHigh = Color(0xFF181C1A), surfaceContainerHighest = Color(0xFF202523),
)

private val HighContrastScheme = lightColorScheme(
    primary = Color(0xFF34105C), onPrimary = Color.White, primaryContainer = Color(0xFF34105C), onPrimaryContainer = Color.White,
    secondary = Color(0xFF221A2D), onSecondary = Color.White, secondaryContainer = Color(0xFF221A2D), onSecondaryContainer = Color.White,
    background = Color.White, onBackground = Color.Black, surface = Color.White, onSurface = Color.Black,
    surfaceVariant = Color(0xFFE8E8E8), onSurfaceVariant = Color(0xFF1A1A1A), outline = Color(0xFF000000), outlineVariant = Color(0xFF4A4A4A),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFEAEAEA), surfaceContainerHighest = Color(0xFFE0E0E0),
    error = Color(0xFF8C0009), onError = Color.White,
)

private val LightStatus = StatusColors(
    near = Color(0xFF7A4A00), onNear = Color.White, nearContainer = Color(0xFFFFE6BF),
    over = Color(0xFF9A3D00), onOver = Color.White, overContainer = Color(0xFFFFDCC7),
    positive = Accent, positiveContainer = Color(0xFFCFF1E0), track = Color(0xFFE4DFEB),
)
private val DarkStatus = StatusColors(
    near = Color(0xFFFFC46B), onNear = Color(0xFF3A2600), nearContainer = Color(0xFF4A3300),
    over = Color(0xFFFFA06A), onOver = Color(0xFF3D1600), overContainer = Color(0xFF5A2600),
    positive = AccentDark, positiveContainer = Color(0xFF0F4E37), track = Color(0xFF2E2836),
)
private val HighContrastStatus = LightStatus.copy(near = Color(0xFF5C3700), over = Color(0xFF7A2F00), track = Color(0xFFBDBDBD))

/**
 * Categorical hues in fixed order (blue, aqua, magenta, green, violet) from the validated reference palette; its
 * orange, yellow and red slots are left out because amber/orange are Penny's status colours and red is never used
 * for money. Charts show at most five series plus a neutral "Other"; identity is also carried by labels.
 */
private val LightPalette = listOf(0xFF2A78D6, 0xFF1BAF7A, 0xFFE87BA4, 0xFF008300, 0xFF6250D6).map(::Color)
private val DarkPalette = listOf(0xFF3987E5, 0xFF199E70, 0xFFD55181, 0xFF0CA30C, 0xFF9085E9).map(::Color)
val ChartOtherLight = Color(0xFF8A8F8B)
val ChartOtherDark = Color(0xFF6F7571)

val LocalPenny = staticCompositionLocalOf {
    PennyExtras(LightStatus, Spacing(), dense = false, reduceMotion = false, haptics = true, hideAmounts = false, entityPalette = LightPalette, isDark = false)
}

/** Kept for existing call sites. */
val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** Plus Jakarta Sans (OFL), bundled as one variable font so it works offline (not the font used by LayerLink). */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun weight(w: Int) = Font(R.font.plus_jakarta_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Jakarta = FontFamily(weight(400), weight(500), weight(600), weight(700), weight(800))

/** Amounts use tabular figures so columns of money align. */
val TabularNumbers = "tnum, lnum"

private fun typography(): Typography {
    val base = Typography()
    // Balanced line breaking everywhere: headings split evenly, paragraphs avoid a lone last word (no ragged,
    // "programmer" wraps). Hyphenation stays off so names and amounts are never split.
    fun TextStyle.j(w: Int, size: Int? = null, line: Int? = null, track: Double? = null, heading: Boolean = false) = copy(
        fontFamily = Jakarta, fontWeight = FontWeight(w),
        fontSize = size?.sp ?: fontSize, lineHeight = line?.sp ?: lineHeight, letterSpacing = track?.sp ?: letterSpacing,
        lineBreak = if (heading) androidx.compose.ui.text.style.LineBreak.Heading else androidx.compose.ui.text.style.LineBreak.Paragraph,
        hyphens = androidx.compose.ui.text.style.Hyphens.None,
    )
    return Typography(
        displayLarge = base.displayLarge.j(700, 52, 60, -1.0, heading = true),
        displayMedium = base.displayMedium.j(700, 44, 52, -0.8, heading = true),
        displaySmall = base.displaySmall.j(700, 36, 44, -0.6, heading = true),
        headlineLarge = base.headlineLarge.j(700, 30, 38, -0.4, heading = true),
        headlineMedium = base.headlineMedium.j(700, 26, 34, -0.3, heading = true),
        headlineSmall = base.headlineSmall.j(700, 22, 30, -0.2, heading = true),
        titleLarge = base.titleLarge.j(700, 20, 28, -0.1, heading = true),
        titleMedium = base.titleMedium.j(600, 16, 24, 0.0, heading = true),
        titleSmall = base.titleSmall.j(700, 14, 20, 0.1, heading = true),
        bodyLarge = base.bodyLarge.j(500, 16, 24, 0.0),
        bodyMedium = base.bodyMedium.j(500, 14, 20, 0.0),
        bodySmall = base.bodySmall.j(500, 12, 16, 0.1),
        labelLarge = base.labelLarge.j(600, 14, 20, 0.1),
        labelMedium = base.labelMedium.j(600, 12, 16, 0.3),
        labelSmall = base.labelSmall.j(700, 11, 16, 0.5),
    )
}

private val PennyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun PennyTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val mode = appearance.theme
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT, ThemeMode.HIGH_CONTRAST -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
    }
    var scheme: ColorScheme = when (mode) {
        ThemeMode.HIGH_CONTRAST -> HighContrastScheme
        ThemeMode.BLACK -> BlackScheme
        else -> if (dark) DarkScheme else LightScheme
    }
    if (appearance.accent != 0L && mode != ThemeMode.HIGH_CONTRAST) {
        // A chosen accent recolours the whole family (containers too), and is lightened for dark backgrounds,
        // so a green or blue choice never sits on plum containers or disappears on black.
        val a = Color(appearance.accent)
        val lerp = androidx.compose.ui.graphics.lerp(a, Color.White, 0f)
        scheme = if (dark) scheme.copy(
            primary = androidx.compose.ui.graphics.lerp(lerp, Color.White, 0.55f), onPrimary = androidx.compose.ui.graphics.lerp(a, Color.Black, 0.6f),
            primaryContainer = androidx.compose.ui.graphics.lerp(a, Color.Black, 0.55f), onPrimaryContainer = androidx.compose.ui.graphics.lerp(a, Color.White, 0.85f),
        ) else scheme.copy(
            primary = a, onPrimary = Color.White,
            primaryContainer = androidx.compose.ui.graphics.lerp(a, Color.White, 0.86f), onPrimaryContainer = androidx.compose.ui.graphics.lerp(a, Color.Black, 0.7f),
        )
    }
    val status = when (mode) {
        ThemeMode.HIGH_CONTRAST -> HighContrastStatus
        else -> if (dark) DarkStatus else LightStatus
    }
    val extras = PennyExtras(
        status = status,
        spacing = if (appearance.dense) Spacing(xs = 2, s = 6, m = 8, l = 12, xl = 18, xxl = 24, gutter = 12, row = 52) else Spacing(),
        dense = appearance.dense, reduceMotion = appearance.reduceMotion, haptics = appearance.haptics, hideAmounts = appearance.hideAmounts,
        entityPalette = if (dark) DarkPalette else LightPalette, isDark = dark,
    )
    val density = LocalDensity.current
    // System bar icons follow Penny's theme, not the phone's: light theme on a dark phone left white icons on white.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(
        LocalPenny provides extras,
        LocalStatusColors provides status,
        LocalDensity provides Density(density.density, (density.fontScale * appearance.textScale).coerceIn(0.85f, 2.0f)),
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography(), shapes = PennyShapes, content = content)
    }
}

object Penny {
    val extras: PennyExtras @Composable get() = LocalPenny.current
    val status: StatusColors @Composable get() = LocalPenny.current.status
    val spacing: Spacing @Composable get() = LocalPenny.current.spacing
    /** Gold for chart marks: the brand gold in dark, a deeper gold on light surfaces (3:1 against white). */
    val gold: Color @Composable get() = if (LocalPenny.current.isDark) MaterialTheme.colorScheme.tertiary else Color(0xFFB38600)
    /** Card and row surface: white on the light page, one step above the page in dark (never below it). */
    val card: Color @Composable get() = if (LocalPenny.current.isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerLowest
}
