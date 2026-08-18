package com.monitor.intelligentflow.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Clean Light Theme
private val AppColorScheme = lightColorScheme(
    primary = BluePrimary,
    onPrimary = SurfaceLight,
    primaryContainer = BlueLight,
    onPrimaryContainer = BluePrimary,
    
    secondary = PurpleAccent,
    onSecondary = SurfaceLight,
    
    tertiary = GreenSuccess,
    onTertiary = SurfaceLight,
    
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    
    surfaceVariant = SurfaceElevatedLight,
    onSurfaceVariant = TextSecondaryLight,
    
    surfaceContainer = SurfaceLight,
    surfaceContainerHigh = SurfaceElevatedLight,
    surfaceContainerHighest = SurfaceHighlightLight,
    
    outline = OutlineLightBorder,
    outlineVariant = OutlineVariantLight,
    
    error = RedError,
    onError = SurfaceLight
)

@Composable
fun IntelligentFlowMonitorTheme(
    darkTheme: Boolean = false, // Force light
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = AppTypography,
        content = content
    )
}
