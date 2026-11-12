package cz.localvoice.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

private val LightColors = lightColorScheme(
    primary = Color(0xFF245BDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2EBFF),
    onPrimaryContainer = Color(0xFF16243C),
    secondary = Color(0xFF14735A),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF16243C),
    surface = Color.White,
    onSurface = Color(0xFF16243C),
    surfaceVariant = Color(0xFFE8EDF5),
    onSurfaceVariant = Color(0xFF46556D),
    outline = Color(0xFF6D7B90),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF102C70),
    primaryContainer = Color(0xFF233D70),
    onPrimaryContainer = Color(0xFFE2EBFF),
    secondary = Color(0xFF82D7B5),
    background = Color(0xFF0F192B),
    onBackground = Color(0xFFEEF3FC),
    surface = Color(0xFF16243C),
    onSurface = Color(0xFFEEF3FC),
    surfaceVariant = Color(0xFF27364D),
    onSurfaceVariant = Color(0xFFC0CBDD),
    outline = Color(0xFF929FB3),
    error = Color(0xFFFFB4AB),
)

@Composable
fun SpeakTheme(content: @Composable () -> Unit) {
    val typography = Typography()
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = typography.copy(
            labelSmall = typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        ),
        content = content,
    )
}
