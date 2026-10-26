package lk.safeheaven.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Sh {
    val Ink = Color(0xFF0B0F10)
    val Surface = Color(0xFF14191B)
    val SurfaceHigh = Color(0xFF1C2326)
    val Outline = Color(0xFF2B3639)
    val Teal = Color(0xFF4CB3A8)
    val TealDeep = Color(0xFF14978C)
    val Purple = Color(0xFF7D6FAB)
    val Bone = Color(0xFFF2F4F3)
    val Muted = Color(0xFF9AA6A8)
    val Danger = Color(0xFFFF6B6B)
    val Amber = Color(0xFFF2B84B)
}

private val Dark = darkColorScheme(
    primary = Sh.Teal,
    onPrimary = Sh.Ink,
    secondary = Sh.Purple,
    onSecondary = Sh.Bone,
    background = Sh.Ink,
    onBackground = Sh.Bone,
    surface = Sh.Surface,
    onSurface = Sh.Bone,
    surfaceVariant = Sh.SurfaceHigh,
    onSurfaceVariant = Sh.Muted,
    outline = Sh.Outline,
    error = Sh.Danger,
)

/** High-contrast dark UI is the only mode: it must read at night, in a car, at a glance. */
@Composable
fun SafeHeavenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, typography = ShTypography, content = content)
}
