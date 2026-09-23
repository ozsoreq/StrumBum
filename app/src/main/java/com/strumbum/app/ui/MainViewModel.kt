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

data class TunerUiState(
    val tuning: Tuning = Tunings.STANDARD,
    val a4: Double = NoteMath.DEFAULT_A4,
    /** Manually locked string index, or null for auto string detection. */
    val lockedString: Int? = null,
    /** String currently compared against (locked or auto-picked); null in chromatic mode or before any pitch. */
    val targetString: Int? = null,
    /** Note the needle is measured against; null before the first reading. */
    val targetMidi: Int? = null,
    /** Nearest chromatic note to what is actually being played. */
    val detectedMidi: Int? = null,
    val hz: Double? = null,
    /** Offset from the target note in cents; negative is flat. */
    val cents: Double? = null,
    /** True while the current frame has a confident pitch. */
    val live: Boolean = false,
    /** True once the string has been silent long enough that the held reading should fade. */
    val stale: Boolean = true,
    val inTune: Boolean = false,
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
                picker.reset()
                inTune.reset()
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

    private fun reduce(r: PitchReading, s: AppSettings, lock: Int?, tuned: Set<Int>, status: CaptureStatus): TunerUiState {
        val tuning = Tunings.byId(s.tuningId)
        val a4 = s.a4Hz.toDouble()
        val base = TunerUiState(tuning = tuning, a4 = a4, lockedString = lock, tunedStrings = tuned, status = status)
        if (r.hz <= 0.0) {
            inTune.update(null, SystemClock.elapsedRealtime())
            val target = lock?.let { tuning.strings.getOrNull(it) }
            return base.copy(targetString = lock, targetMidi = target, inTune = inTune.inTune)
        }
        val stringIndex = when {
            tuning.isChromatic -> null
            lock != null && lock in tuning.strings.indices -> lock
            else -> picker.pick(r.hz, tuning.strings, a4)
        }
        val targetMidi = stringIndex?.let { tuning.strings[it] } ?: NoteMath.nearestMidi(r.hz, a4)
        val cents = NoteMath.cents(r.hz, NoteMath.midiToHz(targetMidi, a4))

        if (targetMidi != lastTarget) {
            inTune.reset()
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
            detectedMidi = NoteMath.nearestMidi(r.hz, a4),
            hz = r.hz,
            cents = cents,
            live = r.hasPitch,
            stale = !r.hasPitch && r.silentFrames > STALE_FRAMES,
            inTune = inTune.inTune,
        )
    }

    // -- listening ---------------------------------------------------------------

    fun startListening() {
        if (ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            capture.start()
        }
    }

    fun stopListening() {
        capture.stop()
        tones.stop()
    }

    // -- tuner actions -------------------------------------------------------------

    fun toggleLock(index: Int) {
        lockedString.value = if (lockedString.value == index) null else index
        inTune.reset()
    }

    fun unlock() {
        lockedString.value = null
    }

    /** Play [midi]'s reference tone. The mic is muted meanwhile so the tuner doesn't read the speaker. */
    fun playTone(midi: Int) {
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
        /** ~430 ms of silence before a held reading fades. */
        const val STALE_FRAMES = 40
    }
}
