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
# Returned by ``_mpm`` when the strongest periodicity lies outside the lag range.
_OUT_OF_BAND = (0.0, 0.0)

# String stiffness puts upper partials sharp of k * f0, and the autocorrelation peak follows
# them (about +1.9 cents at stiffness B = 1e-4). Above this frequency the fundamental's own
# spectral peak is far enough from its neighbours to measure directly, which removes the bias.
# Below it, leakage from the second partial costs more than the bias.
_REFINE_MIN_HZ = 180.0
# Only refine clean frames (one clear source); attack, noisy and two-string frames keep MPM.
_REFINE_MIN_CLARITY = 0.99
_REFINE_SEARCH = 2.0 ** (30.0 / 1200.0)  # look for the fundamental within +-30 cents of MPM


@dataclass(frozen=True)
class PitchEstimate:
    frequency: float
    """Estimated fundamental in Hz."""
    clarity: float
    """Periodicity confidence in [0, 1]. For YIN this is ``1 - aperiodicity``."""
    rms_db: float
    """Frame level in dBFS."""
    method: str
    """``"mpm"``, ``"yin"``, or ``"none"`` when the frame has no pitch in the band."""


def _is_peak(y: np.ndarray, i: int) -> bool:
    """True if ``y[i]`` is an interior local maximum (rising into it, not rising after it)."""
    return 0 < i < len(y) - 1 and y[i - 1] < y[i] >= y[i + 1]


def _parabolic(y: np.ndarray, i: int) -> tuple[float, float]:
    """Return ``(x, y)`` of the vertex of the parabola through ``y[i-1..i+1]``.

    Callers only pass strict interior extrema, so the vertex lies within half a sample of ``i``.
    The clamp guards against round-off; an unbounded shift extrapolates far past the data.
    """
    a, b, c = float(y[i - 1]), float(y[i]), float(y[i + 1])
    denom = a - 2.0 * b + c
    if abs(denom) < _EPS:
        return float(i), b
    shift = min(0.5, max(-0.5, 0.5 * (a - c) / denom))
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
        # 4-term Blackman-Harris: sidelobes under -90 dB, so neighbouring partials don't leak.
        t = 2.0 * np.pi * np.arange(window) / window
        self._refine_window = 0.35875 - 0.48829 * np.cos(t) + 0.14128 * np.cos(2 * t) - 0.01168 * np.cos(3 * t)
        self._refine_fft = 8 * window

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
        # Lobes below min_lag count too: if the first strong peak is there, the note is above
        # max_freq and must be rejected, not read an octave low from its second period.
        peaks: list[int] = []
        for k in range(0, crossings.size, 2):
            a = crossings[k]
            b = crossings[k + 1] if k + 1 < crossings.size else hi + 1
            peaks.append(a + int(np.argmax(seg[a:b])))
        if not peaks:
            return None
        values = seg[peaks]
        threshold = self.mpm_k * float(values.max())
        for p, v in zip(peaks, values):
            if v >= threshold:
                # Below min_lag, or a lobe cut off by max_lag: the pitch is out of band.
                if p < lo or not _is_peak(nsdf, p):
                    return _OUT_OF_BAND
                lag, clarity = _parabolic(nsdf, p)
                if not lo <= lag <= hi:
                    return _OUT_OF_BAND
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
        if not below.size:
            # Standard YIN: no dip under the threshold means unvoiced. The global minimum
            # of an aperiodic frame is not a pitch.
            return None
        i = lo + int(below[0])
        while i + 1 < hi and cmndf[i + 1] < cmndf[i]:
            i += 1
        # The dip must be a true minimum inside the lag range, not a slope cut off by it.
        if not _is_peak(-cmndf, i):
            return None
        lag, value = _parabolic(cmndf, i)
        if not lo <= lag <= hi:
            return None
        return lag, float(np.clip(1.0 - value, 0.0, 1.0))

    # -- stiffness correction -------------------------------------------------------

    def _refine(self, x: np.ndarray, f: float) -> float:
        """Re-measure ``f`` from the fundamental's own peak in a zero-padded spectrum.

        Falls back to ``f`` when the fundamental isn't a clear peak near it (weak or
        missing fundamental, e.g. a phone mic's high-pass).
        """
        spec = np.abs(np.fft.rfft(x * self._refine_window, self._refine_fft))
        df = self.sample_rate / self._refine_fft
        lo = int(f / _REFINE_SEARCH / df)
        hi = int(np.ceil(f * _REFINE_SEARCH / df))
        k = lo + int(np.argmax(spec[lo : hi + 1]))
        if k <= lo or k >= hi or spec[k] < 0.1 * spec.max():
            return f
        x_peak, _ = _parabolic(np.log(spec[k - 1 : k + 2] + _EPS), 1)
        return (k - 1 + x_peak) * df

    # -- public ---------------------------------------------------------------

    def estimate(self, frame: np.ndarray) -> PitchEstimate | None:
        """Estimate the pitch of one analysis window.

        Returns ``None`` only for digital silence. A frame with no pitch inside the
        band (noise, or a note below ``min_freq``/above ``max_freq``) comes back with
        frequency and clarity 0 and method ``"none"``. Callers apply their own level
        and clarity gates to the returned estimate.
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
        if mpm == _OUT_OF_BAND:
            return PitchEstimate(0.0, 0.0, rms_db, "none")
        if mpm is not None and mpm[1] >= 0.5:
            lag, clarity = mpm
            f = self.sample_rate / lag
            if f >= _REFINE_MIN_HZ and clarity >= _REFINE_MIN_CLARITY:
                f = self._refine(x, f)
            return PitchEstimate(f, clarity, rms_db, "mpm")
        yin = self._yin(r, m)
        if yin is None:
            return PitchEstimate(0.0, 0.0, rms_db, "none")
        lag, clarity = yin
        return PitchEstimate(self.sample_rate / lag, clarity, rms_db, "yin")
