package com.strumbum.app.music

/** A tuning: open strings from lowest to highest, as MIDI notes. Chromatic has no strings. */
data class Tuning(
    val id: String,
    val name: String,
    val strings: List<Int>,
    val preferFlats: Boolean = false,
) {
    val isChromatic: Boolean get() = strings.isEmpty()

    fun stringName(index: Int): String = NoteMath.noteName(strings[index], preferFlats)

    /** Short summary for lists, e.g. "E A D G B E". */
    val summary: String
        get() = if (isChromatic) "Any note" else strings.joinToString(" ") { NoteMath.pitchClass(it, preferFlats) }
}

object Tunings {
    val STANDARD = Tuning("standard", "Standard", listOf(40, 45, 50, 55, 59, 64))

    val guitar: List<Tuning> = listOf(
        STANDARD,
        Tuning("drop_d", "Drop D", listOf(38, 45, 50, 55, 59, 64)),
        Tuning("dadgad", "DADGAD", listOf(38, 45, 50, 55, 57, 62)),
        Tuning("open_g", "Open G", listOf(38, 43, 50, 55, 59, 62)),
        Tuning("open_d", "Open D", listOf(38, 45, 50, 54, 57, 62)),
        Tuning("open_e", "Open E", listOf(40, 47, 52, 56, 59, 64)),
        Tuning("half_down", "Half-step down", listOf(39, 44, 49, 54, 58, 63), preferFlats = true),
        Tuning("full_down", "Full-step down", listOf(38, 43, 48, 53, 57, 62)),
    )

    val CHROMATIC = Tuning("chromatic", "Chromatic", emptyList())

    val all: List<Tuning> = guitar + CHROMATIC

    fun byId(id: String?): Tuning = all.firstOrNull { it.id == id } ?: STANDARD

    /** Lowest and highest MIDI notes any preset asks for; the reference tones cover this range. */
    val toneRange: IntRange = 36..67
}
