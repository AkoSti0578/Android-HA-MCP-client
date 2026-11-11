package nl.ako.hachat.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

// "Gieterij": koper, gesmolten metaal en gietijzer.
private val Copper = Color(0xFFB8561F)
private val CopperLight = Color(0xFFFFB68A)
private val Ember = Color(0xFFFFC56B)
private val CastIron = Color(0xFF1E1A17)

private val LightColors = lightColorScheme(
    primary = Copper,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC9),
    onPrimaryContainer = Color(0xFF351000),
    secondary = Color(0xFF765848),
    secondaryContainer = Color(0xFFFFDBC9),
    tertiary = Color(0xFF636032),
    tertiaryContainer = Color(0xFFEAE5AA),
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFF4DED4),
    surfaceContainer = Color(0xFFF8EEE9),
    surfaceContainerHigh = Color(0xFFF2E6E0),
    surfaceContainerHighest = Color(0xFFECE0DA),
)

private val DarkColors = darkColorScheme(
    primary = CopperLight,
    onPrimary = Color(0xFF552000),
    primaryContainer = Color(0xFF8A3A08),
    onPrimaryContainer = Color(0xFFFFDBC9),
    secondary = Color(0xFFE7BEAC),
    secondaryContainer = Color(0xFF5C4132),
    tertiary = Ember,
    tertiaryContainer = Color(0xFF4B481D),
    background = CastIron,
    surface = CastIron,
    surfaceVariant = Color(0xFF52443C),
    surfaceContainer = Color(0xFF2A2420),
    surfaceContainerHigh = Color(0xFF352E2A),
    surfaceContainerHighest = Color(0xFF403934),
)

/** Extra kleuren die Material 3 niet heeft. */
@Immutable
data class ExtraColors(val read: Color, val write: Color, val codeBackground: Color, val success: Color)

val LocalExtraColors = staticCompositionLocalOf {
    ExtraColors(Color(0xFF2E7D32), Color(0xFFC62828), Color(0xFF2A2420), Color(0xFF2E7D32))
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
fun AkoTheme(dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> DarkColors
        else -> LightColors
    }
    val extra = if (dark) {
        ExtraColors(read = Color(0xFF81C995), write = Color(0xFFFF8A80), codeBackground = Color(0xFF15120F), success = Color(0xFF81C995))
    } else {
        ExtraColors(read = Color(0xFF2E7D32), write = Color(0xFFC62828), codeBackground = Color(0xFF2A2420), success = Color(0xFF2E7D32))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides extra) {
        MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
    }
}
