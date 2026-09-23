"""Synthetic guitar-like test signals for the dev machine only; not shipped in the app."""

from __future__ import annotations

import numpy as np

SR = 48_000


def sine(freq: float, seconds: float = 0.5, sr: int = SR, amp: float = 0.5) -> np.ndarray:
    t = np.arange(int(seconds * sr)) / sr
    return (amp * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def pluck(
    freq: float,
    seconds: float = 1.5,
    sr: int = SR,
    amp: float = 0.5,
    harmonics: int = 12,
    inharmonicity: float = 3e-5,
    brightness: float = 1.0,
    second_harmonic_boost: float = 1.0,
    decay: float = 1.2,
    noise_db: float | None = None,
    seed: int = 0,
) -> np.ndarray:
    """Additive plucked string with a percussive attack, decaying partials, and slightly
    sharp upper partials (string stiffness). ``decay`` is the fundamental's T60-ish time
    constant in seconds. Higher partials decay faster."""
    rng = np.random.default_rng(seed)
    n = int(seconds * sr)
    t = np.arange(n) / sr
    out = np.zeros(n)
    for k in range(1, harmonics + 1):
        fk = k * freq * np.sqrt(1 + inharmonicity * k * k)
        if fk >= sr / 2 * 0.9:
            break
        a = (1.0 / k) ** (1.5 / max(brightness, 1e-3))
        if k == 2:
            a *= second_harmonic_boost
        env = np.exp(-t * k ** 0.7 / decay)
        out += a * env * np.sin(2 * np.pi * fk * t + rng.uniform(0, 2 * np.pi))
    # Pick attack: 5 ms burst of filtered noise.
    burst = int(0.005 * sr)
    click = rng.standard_normal(burst) * np.hanning(burst * 2)[burst:] * 0.3
    out[:burst] += click
    attack = np.minimum(1.0, t / 0.002)
    out *= attack
    out *= amp / np.max(np.abs(out))
    if noise_db is not None:
        out += rng.standard_normal(n) * 10 ** (noise_db / 20)
    return out.astype(np.float32)


def white_noise(seconds: float = 0.5, sr: int = SR, db: float = -20.0, seed: int = 1) -> np.ndarray:
    rng = np.random.default_rng(seed)
    return (rng.standard_normal(int(seconds * sr)) * 10 ** (db / 20)).astype(np.float32)


def babble(seconds: float = 1.0, sr: int = SR, db: float = -25.0, seed: int = 2) -> np.ndarray:
    """Crude speech-like noise: pink-ish noise with a syllable-rate amplitude envelope and
    a wandering formant-free glottal buzz. It has weak, unstable periodicity, which is
    exactly what the clarity gate must reject."""
    rng = np.random.default_rng(seed)
    n = int(seconds * sr)
    t = np.arange(n) / sr
    white = rng.standard_normal(n)
    pink = np.cumsum(white)
    pink -= np.convolve(pink, np.ones(64) / 64, mode="same")
    f0 = 120 + 40 * np.sin(2 * np.pi * 3.1 * t) + 25 * np.sin(2 * np.pi * 7.3 * t)
    buzz = np.sign(np.sin(2 * np.pi * np.cumsum(f0) / sr)) * 0.3
    env = 0.5 + 0.5 * np.sin(2 * np.pi * 4.0 * t) ** 2
    x = (pink / np.std(pink) + buzz) * env
    x *= 10 ** (db / 20) / np.sqrt(np.mean(x * x))
    return x.astype(np.float32)
