package com.focusguard.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Background choices (Settings > Appearance). top/mid/bottom = the app; lock* = the focus (lock) screen, always darker. */
data class Palette(
    val name: String, val top: Int, val mid: Int, val bottom: Int,
    val lockTop: Int, val lockMid: Int, val lockBottom: Int,
)

private fun same(name: String, t: Int, m: Int, b: Int) = Palette(name, t, m, b, t, m, b)

val Palettes = listOf(
    same("Charcoal purple", 0xFF1B1A21.toInt(), 0xFF252330.toInt(), 0xFF2E2A3B.toInt()),
    same("Charcoal", 0xFF1F2024.toInt(), 0xFF25262B.toInt(), 0xFF2A2B30.toInt()),
    same("Gray", 0xFF2A2B30.toInt(), 0xFF34353B.toInt(), 0xFF3E3F46.toInt()),
    Palette("Ocean", 0xFF0B3A4A.toInt(), 0xFF11566B.toInt(), 0xFF1B7A8C.toInt(),
        0xFF101A1D.toInt(), 0xFF152227.toInt(), 0xFF1B2D33.toInt()),
    Palette("Ember", 0xFF3A1A22.toInt(), 0xFF692C38.toInt(), 0xFFA8503C.toInt(),
        0xFF1D1417.toInt(), 0xFF261A1D.toInt(), 0xFF302024.toInt()),
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
