package com.strumbum.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.strumbum.app.BuildConfig
import com.strumbum.app.ads.BannerAd
import com.strumbum.app.data.AppSettings
import com.strumbum.app.data.ThemeMode
import com.strumbum.app.music.NoteMath
import kotlin.math.roundToInt

class SettingsActions(
    val setA4: (Int) -> Unit,
    val setThemeMode: (ThemeMode) -> Unit,
    val setAmoledBlack: (Boolean) -> Unit,
    val setHighContrast: (Boolean) -> Unit,
    val setHaptics: (Boolean) -> Unit,
    val showPrivacyOptions: () -> Unit,
)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    privacyOptionsRequired: Boolean,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 8.dp).semantics { heading() },
            )

            Section("Calibration")
            Calibration(settings.a4Hz, actions.setA4)

            Section("Appearance")
            ThemePicker(settings.themeMode, actions.setThemeMode)
            SwitchRow("AMOLED black", "Pure black background in dark mode", settings.amoledBlack, actions.setAmoledBlack)
            SwitchRow("High contrast", "Stronger text and meter colours", settings.highContrast, actions.setHighContrast)

            Section("Feedback")
            SwitchRow("Haptics", "A short tick when a string locks in tune", settings.haptics, actions.setHaptics)

            Section("Privacy")
            Text(
                "No account, no cloud. Sound from the microphone is analysed on your phone and never recorded or sent anywhere.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (privacyOptionsRequired) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = actions.showPrivacyOptions, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Ad privacy choices")
                }
            }

            Section("About")
            Text(
                "StrumBum ${BuildConfig.VERSION_NAME}\nTypeface: Space Grotesk, SIL Open Font License 1.1",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
        BannerAd()
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun Calibration(a4: Int, onChange: (Int) -> Unit) {
    // Drag locally; write to storage when the finger lifts.
    var value by remember { mutableFloatStateOf(a4.toFloat()) }
    LaunchedEffect(a4) { value = a4.toFloat() }
    val shown = value.roundToInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Reference pitch", style = MaterialTheme.typography.titleMedium)
            Text("A4 = $shown Hz", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StepButton("−", "Lower reference pitch", shown > NoteMath.MIN_A4) { onChange(shown - 1) }
        StepButton("+", "Raise reference pitch", shown < NoteMath.MAX_A4) { onChange(shown + 1) }
    }
    Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { onChange(value.roundToInt()) },
        valueRange = NoteMath.MIN_A4.toFloat()..NoteMath.MAX_A4.toFloat(),
        steps = NoteMath.MAX_A4 - NoteMath.MIN_A4 - 1,
        modifier = Modifier.semantics { contentDescription = "Reference pitch, A4 in hertz" },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("${NoteMath.MIN_A4}–${NoteMath.MAX_A4} Hz", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onChange(NoteMath.DEFAULT_A4.toInt()) }, enabled = shown != NoteMath.DEFAULT_A4.toInt()) {
            Text("Reset to 440")
        }
    }
}

@Composable
private fun StepButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).width(56.dp).semantics { contentDescription = description },
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemePicker(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
    Text("Theme", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (m, label) ->
            SegmentedButton(
                selected = m == mode,
                onClick = { onChange(m) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
