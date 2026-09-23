"""McLeod Pitch Method (MPM) with a YIN fallback.

Both algorithms are built on the same two quantities, computed once per frame:

* ``r(tau)``: the autocorrelation, computed with an FFT.
* ``m(tau)``: the sum of squares of the two overlapping parts of the window, from a
  cumulative sum.

MPM uses the normalized square difference function ``n = 2r / m``. YIN uses the
difference function ``d = m - 2r``. The fallback is therefore almost free.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

_EPS = 1e-12


@dataclass(frozen=True)
class PitchEstimate:
    frequency: float
    """Estimated fundamental in Hz."""
    clarity: float
    """Periodicity confidence in [0, 1]. For YIN this is ``1 - aperiodicity``."""
    rms_db: float
    """Frame level in dBFS."""
    method: str
    """Either ``"mpm"`` or ``"yin"``."""


def _parabolic(y: np.ndarray, i: int) -> tuple[float, float]:
    """Return ``(x, y)`` of the vertex of the parabola through ``y[i-1..i+1]``."""
    if i <= 0 or i >= len(y) - 1:
        return float(i), float(y[i])
    a, b, c = float(y[i - 1]), float(y[i]), float(y[i + 1])
    denom = a - 2.0 * b + c
    if abs(denom) < _EPS:
        return float(i), b
    shift = 0.5 * (a - c) / denom
    return i + shift, b - 0.25 * (a - c) * shift


class PitchDetector:
    """Stateless per-frame pitch estimator.

    ``min_freq``/``max_freq`` bound the lag search. The defaults cover drop tunings
    down to ~60 Hz and everything a guitar plays up to ~1.4 kHz.
    """

    def __init__(
        self,
        sample_rate: int = 48_000,
        window: int = 2048,
        min_freq: float = 60.0,
        max_freq: float = 1400.0,
        mpm_k: float = 0.9,
        yin_threshold: float = 0.15,
    ) -> None:
        if window < 256:
            raise ValueError("window too small")
        self.sample_rate = sample_rate
        self.window = window
        self.min_lag = max(2, int(sample_rate / max_freq))
        self.max_lag = min(window // 2 + window // 4, int(np.ceil(sample_rate / min_freq)) + 2)
        if self.max_lag <= self.min_lag + 2:
            raise ValueError("frequency range does not fit in the window")
        self.mpm_k = mpm_k
        self.yin_threshold = yin_threshold
        self._fft_size = 1 << int(np.ceil(np.log2(2 * window)))
        self._taus = np.arange(self.max_lag + 1)

    # -- shared building blocks ------------------------------------------------

    def _r_and_m(self, x: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        n = self.max_lag + 1
        spec = np.fft.rfft(x, self._fft_size)
        r = np.fft.irfft(spec.real ** 2 + spec.imag ** 2, self._fft_size)[:n]
        cs = np.cumsum(x * x)
        total = cs[-1]
        w = len(x)
        # m(tau) = sum_{j<w-tau} x_j^2 + sum_{j>=tau} x_j^2
        head = cs[w - 1 - self._taus]
        tail = total - np.concatenate(([0.0], cs[: n - 1]))
        return r, head + tail

    # -- MPM ----------------------------------------------------------------------

    def _mpm(self, nsdf: np.ndarray) -> tuple[float, float] | None:
        lo, hi = self.min_lag, self.max_lag
        seg = nsdf[: hi + 1]
        positive = seg > 0.0
        # Skip the positive lobe around tau = 0.
        start = int(np.argmax(~positive)) if not positive.all() else hi
        crossings = np.flatnonzero(np.diff(positive[start:].astype(np.int8))) + start + 1
        # Positive regions run from an upward crossing to the next downward crossing.
        if crossings.size and not positive[crossings[0]]:
            crossings = crossings[1:]
        peaks: list[int] = []
        for k in range(0, crossings.size, 2):
            a = crossings[k]
            b = crossings[k + 1] if k + 1 < crossings.size else hi + 1
            if b <= lo:
                continue
            a = max(a, lo)
            if a >= b:
                continue
            peaks.append(a + int(np.argmax(seg[a:b])))
        if not peaks:
            return None
        values = seg[peaks]
        threshold = self.mpm_k * float(values.max())
        for p, v in zip(peaks, values):
            if v >= threshold:
                lag, clarity = _parabolic(seg, p)
                if lag <= 0:
                    return None
                return lag, min(1.0, clarity)
        return None

    # -- YIN ----------------------------------------------------------------------

    def _yin(self, r: np.ndarray, m: np.ndarray) -> tuple[float, float] | None:
        d = np.maximum(m - 2.0 * r, 0.0)
        cmndf = np.ones_like(d)
        running = np.cumsum(d[1:])
        cmndf[1:] = d[1:] * self._taus[1:] / np.maximum(running, _EPS)
        lo, hi = self.min_lag, self.max_lag
        below = np.flatnonzero(cmndf[lo:hi] < self.yin_threshold)
        if below.size:
            i = lo + int(below[0])
            while i + 1 < hi and cmndf[i + 1] < cmndf[i]:
                i += 1
        else:
            i = lo + int(np.argmin(cmndf[lo:hi]))
        lag, value = _parabolic(cmndf, i)
        if lag <= 0:
            return None
        return lag, float(np.clip(1.0 - value, 0.0, 1.0))

    # -- public ---------------------------------------------------------------

    def estimate(self, frame: np.ndarray) -> PitchEstimate | None:
        """Estimate the pitch of one analysis window.

        Returns ``None`` only for digital silence. Callers apply their own
        level and clarity gates to the returned estimate.
        """
        x = np.asarray(frame, dtype=np.float64)
        if x.shape != (self.window,):
            raise ValueError(f"expected {self.window} samples, got {x.shape}")
        x = x - x.mean()
        energy = float(np.dot(x, x))
        if energy <= _EPS:
            return None
        rms_db = 10.0 * np.log10(energy / self.window + _EPS)
        r, m = self._r_and_m(x)
        nsdf = 2.0 * r / np.maximum(m, _EPS)
        mpm = self._mpm(nsdf)
        if mpm is not None and mpm[1] >= 0.5:
            lag, clarity = mpm
            return PitchEstimate(self.sample_rate / lag, clarity, rms_db, "mpm")
        yin = self._yin(r, m)
        if yin is None:
            return None
        lag, clarity = yin
        return PitchEstimate(self.sample_rate / lag, clarity, rms_db, "yin")
