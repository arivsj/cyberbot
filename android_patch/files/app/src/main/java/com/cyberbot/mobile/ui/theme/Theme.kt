package com.cyberbot.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val CyberColorScheme = darkColorScheme(
    primary = Neon,
    onPrimary = Bg,
    secondary = Neon2,
    tertiary = Neon3,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextDim,
    outline = Border,
    error = Danger,
)

// Uma escala unica de cantos para o app inteiro: antes os cartoes tinham 12dp,
// os baloes do chat 8dp e a barra inferior 18dp.
private val CyberShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CyberBotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CyberColorScheme,
        typography = Typography,
        shapes = CyberShapes,
        content = content,
    )
}
