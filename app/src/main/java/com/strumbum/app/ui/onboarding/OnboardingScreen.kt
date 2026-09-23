package com.strumbum.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strumbum.app.music.NoteMath
import com.strumbum.app.ui.ListenWhile
import com.strumbum.app.ui.MainViewModel
import com.strumbum.app.ui.rememberMicPermission
import com.strumbum.app.ui.theme.LocalTunerColors
import com.strumbum.app.ui.theme.accentFor
import com.strumbum.app.ui.tuner.TuningMeter
import kotlin.math.abs

/** Two cards: why we need the mic, then a live "pluck a string" demo. No ads here. */
@Composable
fun OnboardingScreen(vm: MainViewModel) {
    val mic = rememberMicPermission()
    var step by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(mic.granted) { if (mic.granted && step == 0) step = 1 }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${step + 1} of 2",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (step == 0) MicCard(mic.needsSystemSettings, mic::request) else DemoCard(vm)
        Spacer(Modifier.weight(1f))
        if (step == 0) {
            TextButton(onClick = { vm.completeOnboarding() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Not now") }
        } else {
            Button(onClick = { vm.completeOnboarding() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Start tuning", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun MicCard(needsSystemSettings: Boolean, onAllow: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TuningMeter(cents = 0.0, accent = LocalTunerColors.current.green, inTune = true, dimmed = false, modifier = Modifier.padding(horizontal = 32.dp))
        Text(
            "StrumBum listens to your guitar",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "To hear your strings it needs the microphone. Sound is analysed on your phone. It's never recorded, stored or sent anywhere, and there's no account.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(if (needsSystemSettings) "Open app settings" else "Allow microphone", style = MaterialTheme.typography.titleMedium)
        }
        if (needsSystemSettings) {
            Text(
                "Microphone access was turned off. Enable it under Permissions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DemoCard(vm: MainViewModel) {
    ListenWhile(enabled = true, vm = vm)
    val state by vm.tuner.collectAsStateWithLifecycle()
    val colors = LocalTunerColors.current
    val heard = state.detectedMidi
    val cents = state.cents
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Pluck a string",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            heard?.let { NoteMath.noteName(it, state.tuning.preferFlats) } ?: "…",
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
            color = if (cents == null || state.stale) colors.muted else colors.accentFor(abs(cents)),
        )
        TuningMeter(
            cents = cents,
            accent = if (cents == null) colors.muted else colors.accentFor(abs(cents)),
            inTune = state.inTune,
            dimmed = state.stale,
        )
        Text(
            if (heard == null) "Any string will do. The needle shows how far off it is." else "That's it. The tuner shows which way to turn.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(4.dp))
    }
}
