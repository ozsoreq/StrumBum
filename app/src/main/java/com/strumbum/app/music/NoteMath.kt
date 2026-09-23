package com.strumbum.app.music

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Equal-temperament note math. The Python engine has the same helpers for its test harness. */
object NoteMath {
    const val DEFAULT_A4 = 440.0
    const val MIN_A4 = 415
    const val MAX_A4 = 466

    private val SHARP_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLAT_NAMES = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    fun midiToHz(midi: Double, a4: Double = DEFAULT_A4): Double = a4 * 2.0.pow((midi - 69.0) / 12.0)

    fun midiToHz(midi: Int, a4: Double = DEFAULT_A4): Double = midiToHz(midi.toDouble(), a4)

    fun hzToMidi(hz: Double, a4: Double = DEFAULT_A4): Double = 69.0 + 12.0 * log2(hz / a4)

    fun nearestMidi(hz: Double, a4: Double = DEFAULT_A4): Int = hzToMidi(hz, a4).roundToInt()

    /** Signed cents from [refHz] to [hz]: negative means [hz] is flat. */
    fun cents(hz: Double, refHz: Double): Double = 1200.0 * log2(hz / refHz)

    /** Pitch class name without octave, e.g. "F#" or "Gb". */
    fun pitchClass(midi: Int, preferFlats: Boolean = false): String =
        (if (preferFlats) FLAT_NAMES else SHARP_NAMES)[Math.floorMod(midi, 12)]

    fun octave(midi: Int): Int = Math.floorDiv(midi, 12) - 1

    fun noteName(midi: Int, preferFlats: Boolean = false): String = pitchClass(midi, preferFlats) + octave(midi)

    private fun log2(x: Double) = ln(x) / ln(2.0)
}
