"""Render the reference tones the app plays for tuning by ear.

    python -m devtools.render_tones [out_dir]

It writes one Ogg Vorbis file per MIDI note, from C2 (36) to G4 (67), all at
A4 = 440 Hz. The app changes playback speed with SoundPool's rate parameter to
follow the user's calibration: ``rate = a4 / 440``, which is within 0.94–1.06 for
415–466 Hz. That range covers every open string of every guitar preset.
"""

from __future__ import annotations

import pathlib
import sys

import numpy as np
import soundfile as sf

from devtools.synth import pluck
from strumbum_engine.notes import midi_to_hz

SR = 44_100
LOW, HIGH = 36, 67
DEFAULT_OUT = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/res/raw"


def render(midi: int) -> np.ndarray:
    x = pluck(midi_to_hz(midi), seconds=3.0, sr=SR, amp=0.7, harmonics=10, brightness=0.8, decay=2.5, seed=midi)
    fade = int(0.08 * SR)
    x[-fade:] *= np.linspace(1.0, 0.0, fade, dtype=np.float32)
    return x


def main(out: pathlib.Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    for midi in range(LOW, HIGH + 1):
        sf.write(out / f"tone_{midi}.ogg", render(midi), SR, format="OGG", subtype="VORBIS")
    total = sum(f.stat().st_size for f in out.glob("tone_*.ogg"))
    print(f"wrote {HIGH - LOW + 1} tones to {out} ({total / 1024:.0f} KiB)")


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUT)
