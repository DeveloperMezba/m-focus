package com.focusguard.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFF7FD1C4), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF00504A), onPrimaryContainer = Color(0xFFA6F2E4),
    background = Color(0xFF0F1419), onBackground = Color(0xFFE1E3E8),
    surface = Color(0xFF171C23), onSurface = Color(0xFFE1E3E8),
    surfaceVariant = Color(0xFF262D36), onSurfaceVariant = Color(0xFF8A929E),
    outline = Color(0xFF3A424D),
)

@Composable
fun FocusTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Scheme, content = content)
