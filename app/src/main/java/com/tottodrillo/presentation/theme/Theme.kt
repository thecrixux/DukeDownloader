package com.tottodrillo.presentation.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DukeGoldScheme = darkColorScheme(
    primary = Primary, onPrimary = OnPrimary,
    primaryContainer = PrimaryVariant, onPrimaryContainer = Primary,
    secondary = Primary, onSecondary = OnPrimary,
    secondaryContainer = PrimaryVariant, onSecondaryContainer = Primary,
    tertiary = Secondary, onTertiary = OnSecondary,
    tertiaryContainer = Color(0xFF392617), onTertiaryContainer = Highlight,
    background = Background, onBackground = OnBackground,
    surface = Surface, onSurface = OnSurface,
    surfaceVariant = SurfaceVariant, onSurfaceVariant = OnSurfaceVariant,
    surfaceTint = Primary,
    outline = OnSurfaceVariant, outlineVariant = Color(0xFF45454D),
    inverseSurface = OnSurface, inverseOnSurface = Background,
    inversePrimary = Color(0xFF735100),
    error = Error, onError = Background,
    errorContainer = Color(0xFF4A2020), onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black
)

@Suppress("UNUSED_PARAMETER")
@Composable
fun TottodrilloTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = Background.toArgb()
                window.navigationBarColor = Background.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
    }
    MaterialTheme(colorScheme = DukeGoldScheme, typography = Typography, content = content)
}
