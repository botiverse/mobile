package ai.slock.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val SlockLightColorScheme = lightColorScheme(
    primary = SlockColor.BrandYellow,
    onPrimary = SlockColor.TextBlack,
    secondary = SlockColor.AccentPink,
    onSecondary = SlockColor.TextBlack,
    tertiary = SlockColor.AccentCyan,
    onTertiary = SlockColor.TextBlack,
    background = SlockColor.AppBgLightBlue,
    onBackground = SlockColor.TextBlack,
    surface = SlockColor.SurfaceWhite,
    onSurface = SlockColor.TextBlack,
    surfaceVariant = SlockColor.SurfaceCream,
    onSurfaceVariant = SlockColor.TextMutedGray,
    outline = SlockColor.BorderBlack,
    error = SlockColor.AccentRed,
    onError = SlockColor.TextBlack
)

private val SlockDarkColorScheme = darkColorScheme(
    primary = SlockColor.BrandYellow,
    onPrimary = SlockColor.TextBlack,
    secondary = SlockColor.AccentPink,
    onSecondary = SlockColor.TextBlack,
    tertiary = SlockColor.AccentCyan,
    onTertiary = SlockColor.TextBlack,
    background = SlockColor.TextBlack,
    onBackground = SlockColor.SurfaceWhite,
    surface = SlockColor.TextBlack,
    onSurface = SlockColor.SurfaceWhite,
    surfaceVariant = SlockColor.ShadowBlack,
    onSurfaceVariant = SlockColor.EmptyStateGray,
    outline = SlockColor.SurfaceWhite,
    error = SlockColor.AccentRed,
    onError = SlockColor.TextBlack
)

@Composable
fun SlockTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) SlockDarkColorScheme else SlockLightColorScheme,
        typography = Typography,
        content = content
    )
}
