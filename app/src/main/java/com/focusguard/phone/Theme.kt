package com.focusguard.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Background choices (Settings > Appearance). Used by the app AND the lock screen. */
data class Palette(val name: String, val top: Int, val mid: Int, val bottom: Int)

val Palettes = listOf(
    Palette("Charcoal purple", 0xFF1B1A21.toInt(), 0xFF252330.toInt(), 0xFF2E2A3B.toInt()),
    Palette("Charcoal", 0xFF1F2024.toInt(), 0xFF25262B.toInt(), 0xFF2A2B30.toInt()),
    Palette("Gray", 0xFF2A2B30.toInt(), 0xFF34353B.toInt(), 0xFF3E3F46.toInt()),
)

fun gradientFor(idx: Int): Brush {
    val p = Palettes[idx.coerceIn(0, Palettes.lastIndex)]
    return Brush.verticalGradient(listOf(Color(p.top), Color(p.mid), Color(p.bottom)))
}

private val Scheme = darkColorScheme(
    primary = Color.White, onPrimary = Color(0xFF1B1A21),
    primaryContainer = Color(0x33FFFFFF), onPrimaryContainer = Color.White,
    secondaryContainer = Color(0x33FFFFFF), onSecondaryContainer = Color.White,
    background = Color(0xFF1B1A21), onBackground = Color.White,
    surface = Color(0x1FFFFFFF), onSurface = Color.White,
    surfaceVariant = Color(0x33FFFFFF), onSurfaceVariant = Color(0xB3FFFFFF),
    outline = Color(0x55FFFFFF),
    surfaceContainerHigh = Color(0xFF34313F),
)

@Composable
fun FocusTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Scheme, content = content)
