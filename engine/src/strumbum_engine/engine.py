"""Streaming entry point used by the Android app through Chaquopy.

Kotlin reads the microphone in small chunks and hands each chunk to
:meth:`Engine.feed`. The engine keeps its own sliding analysis window and runs
one detection per ``hop`` samples. It returns a flat tuple of floats, because
flat tuples are the cheapest thing to move across the Python/Java bridge.
"""

from __future__ import annotations

import numpy as np

from .detector import PitchDetector
from .smoothing import PitchSmoother

# Index constants for the tuple returned by Engine.feed; mirrored in Kotlin (PitchEngine.kt).
HAS_PITCH, SMOOTHED_HZ, RAW_HZ, CLARITY, RMS_DB, SILENT_FRAMES = range(6)


def _as_float32(samples) -> np.ndarray:
    # Chaquopy's Java primitive arrays implement the buffer protocol, so this is a
    # zero-copy view on device. Plain Python sequences take the slow path (tests).
    try:
        return np.frombuffer(samples, dtype=np.float32)
    except (TypeError, ValueError):
        return np.asarray(samples, dtype=np.float32)


class Engine:
    def __init__(
        self,
        sample_rate: int = 48_000,
        window: int = 2048,
        hop: int = 512,
        min_db: float = -55.0,
        min_clarity: float = 0.9,
        min_freq: float = 60.0,
        max_freq: float = 1400.0,
        reset_after_silent_frames: int = 12,
    ) -> None:
        self.detector = PitchDetector(sample_rate, window, min_freq, max_freq)
        self.smoother = PitchSmoother()
        self.sample_rate = sample_rate
        self.window = window
        self.hop = hop
        self.min_db = min_db
        self.min_clarity = min_clarity
        self.reset_after_silent_frames = reset_after_silent_frames
        self._buf = np.zeros(window, dtype=np.float32)
        self._pending = 0
        self._silent = reset_after_silent_frames
        self._last = (0.0, 0.0, 0.0, 0.0, -120.0, float(self._silent))

    def set_gate(self, min_db: float, min_clarity: float) -> None:
        self.min_db = float(min_db)
        self.min_clarity = float(min_clarity)

    def reset(self) -> None:
        self._buf[:] = 0.0
        self._pending = 0
        self.smoother.reset()
        self._silent = self.reset_after_silent_frames

    def feed(self, samples) -> tuple[float, float, float, float, float, float]:
        """Push mono float samples in [-1, 1] and return the latest reading.

        The tuple is ``(has_pitch, smoothed_hz, raw_hz, clarity, rms_db, silent_frames)``.
        ``has_pitch`` is 1.0 when the latest frame passed both gates. Otherwise
        ``smoothed_hz`` still holds the last good value, so the UI can hold or fade
        the needle. It is 0.0 only if nothing has been detected yet.
        """
        x = _as_float32(samples)
        n = x.size
        w = self.window
        pos = 0
        while pos < n:
            take = min(self.hop - self._pending, n - pos)
            self._buf[:-take] = self._buf[take:]
            self._buf[-take:] = x[pos : pos + take]
            pos += take
            self._pending += take
            if self._pending >= self.hop:
                self._pending = 0
                self._last = self._analyze()
        return self._last

    def _analyze(self) -> tuple[float, float, float, float, float, float]:
        est = self.detector.estimate(self._buf)
        last_hz = self.smoother.value or 0.0
        if est is None:
            self._silent += 1
            return (0.0, last_hz, 0.0, 0.0, -120.0, float(self._silent))
        if est.rms_db < self.min_db or est.clarity < self.min_clarity:
            self._silent += 1
            return (0.0, last_hz, est.frequency, est.clarity, est.rms_db, float(self._silent))
        if self._silent >= self.reset_after_silent_frames:
            # A fresh pluck after silence: start over so the first reading is quick.
            self.smoother.reset()
        self._silent = 0
        smoothed = self.smoother.push(est.frequency)
        return (1.0, smoothed, est.frequency, est.clarity, est.rms_db, 0.0)
