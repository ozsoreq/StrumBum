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
    # Chaquopy's Java float[] implements the buffer protocol, so this is a zero-copy view on
    # device. Anything else (other dtypes, lists) is converted by value, never reinterpreted.
    if isinstance(samples, np.ndarray):
        return samples.astype(np.float32, copy=False).ravel()
    try:
        fmt = memoryview(samples).format
    except TypeError:
        return np.asarray(samples, dtype=np.float32)
    if fmt in ("f", "@f", "=f", "<f"):
        return np.frombuffer(samples, dtype=np.float32)
    return np.asarray(samples, dtype=np.float32)


class Engine:
    def __init__(
        self,
        sample_rate: int = 48_000,
        window: int = 2048,
        hop: int = 512,
        min_db: float = -55.0,
        min_clarity: float = 0.85,
        min_freq: float = 60.0,
        max_freq: float = 1400.0,
        reset_after_silent_frames: int = 12,
        confirm_frames: int = 2,
        full_weight_clarity: float = 0.999,
    ) -> None:
        if not 0 < hop <= window:
            raise ValueError("hop must be between 1 and window")
        if confirm_frames < 1 or not 0.0 < full_weight_clarity < 1.0:
            raise ValueError("bad engine parameters")
        self.detector = PitchDetector(sample_rate, window, min_freq, max_freq)
        self.smoother = PitchSmoother()
        self.sample_rate = sample_rate
        self.window = window
        self.hop = hop
        self.min_db = min_db
        self.min_clarity = min_clarity
        self.reset_after_silent_frames = reset_after_silent_frames
        self.confirm_frames = confirm_frames
        self._full_weight_snr = full_weight_clarity / (1.0 - full_weight_clarity)
        self._buf = np.zeros(window, dtype=np.float32)
        self._pending = 0
        self._silent = reset_after_silent_frames
        self._onset = 0  # consecutive gated-in frames of a pluck that is not yet confirmed
        self._peak_snr = 0.0
        self._last = self._idle_reading()

    def _idle_reading(self) -> tuple[float, float, float, float, float, float]:
        return (0.0, 0.0, 0.0, 0.0, -120.0, float(self._silent))

    def set_gate(self, min_db: float, min_clarity: float) -> None:
        self.min_db = float(min_db)
        self.min_clarity = float(min_clarity)

    def reset(self) -> None:
        self._buf[:] = 0.0
        self._pending = 0
        self.smoother.reset()
        self._silent = self.reset_after_silent_frames
        self._onset = 0
        self._peak_snr = 0.0
        self._last = self._idle_reading()

    def feed(self, samples) -> tuple[float, float, float, float, float, float]:
        """Push mono float samples in [-1, 1] and return the latest reading.

        The tuple is ``(has_pitch, smoothed_hz, raw_hz, clarity, rms_db, silent_frames)``.
        ``has_pitch`` is 1.0 when the latest frame passed both gates. Otherwise
        ``smoothed_hz`` still holds the last good value, so the UI can hold or fade
        the needle. It is 0.0 only if nothing has been detected yet.
        """
        x = _as_float32(samples)
        if not np.isfinite(x).all():
            # One NaN/Inf would poison the whole window (and the smoother); treat it as silence.
            x = np.nan_to_num(x, nan=0.0, posinf=0.0, neginf=0.0)
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

    def _weight(self, clarity: float) -> float:
        """EMA weight of one frame: its SNR relative to the note's best frame, capped at 1.

        Clarity is about SNR / (1 + SNR) and the pitch error variance of a frame is about
        1 / SNR, so this is inverse-variance weighting. A note fading into room noise then
        stops moving the held reading instead of dragging it around in its noisiest frames.
        The reference is the note's own peak SNR (at most that of ``full_weight_clarity``),
        so a note that is noisy from the start still gets full weight and stays responsive.
        """
        snr = clarity / max(1.0 - clarity, 1e-9)
        self._peak_snr = max(self._peak_snr, snr)
        return min(1.0, snr / min(self._peak_snr, self._full_weight_snr))

    def _analyze(self) -> tuple[float, float, float, float, float, float]:
        est = self.detector.estimate(self._buf)
        last_hz = self.smoother.value or 0.0
        if est is None:
            self._silent += 1
            self._onset = 0
            return (0.0, last_hz, 0.0, 0.0, -120.0, float(self._silent))
        # Written so that NaN fails the gates.
        voiced = est.rms_db >= self.min_db and est.clarity >= self.min_clarity and 0.0 < est.frequency < np.inf
        if not voiced:
            self._silent += 1
            self._onset = 0
            return (0.0, last_hz, est.frequency, est.clarity, est.rms_db, float(self._silent))
        if self._silent >= self.reset_after_silent_frames:
            # A fresh pluck after silence. Publish it only once it has passed the gates for
            # confirm_frames frames in a row, so a lone noise frame never shows up as a note.
            self._onset += 1
            if self._onset < self.confirm_frames:
                self._silent += 1
                return (0.0, last_hz, est.frequency, est.clarity, est.rms_db, float(self._silent))
            # Start the smoother over so the first reading is quick. The earlier onset frames
            # are left out: they straddle the attack and read worst.
            self.smoother.reset()
            self._peak_snr = 0.0
            self._onset = 0
        self._silent = 0
        smoothed = self.smoother.push(est.frequency, self._weight(est.clarity))
        return (1.0, smoothed, est.frequency, est.clarity, est.rms_db, 0.0)
