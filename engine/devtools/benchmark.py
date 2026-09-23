"""Accuracy, latency and speed benchmarks for the pitch engine (Phase 0).

    python -m devtools.benchmark                 # synthetic test set
    python -m devtools.benchmark --wav-dir DIR   # also recorded samples
    python -m devtools.benchmark --pyin          # compare with librosa pYIN

Recorded samples must be mono or stereo WAV/FLAC files with the expected pitch at the
start of the file name: ``E2.wav``, ``G3_+12c.wav``, ``Db3_-7c_take2.flac``. The
optional ``_±Nc`` suffix gives the detune in cents.

Targets from the spec: accuracy within ±1 cent, first stable reading under 150 ms,
and roughly 1 ms per frame. On a phone, frame cost is measured through Chaquopy.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import time

import numpy as np

from devtools.synth import SR, pluck
from strumbum_engine.engine import HAS_PITCH, SMOOTHED_HZ, Engine
from strumbum_engine.detector import PitchDetector
from strumbum_engine.notes import cents_between, midi_to_hz, parse_note

SYNTH_NOTES = "D2 E2 A2 D3 G3 B3 E4 C3 F3 A3 D4".split()
_NAME = re.compile(r"^([A-Ga-g][#b]?-?\d)(?:_([+-]?\d+(?:\.\d+)?)c)?")


def evaluate(x: np.ndarray, target: float, sr: int = SR) -> dict:
    eng = Engine(sample_rate=sr)
    chunk = 256
    onset = _onset(x, sr)
    first = None
    settled = []
    for i in range(0, len(x) - chunk + 1, chunk):
        r = eng.feed(x[i : i + chunk])
        t = (i + chunk) / sr
        if not r[HAS_PITCH]:
            continue
        err = cents_between(r[SMOOTHED_HZ], target)
        if first is None and abs(err) < 3:
            first = t - onset
        if t - onset > 0.3:
            settled.append(err)
    return {
        "first_ms": None if first is None else 1000 * first,
        "median_err": float(np.median(settled)) if settled else float("nan"),
        "p95_abs_err": float(np.percentile(np.abs(settled), 95)) if settled else float("nan"),
    }


def _onset(x: np.ndarray, sr: int) -> float:
    env = np.abs(x)
    idx = np.flatnonzero(env > 0.1 * env.max())
    return idx[0] / sr if idx.size else 0.0


def synthetic_cases():
    rng = np.random.default_rng(42)
    for note in SYNTH_NOTES:
        for noise in (None, -45.0):
            detune = float(rng.uniform(-30, 30))
            f = midi_to_hz(parse_note(note)) * 2 ** (detune / 1200)
            x = np.concatenate([np.zeros(SR // 5, np.float32), pluck(f, 1.5, noise_db=noise, seed=int(f))])
            yield f"{note} {detune:+.1f}c{' noisy' if noise else ''}", x, f, SR


def wav_cases(folder: pathlib.Path):
    import soundfile as sf

    for p in sorted(folder.iterdir()):
        m = _NAME.match(p.stem)
        if not m or p.suffix.lower() not in {".wav", ".flac"}:
            continue
        x, sr = sf.read(p, dtype="float32", always_2d=True)
        f = midi_to_hz(parse_note(m.group(1))) * 2 ** (float(m.group(2) or 0) / 1200)
        yield p.name, x.mean(axis=1), f, sr


def frame_speed(n: int = 2000) -> float:
    det = PitchDetector()
    x = pluck(110.0, 1.0)
    frames = [x[i : i + 2048] for i in range(0, len(x) - 2048, 512)]
    t0 = time.perf_counter()
    for k in range(n):
        det.estimate(frames[k % len(frames)])
    return 1000 * (time.perf_counter() - t0) / n


def pyin_median(x: np.ndarray, sr: int, target: float) -> float:
    import librosa

    f0, voiced, _ = librosa.pyin(x, fmin=55, fmax=1400, sr=sr, frame_length=4096)
    f0 = f0[voiced & np.isfinite(f0)]
    return float(np.median([cents_between(v, target) for v in f0])) if f0.size else float("nan")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--wav-dir", type=pathlib.Path)
    ap.add_argument("--pyin", action="store_true", help="compare median error with librosa pYIN")
    args = ap.parse_args()

    cases = list(synthetic_cases())
    if args.wav_dir:
        cases += list(wav_cases(args.wav_dir))

    header = f"{'case':<26}{'first ms':>9}{'med err c':>11}{'p95 |err| c':>13}"
    print(header + ("   pYIN med c" if args.pyin else ""))
    worst_first, worst_err, failures = 0.0, 0.0, 0
    for name, x, f, sr in cases:
        r = evaluate(x, f, sr)
        first = r["first_ms"]
        line = f"{name:<26}{'—' if first is None else f'{first:.0f}':>9}{r['median_err']:>11.2f}{r['p95_abs_err']:>13.2f}"
        if args.pyin:
            line += f"{pyin_median(x, sr, f):>14.2f}"
        ok = first is not None and first < 150 and abs(r["median_err"]) < 1.0
        failures += not ok
        worst_first = max(worst_first, first or float("inf"))
        worst_err = max(worst_err, abs(r["median_err"]))
        print(line + ("" if ok else "   FAIL"))
    print(f"\nworst first reading: {worst_first:.0f} ms (target < 150)")
    print(f"worst median error:  {worst_err:.2f} cents (target < 1)")
    print(f"frame cost:          {frame_speed():.3f} ms on this machine (target ~1 ms on a mid-range phone)")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
