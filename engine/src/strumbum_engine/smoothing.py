"""Pitch smoothing: a short median, then an exponential moving average.

Smoothing runs in the log-frequency (cents) domain, so the EMA behaves the same on
low E as on high e. The median removes one-frame glitches such as octave errors.
When the median moves by more than ``jump_cents``, a new note is playing. The EMA
then snaps to it instead of gliding across the gap.
"""

from __future__ import annotations

from collections import deque
import math

import numpy as np


def hz_to_cents(hz: float) -> float:
    """Cents relative to A4 = 440 Hz. Only differences between values matter."""
    return 1200.0 * math.log2(hz / 440.0)


def cents_to_hz(cents: float) -> float:
    return 440.0 * 2.0 ** (cents / 1200.0)


class PitchSmoother:
    def __init__(self, median_len: int = 5, alpha: float = 0.35, jump_cents: float = 60.0) -> None:
        if median_len < 1 or not 0.0 < alpha <= 1.0:
            raise ValueError("bad smoother parameters")
        self.alpha = alpha
        self.jump_cents = jump_cents
        self._history: deque[float] = deque(maxlen=median_len)
        self._ema: float | None = None

    def reset(self) -> None:
        self._history.clear()
        self._ema = None

    @property
    def value(self) -> float | None:
        return None if self._ema is None else cents_to_hz(self._ema)

    def push(self, hz: float) -> float:
        c = hz_to_cents(hz)
        self._history.append(c)
        med = float(np.median(self._history))
        if self._ema is None or abs(med - self._ema) > self.jump_cents:
            # New note: keep only the samples that agree with it, and start the EMA there.
            keep = [h for h in self._history if abs(h - med) <= self.jump_cents]
            self._history.clear()
            self._history.extend(keep)
            self._ema = med
        else:
            self._ema += self.alpha * (med - self._ema)
        return cents_to_hz(self._ema)
