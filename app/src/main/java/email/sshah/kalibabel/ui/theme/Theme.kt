package email.sshah.kalibabel.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val BabelBlue = Color(0xFF0B3C5D)
private val BabelYellow = Color(0xFFFFD23F)
private val BabelTeal = Color(0xFF328CC1)

private val LightColors = lightColorScheme(
    primary = BabelBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE5FF),
    onPrimaryContainer = Color(0xFF001D33),
    secondary = Color(0xFF7A5900),
    onSecondary = Color.White,
    secondaryContainer = BabelYellow,
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = BabelTeal,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ACBFF),
    onPrimary = Color(0xFF003354),
    primaryContainer = Color(0xFF1A4A6E),
    onPrimaryContainer = Color(0xFFCFE5FF),
    secondary = BabelYellow,
    onSecondary = Color(0xFF3F2E00),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = Color(0xFFFFDF9A),
    tertiary = Color(0xFF8ECDFF),
)

@Composable
fun KaliBabelTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
