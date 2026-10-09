package io.github.newincogniter91.grabbit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF1C1E24)
val Panel = Color(0xFF2A2D35)
val Red = Color(0xFFE22A3C)
val Blue = Color(0xFF4C8DF6)
val BlueBg = Color(0xFF2D3F5C)
val Muted = Color(0xFF9AA0AC)

@Composable
fun GrabbitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Red,
            onPrimary = Color.White,
            secondary = Blue,
            background = Bg,
            surface = Panel,
            onBackground = Color.White,
            onSurface = Color.White,
            surfaceVariant = Panel,
            onSurfaceVariant = Muted,
            outline = Color(0xFF3A3E48),
        ),
        content = content,
    )
}
