package com.strumbum.app.ui.tuner

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.strumbum.app.audio.CaptureStatus
import com.strumbum.app.music.NoteMath
import com.strumbum.app.ui.TunerUiState
import com.strumbum.app.ui.theme.LocalTunerColors
import com.strumbum.app.ui.theme.accentFor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun TunerScreen(
    state: TunerUiState,
    hasMicPermission: Boolean,
    onRequestMic: () -> Unit,
    onRetry: () -> Unit,
    onOpenTunings: () -> Unit,
    onToggleLock: (Int) -> Unit,
    onAuto: () -> Unit,
    onPlayTone: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header(state, onOpenTunings)
        Spacer(Modifier.weight(1f))
        when {
            !hasMicPermission -> MicNeeded(onRequestMic)
            state.status is CaptureStatus.Failed -> Problem(state.status.reason, onRetry)
            else -> Readout(state)
        }
        Spacer(Modifier.weight(1f))
        if (state.tuning.isChromatic) {
            Text(
                "Chromatic: any note, measured against the nearest semitone",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            StringRow(state, onToggleLock, onAuto, onPlayTone)
        }
        Spacer(Modifier.height(12.dp))
        val toneMidi = state.lockedString?.let { state.tuning.strings[it] } ?: state.targetMidi
        FilledTonalButton(
            onClick = { toneMidi?.let(onPlayTone) },
            enabled = toneMidi != null,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(
                toneMidi?.let { "Play ${NoteMath.noteName(it, state.tuning.preferFlats)}" } ?: "Play reference tone",
            )
        }
    }
}

@Composable
private fun Header(state: TunerUiState, onOpenTunings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onOpenTunings, modifier = Modifier.heightIn(min = 48.dp)) {
            Column {
                Text(state.tuning.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(state.tuning.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change tuning")
        }
        Spacer(Modifier.weight(1f))
        Text(
            "A4 = ${state.a4.roundToInt()} Hz",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun Readout(state: TunerUiState) {
    val colors = LocalTunerColors.current
    val cents = state.cents
    val accent = if (cents == null) colors.muted else colors.accentFor(abs(cents))
    val noteColor = if (state.stale || cents == null) colors.muted else accent
    val target = state.targetMidi
    val flats = state.tuning.preferFlats

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Big note letter, accidental and octave. TalkBack reads it once as, e.g., "Target note E2".
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = target?.let { "Target note ${NoteMath.noteName(it, flats)}" } ?: "No note yet"
                heading()
            },
        ) {
            val pc = target?.let { NoteMath.pitchClass(it, flats) } ?: "–"
            Text(pc.take(1), fontSize = 120.sp, lineHeight = 120.sp, fontWeight = FontWeight.Bold, color = noteColor)
            Column(Modifier.padding(top = 18.dp, start = 4.dp)) {
                Text(pc.drop(1).replace("#", "♯").replace("b", "♭"), fontSize = 40.sp, color = noteColor)
                Spacer(Modifier.height(20.dp))
                Text(target?.let { NoteMath.octave(it).toString() } ?: "", fontSize = 28.sp, color = colors.muted)
            }
        }
        Text(
            state.hz?.let { String.format(Locale.US, "%.1f Hz", it) } ?: " ",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val detected = state.detectedMidi
        Text(
            if (detected != null && target != null && detected != target && !state.stale) {
                "You're playing ${NoteMath.noteName(detected, flats)}"
            } else {
                " "
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        TuningMeter(cents = cents, accent = accent, inTune = state.inTune, dimmed = state.stale)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("♭ −50", style = MaterialTheme.typography.labelMedium, color = colors.muted)
            Text("+50 ♯", style = MaterialTheme.typography.labelMedium, color = colors.muted)
        }
        Spacer(Modifier.height(8.dp))
        Direction(state, accent)
    }
}

/** Colour is never the only signal: an arrow and words say which way to turn. */
@Composable
private fun Direction(state: TunerUiState, accent: Color) {
    val cents = state.cents
    val (icon, label) = when {
        state.status is CaptureStatus.Starting -> null to "Warming up…"
        cents == null -> null to "Pluck a string"
        state.inTune || abs(cents) <= 3.0 -> Icons.Filled.Check to "In tune"
        cents < 0 -> Icons.Filled.KeyboardArrowUp to "Tune up"
        else -> Icons.Filled.KeyboardArrowDown to "Tune down"
    }
    val detail = if (cents != null && !state.inTune && abs(cents) > 3.0) {
        val n = abs(cents).roundToInt()
        "$n ${if (n == 1) "cent" else "cents"} ${if (cents < 0) "flat" else "sharp"}"
    } else {
        null
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // Only the label is announced; it changes rarely (up / down / in tune), so TalkBack isn't flooded.
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(32.dp))
        Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = if (icon != null) accent else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
        detail ?: " ",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StringRow(
    state: TunerUiState,
    onToggleLock: (Int) -> Unit,
    onAuto: () -> Unit,
    onPlayTone: (Int) -> Unit,
) {
    val colors = LocalTunerColors.current
    val strings = state.tuning.strings
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            strings.forEachIndexed { i, midi ->
                val isTarget = state.targetString == i
                val isLocked = state.lockedString == i
                val tuned = i in state.tunedStrings
                val ring = when {
                    isTarget && state.cents != null && !state.stale -> colors.accentFor(abs(state.cents))
                    isTarget || isLocked -> MaterialTheme.colorScheme.onBackground
                    else -> MaterialTheme.colorScheme.outline
                }
                val name = NoteMath.noteName(midi, state.tuning.preferFlats)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (isLocked) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .border(if (isTarget || isLocked) 2.5.dp else 1.dp, ring, CircleShape)
                        .combinedClickable(
                            onClickLabel = if (isLocked) "Unlock string" else "Lock to this string",
                            onLongClickLabel = "Play reference tone",
                            onLongClick = { onPlayTone(midi) },
                            onClick = { onToggleLock(i) },
                        )
                        .semantics {
                            contentDescription = "String ${strings.size - i}, $name"
                            stateDescription = listOfNotNull(
                                if (isLocked) "locked" else null,
                                if (tuned) "tuned" else null,
                            ).joinToString(", ")
                        },
                ) {
                    Text(
                        NoteMath.pitchClass(midi, state.tuning.preferFlats),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isTarget) FontWeight.Bold else FontWeight.Medium,
                    )
                    if (tuned) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = colors.green,
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 4.dp).size(12.dp),
                        )
                    }
                    if (isLocked) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).size(10.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        val locked = state.lockedString
        if (locked == null) {
            Text(
                "Auto string detection · tap a string to lock it, hold to hear it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            TextButton(onClick = onAuto, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Locked to ${state.tuning.stringName(locked)} · back to auto")
            }
        }
    }
}

@Composable
private fun MicNeeded(onRequestMic: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Text("The tuner needs the microphone", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sound is analysed on your phone and never recorded or sent anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequestMic, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow microphone") }
    }
}

@Composable
private fun Problem(reason: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Text(reason, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
    }
}
