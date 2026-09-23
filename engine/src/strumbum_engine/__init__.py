"""StrumBum pitch engine: on-device pitch detection with classic DSP (no ML, no network).

The Android app embeds this package through Chaquopy and talks to it only via
:class:`strumbum_engine.engine.Engine`. Everything else is importable for tests
and the offline benchmark harness.
"""

from .detector import PitchDetector, PitchEstimate
from .engine import Engine
from .smoothing import PitchSmoother

__all__ = ["Engine", "PitchDetector", "PitchEstimate", "PitchSmoother"]
