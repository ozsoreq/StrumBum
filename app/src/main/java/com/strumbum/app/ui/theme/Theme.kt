package com.strumbum.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.strumbum.app.R
import com.strumbum.app.data.AppSettings
import com.strumbum.app.data.ThemeMode

/** Tuner-specific colours: the one accent that shifts red → amber → green, plus meter ink. */
@Immutable
data class TunerColors(
    val red: Color,
    val amber: Color,
    val green: Color,
    val ticks: Color,
    val muted: Color,
    val highContrast: Boolean,
)

val LocalTunerColors = staticCompositionLocalOf {
    TunerColors(Color.Red, Color.Yellow, Color.Green, Color.Gray, Color.Gray, false)
}

/** Accent for a given offset: green inside ±3 cents, fading through amber to red by 35 cents. */
fun TunerColors.accentFor(absCents: Double): Color = when {
    absCents <= 3.0 -> green
    absCents <= 15.0 -> lerp(green, amber, ((absCents - 3.0) / 12.0).toFloat())
    absCents <= 35.0 -> lerp(amber, red, ((absCents - 15.0) / 20.0).toFloat())
    else -> red
}

private val Ink = Color(0xFF0E0F12)
private val OffWhite = Color(0xFFECEDEF)

// Variable font: Font(resId, weight) sets the matching 'wght' axis value.
private fun spaceGrotesk(weight: Int) = Font(R.font.space_grotesk, weight = FontWeight(weight))

val SpaceGrotesk = FontFamily(spaceGrotesk(400), spaceGrotesk(500), spaceGrotesk(600), spaceGrotesk(700))

private val AppTypography: Typography = Typography().let { t ->
    fun s(style: androidx.compose.ui.text.TextStyle) = style.copy(fontFamily = SpaceGrotesk)
    Typography(
        displayLarge = s(t.displayLarge), displayMedium = s(t.displayMedium), displaySmall = s(t.displaySmall),
        headlineLarge = s(t.headlineLarge), headlineMedium = s(t.headlineMedium), headlineSmall = s(t.headlineSmall),
        titleLarge = s(t.titleLarge), titleMedium = s(t.titleMedium), titleSmall = s(t.titleSmall),
        bodyLarge = s(t.bodyLarge), bodyMedium = s(t.bodyMedium), bodySmall = s(t.bodySmall),
        labelLarge = s(t.labelLarge), labelMedium = s(t.labelMedium), labelSmall = s(t.labelSmall),
    )
}

@Composable
fun AppSettings.isDarkTheme(): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun StrumBumTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = settings.isDarkTheme()
    val hc = settings.highContrast
    // Dynamic colour is deliberately off: the tuner has its own palette.
    val scheme = if (dark) {
        val bg = if (settings.amoledBlack) Color.Black else Ink
        darkColorScheme(
            primary = Color(0xFF3DDC97),
            onPrimary = Ink,
            background = bg,
            onBackground = if (hc) Color.White else OffWhite,
            surface = bg,
            onSurface = if (hc) Color.White else OffWhite,
            surfaceVariant = if (settings.amoledBlack) Color(0xFF111214) else Color(0xFF1A1C21),
            onSurfaceVariant = if (hc) Color.White else Color(0xFFA4A8B1),
            surfaceContainer = if (settings.amoledBlack) Color(0xFF0B0B0C) else Color(0xFF15171B),
            outline = if (hc) Color.White else Color(0xFF3A3D45),
            outlineVariant = if (hc) Color(0xFFBFC3CA) else Color(0xFF2A2D33),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF12805A),
            onPrimary = Color.White,
            background = Color(0xFFF6F6F4),
            onBackground = if (hc) Color.Black else Color(0xFF15171B),
            surface = Color(0xFFF6F6F4),
            onSurface = if (hc) Color.Black else Color(0xFF15171B),
            surfaceVariant = Color(0xFFE9EAE7),
            onSurfaceVariant = if (hc) Color.Black else Color(0xFF555A63),
            surfaceContainer = Color(0xFFEFEFEC),
            outline = if (hc) Color.Black else Color(0xFFC4C6C9),
            outlineVariant = if (hc) Color(0xFF333333) else Color(0xFFDADCDD),
        )
    }
    val tunerColors = if (dark) {
        TunerColors(
            red = if (hc) Color(0xFFFF5A5A) else Color(0xFFFF6B6B),
            amber = if (hc) Color(0xFFFFC23D) else Color(0xFFFFB547),
            green = if (hc) Color(0xFF3CFFAA) else Color(0xFF3DDC97),
            ticks = if (hc) Color.White else Color(0xFF5A5F69),
            muted = if (hc) Color(0xFFD0D0D0) else Color(0xFF6C717B),
            highContrast = hc,
        )
    } else {
        TunerColors(
            red = if (hc) Color(0xFFB00020) else Color(0xFFD23C3C),
            amber = if (hc) Color(0xFF8A5A00) else Color(0xFFB7791F),
            green = if (hc) Color(0xFF006B3F) else Color(0xFF12805A),
            ticks = if (hc) Color.Black else Color(0xFFA2A6AD),
            muted = if (hc) Color(0xFF333333) else Color(0xFF8B9099),
            highContrast = hc,
        )
    }
    CompositionLocalProvider(LocalTunerColors provides tunerColors) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
