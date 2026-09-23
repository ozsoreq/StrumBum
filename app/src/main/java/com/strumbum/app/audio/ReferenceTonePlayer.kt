package com.strumbum.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.strumbum.app.R
import com.strumbum.app.music.NoteMath

/**
 * Plays the pre-rendered string tones (engine/devtools/render_tones.py, rendered at A4 = 440).
 * Calibration is applied through SoundPool's playback rate, which moves the pitch by a4 / 440.
 */
class ReferenceTonePlayer(private val context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .build()

    private val soundIds = HashMap<Int, Int>() // midi -> SoundPool id
    private val ready = HashSet<Int>() // SoundPool ids that finished loading
    private var pending: Pair<Int, Float>? = null // sound id + rate waiting for its load
    private var stream = 0

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status != 0) return@setOnLoadCompleteListener
            ready += id
            pending?.let { (pid, rate) -> if (pid == id) { pending = null; start(id, rate) } }
        }
    }

    /** Decode the given notes ahead of time so the first tap plays instantly. */
    fun prepare(midis: Collection<Int>) {
        midis.forEach { load(it) }
    }

    fun play(midi: Int, a4: Double) {
        val id = load(midi) ?: return
        val rate = (a4 / NoteMath.DEFAULT_A4).toFloat().coerceIn(0.5f, 2.0f)
        if (id in ready) start(id, rate) else pending = id to rate
    }

    fun stop() {
        pending = null
        if (stream != 0) pool.stop(stream)
        stream = 0
    }

    fun release() = pool.release()

    private fun start(id: Int, rate: Float) {
        stop()
        stream = pool.play(id, 1f, 1f, 1, 0, rate)
    }

    private fun load(midi: Int): Int? {
        val res = TONES[midi] ?: return null
        return soundIds.getOrPut(midi) { pool.load(context, res, 1) }
    }

    companion object {
        /** Length of the rendered tones; the mic ignores input for this long after play(). */
        const val TONE_MS = 3_000L

        private val TONES: Map<Int, Int> = mapOf(
            36 to R.raw.tone_36, 37 to R.raw.tone_37, 38 to R.raw.tone_38, 39 to R.raw.tone_39,
            40 to R.raw.tone_40, 41 to R.raw.tone_41, 42 to R.raw.tone_42, 43 to R.raw.tone_43,
            44 to R.raw.tone_44, 45 to R.raw.tone_45, 46 to R.raw.tone_46, 47 to R.raw.tone_47,
            48 to R.raw.tone_48, 49 to R.raw.tone_49, 50 to R.raw.tone_50, 51 to R.raw.tone_51,
            52 to R.raw.tone_52, 53 to R.raw.tone_53, 54 to R.raw.tone_54, 55 to R.raw.tone_55,
            56 to R.raw.tone_56, 57 to R.raw.tone_57, 58 to R.raw.tone_58, 59 to R.raw.tone_59,
            60 to R.raw.tone_60, 61 to R.raw.tone_61, 62 to R.raw.tone_62, 63 to R.raw.tone_63,
            64 to R.raw.tone_64, 65 to R.raw.tone_65, 66 to R.raw.tone_66, 67 to R.raw.tone_67,
        )
    }
}
