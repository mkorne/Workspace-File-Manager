package com.example.workspacefilemanager.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

private val LightScheme = lightColorScheme(
    primary          = Color(0xFF1B6EF3),
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFD8E6FF),
    onPrimaryContainer = Color(0xFF001943),
    secondary        = Color(0xFF5B5E71),
    onSecondary      = Color.White,
    secondaryContainer = Color(0xFFE0E2F0),
    onSecondaryContainer = Color(0xFF181B2A),
    tertiary         = Color(0xFF77536D),
    onTertiary       = Color.White,
    tertiaryContainer = Color(0xFFFFD7E9),
    onTertiaryContainer = Color(0xFF2D1128),
    background       = Color(0xFFFBF8FF),
    onBackground     = Color(0xFF1A1C20),
    surface          = Color(0xFFFBF8FF),
    onSurface        = Color(0xFF1A1C20),
    surfaceVariant   = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF45464F),
    outline          = Color(0xFF76767F),
    error            = Color(0xFFBA1A1A),
    onError          = Color.White,
    errorContainer   = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkScheme = darkColorScheme(
    primary          = Color(0xFFAEC6FF),
    onPrimary        = Color(0xFF002D77),
    primaryContainer = Color(0xFF194DAA),
    onPrimaryContainer = Color(0xFFD8E6FF),
    secondary        = Color(0xFFC3C5DC),
    onSecondary      = Color(0xFF2D3042),
    secondaryContainer = Color(0xFF434759),
    onSecondaryContainer = Color(0xFFE0E2F0),
    tertiary         = Color(0xFFE5B9D9),
    onTertiary       = Color(0xFF44263F),
    tertiaryContainer = Color(0xFF5D3A56),
    onTertiaryContainer = Color(0xFFFFD7E9),
    background       = Color(0xFF1A1C20),
    onBackground     = Color(0xFFE2E1E9),
    surface          = Color(0xFF1A1C20),
    onSurface        = Color(0xFFE2E1E9),
    surfaceVariant   = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0),
    outline          = Color(0xFF90909A),
    error            = Color(0xFFFFB4AB),
    onError          = Color(0xFF690005),
    errorContainer   = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

enum class ThemeMode { System, Light, Dark }

@Composable
fun WorkspaceTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light  -> false
        ThemeMode.Dark   -> true
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkScheme
        else      -> LightScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.value.toInt()
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography(),
        content     = content
    )
}
