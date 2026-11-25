@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.dpadmouse.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import app.dpadmouse.R

/** Dark-only Material 3 Expressive theme; colors come from colors.xml. */
@Composable
fun DpadTheme(content: @Composable () -> Unit) {
    val onSurface = colorResource(R.color.md_on_surface)
    val scheme = darkColorScheme(
        primary = colorResource(R.color.md_primary),
        onPrimary = colorResource(R.color.md_on_primary),
        primaryContainer = colorResource(R.color.md_primary_container),
        onPrimaryContainer = colorResource(R.color.md_on_primary_container),
        secondaryContainer = colorResource(R.color.md_secondary_container),
        onSecondaryContainer = colorResource(R.color.md_on_secondary_container),
        errorContainer = colorResource(R.color.md_error_container),
        onErrorContainer = colorResource(R.color.md_on_error_container),
        background = colorResource(R.color.md_surface),
        onBackground = onSurface,
        surface = colorResource(R.color.md_surface),
        onSurface = onSurface,
        onSurfaceVariant = colorResource(R.color.md_on_surface_variant),
        surfaceContainer = colorResource(R.color.md_surface_container),
        surfaceContainerHigh = colorResource(R.color.md_surface_container_high),
        surfaceContainerHighest = colorResource(R.color.md_surface_container_highest),
        outline = colorResource(R.color.md_outline),
        outlineVariant = colorResource(R.color.md_outline_variant)
    )
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
