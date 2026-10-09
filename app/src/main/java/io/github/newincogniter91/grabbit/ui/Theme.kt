package io.github.newincogniter91.grabbit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val Red = Color(0xFFE22A3C)

data class GrabbitColors(
    val panel: Color,
    val muted: Color,
    val blue: Color,
    val blueBg: Color,
)

private val DarkColors = GrabbitColors(
    panel = Color(0xFF2A2D35),
    muted = Color(0xFF9AA0AC),
    blue = Color(0xFF4C8DF6),
    blueBg = Color(0xFF2D3F5C),
)

private val LightColors = GrabbitColors(
    panel = Color(0xFFFFFFFF),
    muted = Color(0xFF5F6672),
    blue = Color(0xFF2563C9),
    blueBg = Color(0xFFDCE8FB),
)

private val LocalGrabbitColors = staticCompositionLocalOf { DarkColors }

val Panel: Color @Composable get() = LocalGrabbitColors.current.panel
val Muted: Color @Composable get() = LocalGrabbitColors.current.muted
val Blue: Color @Composable get() = LocalGrabbitColors.current.blue
val BlueBg: Color @Composable get() = LocalGrabbitColors.current.blueBg

@Composable
fun GrabbitTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = Red,
            onPrimary = Color.White,
            secondary = colors.blue,
            background = Color(0xFF1C1E24),
            surface = colors.panel,
            onBackground = Color.White,
            onSurface = Color.White,
            surfaceVariant = colors.panel,
            onSurfaceVariant = colors.muted,
            outline = Color(0xFF3A3E48),
        )
    } else {
        lightColorScheme(
            primary = Red,
            onPrimary = Color.White,
            secondary = colors.blue,
            background = Color(0xFFEEF0F4),
            surface = colors.panel,
            onBackground = Color(0xFF1C1E24),
            onSurface = Color(0xFF1C1E24),
            surfaceVariant = colors.panel,
            onSurfaceVariant = colors.muted,
            outline = Color(0xFFC9CDD6),
        )
    }
    CompositionLocalProvider(LocalGrabbitColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
