package com.focusguard.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Same charcoal + faint purple as the focus (lock) screen, so the whole app looks like one.
val GradTop = Color(0xFF1B1A21)
val GradMid = Color(0xFF252330)
val GradBottom = Color(0xFF2E2A3B)
val AppGradient = Brush.verticalGradient(listOf(GradTop, GradMid, GradBottom))

private val Scheme = darkColorScheme(
    primary = Color.White, onPrimary = Color(0xFF1B1A21),
    primaryContainer = Color(0x33FFFFFF), onPrimaryContainer = Color.White,
    secondaryContainer = Color(0x33FFFFFF), onSecondaryContainer = Color.White,
    background = GradTop, onBackground = Color.White,
    surface = Color(0x1FFFFFFF), onSurface = Color.White,
    surfaceVariant = Color(0x33FFFFFF), onSurfaceVariant = Color(0xB3FFFFFF),
    outline = Color(0x55FFFFFF),
    surfaceContainerHigh = Color(0xFF34313F),
)

@Composable
fun FocusTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Scheme, content = content)
