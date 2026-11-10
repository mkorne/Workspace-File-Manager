package com.example.workspacefilemanager.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ── Theme options ─────────────────────────────────────────────────────────

enum class ThemeMode(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark")
}

/**
 * Accent palettes. Each accent carries explicit light/dark primaries and
 * containers so switching accents produces a noticeable, well-contrasted UI.
 */
enum class AccentColor(
    val label: String,
    val lightPrimary: Color,
    val lightContainer: Color,
    val lightOnContainer: Color,
    val darkPrimary: Color,
    val darkContainer: Color,
    val darkOnContainer: Color
) {
    Blue(
        "Ocean",
        Color(0xFF1B6EF3), Color(0xFFD9E6FF), Color(0xFF001B4A),
        Color(0xFFAECBFF), Color(0xFF113C86), Color(0xFFE2ECFF)
    ),
    Violet(
        "Violet",
        Color(0xFF6C4DF6), Color(0xFFE7DEFF), Color(0xFF1D0B57),
        Color(0xFFC9BCFF), Color(0xFF432C9E), Color(0xFFEDE6FF)
    ),
    Teal(
        "Teal",
        Color(0xFF00897B), Color(0xFFC9F2EC), Color(0xFF00201C),
        Color(0xFF74DACE), Color(0xFF005048), Color(0xFFD2FAF3)
    ),
    Green(
        "Forest",
        Color(0xFF2E7D32), Color(0xFFD7F5D8), Color(0xFF062008),
        Color(0xFF9FDCA2), Color(0xFF1B5E20), Color(0xFFD9F6DA)
    ),
    Amber(
        "Amber",
        Color(0xFF9C5B00), Color(0xFFFFE2B8), Color(0xFF2C1600),
        Color(0xFFFFC978), Color(0xFF5E3600), Color(0xFFFFE7CC)
    ),
    Rose(
        "Rose",
        Color(0xFFC2185B), Color(0xFFFFD9E5), Color(0xFF3F0018),
        Color(0xFFFFB1C8), Color(0xFF7A0E36), Color(0xFFFFDCE7)
    )
}

data class AppTheme(
    val mode: ThemeMode = ThemeMode.System,
    val accent: AccentColor = AccentColor.Blue,
    val dynamicColor: Boolean = false
)

/**
 * Single source of truth for appearance. Activity-level [WorkspaceTheme]
 * observes this flow, so changing a theme in Settings recomposes the whole app
 * immediately instead of waiting for a restart.
 */
object ThemeController {

    private const val PREFS = "app_settings"
    private const val KEY_MODE = "theme_selection"
    private const val KEY_ACCENT = "accent_selection"
    private const val KEY_DYNAMIC = "dynamic_color"

    private val _state = MutableStateFlow(AppTheme())
    val state: StateFlow<AppTheme> = _state.asStateFlow()

    private var initialised = false

    fun init(context: Context) {
        if (initialised) return
        initialised = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = AppTheme(
            mode = ThemeMode.entries.getOrElse(prefs.getInt(KEY_MODE, 0)) { ThemeMode.System },
            accent = AccentColor.entries.getOrElse(prefs.getInt(KEY_ACCENT, 0)) { AccentColor.Blue },
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, false)
        )
    }

    fun setMode(context: Context, mode: ThemeMode) {
        _state.value = _state.value.copy(mode = mode)
        persist(context)
    }

    fun setAccent(context: Context, accent: AccentColor) {
        _state.value = _state.value.copy(accent = accent)
        persist(context)
    }

    fun setDynamicColor(context: Context, enabled: Boolean) {
        _state.value = _state.value.copy(dynamicColor = enabled)
        persist(context)
    }

    private fun persist(context: Context) {
        val current = _state.value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MODE, current.mode.ordinal)
            .putInt(KEY_ACCENT, current.accent.ordinal)
            .putBoolean(KEY_DYNAMIC, current.dynamicColor)
            .apply()
    }
}

// ── Colour schemes ────────────────────────────────────────────────────────

private fun lightScheme(accent: AccentColor) = lightColorScheme(
    primary = accent.lightPrimary,
    onPrimary = Color.White,
    primaryContainer = accent.lightContainer,
    onPrimaryContainer = accent.lightOnContainer,
    secondary = Color(0xFF55606F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE4F2),
    onSecondaryContainer = Color(0xFF121C28),
    tertiary = Color(0xFF6B5A7E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0DFFF),
    onTertiaryContainer = Color(0xFF251432),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF14161B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14161B),
    surfaceVariant = Color(0xFFE7EAF0),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F6FA),
    surfaceContainer = Color(0xFFEFF1F6),
    surfaceContainerHigh = Color(0xFFE9ECF2),
    surfaceContainerHighest = Color(0xFFE3E6EC),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C7CF),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF2E3136),
    inverseOnSurface = Color(0xFFEFF1F6)
)

private fun darkScheme(accent: AccentColor) = darkColorScheme(
    primary = accent.darkPrimary,
    onPrimary = Color(0xFF04122E),
    primaryContainer = accent.darkContainer,
    onPrimaryContainer = accent.darkOnContainer,
    secondary = Color(0xFFBAC8DB),
    onSecondary = Color(0xFF243140),
    secondaryContainer = Color(0xFF3A4758),
    onSecondaryContainer = Color(0xFFD6E4F7),
    tertiary = Color(0xFFD5BFE8),
    onTertiary = Color(0xFF3A2A49),
    tertiaryContainer = Color(0xFF523F61),
    onTertiaryContainer = Color(0xFFF0DFFF),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF171A20),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C7CF),
    surfaceContainerLowest = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF14171C),
    surfaceContainer = Color(0xFF1B1E24),
    surfaceContainerHigh = Color(0xFF22262D),
    surfaceContainerHighest = Color(0xFF2A2E36),
    outline = Color(0xFF8E9199),
    outlineVariant = Color(0xFF44474F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2E3136)
)

// ── Typography & shapes ───────────────────────────────────────────────────

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-0.5).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp
    )
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

// ── Entry point ───────────────────────────────────────────────────────────

@Composable
fun WorkspaceTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val theme by ThemeController.state.collectAsState()
    WorkspaceTheme(theme = theme, content = content)
}

@Composable
fun WorkspaceTheme(
    theme: AppTheme,
    content: @Composable () -> Unit
) {
    val darkTheme = when (theme.mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        theme.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkScheme(theme.accent)
        else -> lightScheme(theme.accent)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
