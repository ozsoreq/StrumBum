package com.strumbum.app.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

sealed interface CaptureStatus {
    data object Idle : CaptureStatus
    data object Starting : CaptureStatus
    data object Running : CaptureStatus
    data class Failed(val reason: String) : CaptureStatus
}

/**
 * Reads the microphone on a dedicated thread and runs the pitch engine once per hop.
 * The UI reads only the latest value from [readings], so drawing never waits on DSP.
 * Audio stays in memory on the device; nothing is stored or sent anywhere.
 */
class AudioCapture(private val context: Context) {
    private val _readings = MutableStateFlow(PitchReading.NONE)
    val readings: StateFlow<PitchReading> = _readings.asStateFlow()

    private val _status = MutableStateFlow<CaptureStatus>(CaptureStatus.Idle)
    val status: StateFlow<CaptureStatus> = _status.asStateFlow()

    /**
     * Each start() gets its own flag. A worker whose session was stopped can still be busy
     * opening the mic or starting Python (seconds, the first time); its flag keeps it from
     * running on or publishing anything once it gets there, even if a new session started.
     */
    private var session: AtomicBoolean? = null
    private var worker: Thread? = null
    private val publishLock = Any()

    /** While muted, frames are still analysed but not published (e.g. during a reference tone). */
    @Volatile var muted: Boolean = false

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Synchronized
    fun start() {
        if (worker?.isAlive == true && session?.get() == true) return
        session?.set(false)
        val active = AtomicBoolean(true)
        session = active
        // The previous worker may still hold the mic; the new one waits for it to let go.
        val previous = worker
        _status.value = CaptureStatus.Starting
        worker = Thread({
            previous?.join()
            loop(active)
        }, "strumbum-audio").apply { start() }
    }

    @Synchronized
    fun stop() {
        synchronized(publishLock) {
            session?.set(false)
            _readings.value = PitchReading.NONE
            if (_status.value !is CaptureStatus.Failed) _status.value = CaptureStatus.Idle
        }
        session = null
        // Give the worker a moment to release the mic. It is not forgotten if it is still
        // alive: the next start() waits for it.
        worker?.join(STOP_TIMEOUT_MS)
    }

    /** Runs [block] only while [active] is the current session, so a stale worker can't publish. */
    private inline fun publish(active: AtomicBoolean, block: () -> Unit) {
        synchronized(publishLock) { if (active.get()) block() }
    }

    @Suppress("MissingPermission") // start() is the only entry point and requires the permission.
    private fun loop(active: AtomicBoolean) {
        if (!active.get()) return
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val record = openRecord() ?: run {
            publish(active) { _status.value = CaptureStatus.Failed("Could not open the microphone") }
            active.set(false)
            return
        }
        val engine = try {
            PitchEngine.create(context, record.sampleRate)
        } catch (t: Throwable) {
            Log.e(TAG, "pitch engine failed to start", t)
            record.release()
            publish(active) { _status.value = CaptureStatus.Failed("Pitch engine failed to start") }
            active.set(false)
            return
        }
        try {
            record.startRecording()
            publish(active) { _status.value = CaptureStatus.Running }
            val buf = FloatArray(PitchEngine.HOP)
            while (active.get()) {
                val n = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    publish(active) { _status.value = CaptureStatus.Failed("Microphone error ($n)") }
                    break
                }
                if (n == 0) continue
                val reading = engine.feed(if (n == buf.size) buf else buf.copyOf(n))
                if (!muted) publish(active) { _readings.value = reading }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "audio loop crashed", t)
            publish(active) { _status.value = CaptureStatus.Failed("Audio stopped unexpectedly") }
        } finally {
            active.set(false)
            runCatching { record.stop() }
            record.release()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun openRecord(): AudioRecord? {
        for (rate in SAMPLE_RATES) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            if (min <= 0) continue
            val bytes = maxOf(min, PitchEngine.WINDOW * 4 * Float.SIZE_BYTES)
            val record = runCatching {
                AudioRecord(audioSource(), rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, bytes)
            }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
        }
        return null
    }

    /** UNPROCESSED skips AGC and noise suppression where the device supports it. */
    private fun audioSource(): Int {
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private companion object {
        const val TAG = "AudioCapture"
        const val STOP_TIMEOUT_MS = 300L
        val SAMPLE_RATES = intArrayOf(48_000, 44_100)
    }
}
