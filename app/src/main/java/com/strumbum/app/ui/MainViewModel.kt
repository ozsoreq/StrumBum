package com.strumbum.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.strumbum.app.audio.AudioCapture
import com.strumbum.app.audio.CaptureStatus
import com.strumbum.app.audio.PitchReading
import com.strumbum.app.audio.ReferenceTonePlayer
import com.strumbum.app.data.AppSettings
import com.strumbum.app.data.SettingsRepository
import com.strumbum.app.data.ThemeMode
import com.strumbum.app.music.InTuneDetector
import com.strumbum.app.music.NoteMath
import com.strumbum.app.music.NoteTracker
import com.strumbum.app.music.StickyRounder
import com.strumbum.app.music.StringPicker
import com.strumbum.app.music.Tuning
import com.strumbum.app.music.Tunings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

data class TunerUiState(
    val tuning: Tuning = Tunings.STANDARD,
    val a4: Double = NoteMath.DEFAULT_A4,
    /** Manually locked string index, or null for auto string detection. */
    val lockedString: Int? = null,
    /** String currently compared against (locked or auto-picked); null in chromatic mode or before any pitch. */
    val targetString: Int? = null,
    /** Note the needle is measured against; null before the first reading. */
    val targetMidi: Int? = null,
    /** Nearest chromatic note to what is actually being played (with hysteresis at ±50 cents). */
    val detectedMidi: Int? = null,
    val hz: Double? = null,
    /** Offset from the target note in cents; negative is flat. */
    val cents: Double? = null,
    /** |cents| rounded for the "N cents flat" text, with hysteresis so it doesn't flicker. */
    val roundedCents: Int? = null,
    /** True once the string has been silent long enough that the held reading should fade. */
    val stale: Boolean = true,
    /** Locked in tune: held within ±3 cents for 400 ms. Drives the glow and the haptic tick. */
    val inTune: Boolean = false,
    /** Within the in-tune band right now (enter ±3 cents, leave past ±5). Drives the label. */
    val centered: Boolean = false,
    val tunedStrings: Set<Int> = emptySet(),
    val status: CaptureStatus = CaptureStatus.Idle,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)
    private val capture = AudioCapture(app)
    private val tones = ReferenceTonePlayer(app)

    /** Null until DataStore has been read once, so the first frame never shows wrong defaults. */
    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val lockedString = MutableStateFlow<Int?>(null)
    private val tunedStrings = MutableStateFlow<Set<Int>>(emptySet())
    private val picker = StringPicker()
    private val inTune = InTuneDetector()
    private val notes = NoteTracker()
    private val rounder = StickyRounder()
    private var lastTarget: Int? = null
    private var unmuteJob: Job? = null

    private val _tuner = MutableStateFlow(TunerUiState())
    val tuner: StateFlow<TunerUiState> = _tuner.asStateFlow()

    private val _lockEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits once each time a string locks in tune, while haptics are enabled. */
    val lockEvents: SharedFlow<Unit> = _lockEvents

    val captureStatus: StateFlow<CaptureStatus> = capture.status

    init {
        viewModelScope.launch {
            settings.filterNotNull().distinctUntilChangedBy { it.tuningId }.collect { s ->
                resetTracking()
                lockedString.value = null
                tunedStrings.value = emptySet()
                tones.prepare(Tunings.byId(s.tuningId).strings)
            }
        }
        viewModelScope.launch {
            combine(capture.readings, settings.filterNotNull(), lockedString, tunedStrings, capture.status) { r, s, lock, tuned, status ->
                reduce(r, s, lock, tuned, status)
            }.collect { _tuner.value = it }
        }
    }

    private fun reduce(r: PitchReading, s: AppSettings, requestedLock: Int?, tuned: Set<Int>, status: CaptureStatus): TunerUiState {
        val tuning = Tunings.byId(s.tuningId)
        val a4 = s.a4Hz.toDouble()
        // The lock is cleared by another collector after a tuning change, so an intermediate
        // state can pair a new tuning with an old index. Never pass on an index it doesn't have.
        val lock = requestedLock?.takeIf { it in tuning.strings.indices }
        val base = TunerUiState(tuning = tuning, a4 = a4, lockedString = lock, tunedStrings = tuned, status = status)
        if (!r.hz.isFinite() || r.hz <= 0.0) {
            inTune.update(null, SystemClock.elapsedRealtime())
            notes.reset()
            val target = lock?.let { tuning.strings[it] }
            return base.copy(targetString = lock, targetMidi = target, inTune = inTune.inTune)
        }
        val stale = !r.hasPitch && r.silentFrames > STALE_FRAMES
        // The next pluck names its note afresh.
        if (stale) notes.reset()
        val stringIndex = when {
            tuning.isChromatic -> null
            lock != null -> lock
            else -> picker.pick(r.hz, tuning.strings, a4)
        }
        val detectedMidi = notes.nearest(r.hz, a4)
        val targetMidi = stringIndex?.let { tuning.strings[it] } ?: detectedMidi
        val cents = NoteMath.cents(r.hz, NoteMath.midiToHz(targetMidi, a4))

        if (targetMidi != lastTarget) {
            inTune.reset()
            rounder.reset()
            lastTarget = targetMidi
        }
        val locked = inTune.update(if (r.hasPitch) cents else null, SystemClock.elapsedRealtime())
        if (locked) {
            if (s.haptics) _lockEvents.tryEmit(Unit)
            if (stringIndex != null && stringIndex !in tuned) tunedStrings.value = tuned + stringIndex
        }
        return base.copy(
            targetString = stringIndex,
            targetMidi = targetMidi,
            detectedMidi = detectedMidi,
            hz = r.hz,
            cents = cents,
            roundedCents = rounder.round(abs(cents)),
            stale = stale,
            inTune = inTune.inTune,
            centered = inTune.centered,
        )
    }

    // -- listening ---------------------------------------------------------------

    fun startListening() {
        if (ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            resetTracking()
            capture.start()
        }
    }

    fun stopListening() {
        capture.stop()
        tones.stop()
        resetTracking()
    }

    /** Forget the previous session: no in-tune glow without a pitch, and a fresh tick next time. */
    private fun resetTracking() {
        picker.reset()
        inTune.reset()
        notes.reset()
        rounder.reset()
        lastTarget = null
    }

    // -- tuner actions -------------------------------------------------------------

    fun toggleLock(index: Int) {
        lockedString.value = if (lockedString.value == index) null else index
        inTune.reset()
    }

    fun unlock() {
        lockedString.value = null
        inTune.reset()
    }

    /** Play [midi]'s reference tone. The mic is muted meanwhile so the tuner doesn't read the speaker. */
    fun playTone(midi: Int) {
        // No tone for this note (chromatic mode can target any note): don't mute the mic for nothing.
        if (!tones.canPlay(midi)) return
        val a4 = settings.value?.a4Hz?.toDouble() ?: NoteMath.DEFAULT_A4
        capture.muted = true
        tones.play(midi, a4)
        unmuteJob?.cancel()
        unmuteJob = viewModelScope.launch {
            delay(ReferenceTonePlayer.TONE_MS)
            capture.muted = false
        }
    }

    // -- settings ----------------------------------------------------------------------

    fun selectTuning(id: String) = viewModelScope.launch { repo.setTuning(id) }
    fun setA4(hz: Int) = viewModelScope.launch { repo.setA4(hz) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repo.setThemeMode(mode) }
    fun setAmoledBlack(on: Boolean) = viewModelScope.launch { repo.setAmoledBlack(on) }
    fun setHighContrast(on: Boolean) = viewModelScope.launch { repo.setHighContrast(on) }
    fun setHaptics(on: Boolean) = viewModelScope.launch { repo.setHaptics(on) }
    fun completeOnboarding() = viewModelScope.launch { repo.setOnboardingDone() }

    override fun onCleared() {
        capture.stop()
        tones.release()
    }

    private companion object {
        /** Frames of silence before a held reading fades: ~440 ms at 48 kHz, ~480 ms at 44.1 kHz. */
        const val STALE_FRAMES = 40
    }
}
