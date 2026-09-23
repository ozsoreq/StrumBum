package com.strumbum.app.audio

/** One engine output. Mirrors the tuple returned by `strumbum_engine.engine.Engine.feed`. */
data class PitchReading(
    /** True when the latest frame passed the level and clarity gates. */
    val hasPitch: Boolean,
    /** Smoothed pitch in Hz; still holds the last good value while gated, 0 if none yet. */
    val hz: Double,
    val rawHz: Double,
    val clarity: Double,
    val rmsDb: Double,
    /** Analysis frames (~10.7 ms each) since the last gated-in frame. */
    val silentFrames: Int,
) {
    companion object {
        val NONE = PitchReading(false, 0.0, 0.0, 0.0, -120.0, Int.MAX_VALUE)
    }
}
