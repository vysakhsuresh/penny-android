package app.penny.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Penny design tokens (UX-06): one accent; amber for "near" or "ahead of pace", orange for "over". Never red for money
 * states; red is reserved for form validation. Light and dark are designed separately, not inverted.
 */
@Immutable
data class StatusColors(val near: Color, val over: Color, val positive: Color, val onNear: Color)

private val Accent = Color(0xFF1F6F54)
private val AccentDark = Color(0xFF7FD3AE)

private val Light = lightColorScheme(
    primary = Accent, onPrimary = Color.White, primaryContainer = Color(0xFFCDEBDC), onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4D6357), secondaryContainer = Color(0xFFD0E8D8), surface = Color(0xFFF8FAF8),
    surfaceVariant = Color(0xFFDDE5DE), onSurfaceVariant = Color(0xFF3F4943), background = Color(0xFFF8FAF8),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = AccentDark, onPrimary = Color(0xFF003824), primaryContainer = Color(0xFF005237), onPrimaryContainer = Color(0xFF9BF0C9),
    secondary = Color(0xFFB4CCBC), secondaryContainer = Color(0xFF364B3F), surface = Color(0xFF101412),
    surfaceVariant = Color(0xFF3F4943), onSurfaceVariant = Color(0xFFBFC9C1), background = Color(0xFF101412),
    error = Color(0xFFF2B8B5),
)

private val LightStatus = StatusColors(near = Color(0xFF8A5300), over = Color(0xFFA34700), positive = Accent, onNear = Color(0xFFFFF4E0))
private val DarkStatus = StatusColors(near = Color(0xFFFFC069), over = Color(0xFFFFA463), positive = AccentDark, onNear = Color(0xFF3A2600))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val PennyTypography = Typography().let { t ->
    t.copy(displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 40.sp))
}

@Composable
fun PennyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = PennyTypography, content = content)
    }
}
