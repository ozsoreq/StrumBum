"""Note math for the test harness. The app has its own copy in Kotlin (NoteMath.kt)."""

from __future__ import annotations

import math

NOTE_NAMES = ("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")


def midi_to_hz(midi: float, a4: float = 440.0) -> float:
    return a4 * 2.0 ** ((midi - 69) / 12.0)


def hz_to_midi(hz: float, a4: float = 440.0) -> float:
    return 69.0 + 12.0 * math.log2(hz / a4)


def cents_between(hz: float, ref_hz: float) -> float:
    return 1200.0 * math.log2(hz / ref_hz)


def note_name(midi: int) -> str:
    return f"{NOTE_NAMES[midi % 12]}{midi // 12 - 1}"


def parse_note(name: str) -> int:
    """``"E2"`` -> 40, ``"C#3"`` -> 49, ``"Eb3"`` -> 51."""
    letter = name[0].upper()
    rest = name[1:]
    acc = 0
    if rest[:1] == "#":
        acc, rest = 1, rest[1:]
    elif rest[:1] == "b":
        acc, rest = -1, rest[1:]
    base = NOTE_NAMES.index(letter)
    return base + acc + (int(rest) + 1) * 12
