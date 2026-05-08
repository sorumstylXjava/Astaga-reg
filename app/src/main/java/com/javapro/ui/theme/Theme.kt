package com.javapro.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.javapro.utils.PreferenceManager

@Composable
fun JavaProTheme(
    prefManager: PreferenceManager,
    content: @Composable () -> Unit
) {
    val isDarkPref by prefManager.darkModeFlow.collectAsState(initial = true)
    val context = LocalContext.current

    val baseScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDarkPref) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDarkPref -> darkColorScheme()
        else -> lightColorScheme()
    }

    val colorScheme = if (isDarkPref) {
        baseScheme.copy(
            background = Color.Transparent,
            surface    = Color.Transparent
        )
    } else baseScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.navigationBarColor = Color.Transparent.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightNavigationBars = !isDarkPref
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
