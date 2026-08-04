package dev.norsehorse.vaultpony.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = BrassDark,
    onPrimary = BrassOnDark,
    primaryContainer = BrassContainerDark,
    onPrimaryContainer = OnBrassContainerDark,
    secondary = BrassDark,
    onSecondary = BrassOnDark,
    // Tertiary is reserved for the hidden-volume signal.
    tertiary = HiddenVioletDark,
    onTertiary = OnHiddenDark,
    background = BgDark,
    onBackground = TextDark,
    surface = SurfaceDark,
    onSurface = TextDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextMutedDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    error = DangerDark,
    onError = OnDangerDark,
)

private val LightColors = lightColorScheme(
    primary = BrassLight,
    onPrimary = BrassOnLight,
    primaryContainer = BrassContainerLight,
    onPrimaryContainer = OnBrassContainerLight,
    secondary = BrassLight,
    onSecondary = BrassOnLight,
    tertiary = HiddenVioletLight,
    onTertiary = OnHiddenLight,
    background = BgLight,
    onBackground = TextLight,
    surface = SurfaceLight,
    onSurface = TextLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextMutedLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    error = DangerLight,
    onError = OnDangerLight,
)

/** App theme: Hardened Vault, brass accent, follows the system light/dark
 *  setting. No dynamic color — the brand palette is fixed. */
@Composable
fun VaultPonyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = VaultTypography,
        shapes = VaultShapes,
        content = content,
    )
}
