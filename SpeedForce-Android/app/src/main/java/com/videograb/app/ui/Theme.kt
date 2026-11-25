package com.videograb.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.videograb.app.Prefs

/** Speed Force orange (like the Brave lion's orange). */
val Accent = Color(0xFFFB542B)
val AccentDark = Color(0xFFFF7654)
val ShieldGreen = Color(0xFF3DBA6B)

/** Light, Dark or System (the phone's own setting), chosen in Settings. */
val LocalDarkTheme = staticCompositionLocalOf { true }

private val Dark = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF1B1C22),
    secondary = Color(0xFFA0A5FF),
    background = Color(0xFF1B1C22),
    onBackground = Color(0xFFEDEDF1),
    surface = Color(0xFF1B1C22),
    onSurface = Color(0xFFEDEDF1),
    surfaceVariant = Color(0xFF2B2D37),
    onSurfaceVariant = Color(0xFFB6B8C6),
    surfaceContainer = Color(0xFF23242C),
    surfaceContainerHigh = Color(0xFF2B2D37),
    outline = Color(0xFF4A4C5B),
)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFF4C54D2),
    background = Color.White,
    onBackground = Color(0xFF1B1C22),
    surface = Color.White,
    onSurface = Color(0xFF1B1C22),
    surfaceVariant = Color(0xFFF0F1F5),
    onSurfaceVariant = Color(0xFF5A5C6B),
    surfaceContainer = Color(0xFFF6F6F9),
    surfaceContainerHigh = Color(0xFFECEDF2),
    outline = Color(0xFFC9CAD6),
)

@Composable
fun VideoGrabTheme(mode: String = Prefs.themeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }
    val scheme = if (dark) Dark else Light
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = scheme.background.toArgb()
            window.navigationBarColor = scheme.background.toArgb()
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
            window.decorView.setBackgroundColor(scheme.background.toArgb())
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(24.dp),
            ),
            content = content,
        )
    }
}
