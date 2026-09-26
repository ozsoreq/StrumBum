import array

import numpy as np
import pytest

from devtools.synth import SR, babble, pluck, white_noise
from strumbum_engine.detector import PitchEstimate
from strumbum_engine.engine import CLARITY, HAS_PITCH, RAW_HZ, SILENT_FRAMES, SMOOTHED_HZ, Engine
from strumbum_engine.notes import cents_between, midi_to_hz, parse_note


def run(engine: Engine, x: np.ndarray, chunk: int = 256):
    """Feed in chunks; return [(time_s, result_tuple)] after each chunk."""
    out = []
    for i in range(0, len(x) - chunk + 1, chunk):
        out.append(((i + chunk) / SR, engine.feed(x[i : i + chunk])))
    return out


def with_silence(x: np.ndarray, before_s: float = 0.3) -> np.ndarray:
    return np.concatenate([np.zeros(int(before_s * SR), np.float32), x])


@pytest.mark.parametrize("note", ["E2", "A2", "D3", "G3", "B3", "E4", "D2"])
def test_first_stable_reading_under_150ms(note):
    target = midi_to_hz(parse_note(note))
    onset = 0.3
    x = with_silence(pluck(target, seconds=1.0), onset)
    for t, r in run(Engine(), x):
        if r[HAS_PITCH] and abs(cents_between(r[SMOOTHED_HZ], target)) < 3:
            assert t - onset < 0.150, f"{note}: first stable reading after {1000 * (t - onset):.0f} ms"
            break
    else:
        pytest.fail("never produced a stable reading")


def test_settled_reading_within_one_cent():
    target = midi_to_hz(parse_note("B3")) * 2 ** (-12 / 1200)
    res = run(Engine(), with_silence(pluck(target, seconds=1.2)))
    tail = [r[SMOOTHED_HZ] for t, r in res if 0.6 < t < 1.2 and r[HAS_PITCH]]
    assert tail
    assert max(abs(cents_between(h, target)) for h in tail) < 1.0


def test_chunk_size_does_not_change_results():
    x = with_silence(pluck(196.0, seconds=0.6), 0.1)
    a = Engine()
    b = Engine()
    ra = [a.feed(x[i : i + 512]) for i in range(0, len(x) - 511, 512)]
    # Odd chunk sizes, delivered in a different pattern, reach the same final state.
    i = 0
    sizes = [100, 700, 37, 1200, 3]
    k = 0
    while i < len(ra) * 512:
        n = min(sizes[k % len(sizes)], len(ra) * 512 - i)
        rb = b.feed(x[i : i + n])
        i += n
        k += 1
    assert rb == ra[-1]


def test_silence_and_babble_never_report_pitch():
    x = np.concatenate([np.zeros(SR // 4, np.float32), babble(1.0)])
    assert not any(r[HAS_PITCH] for _, r in run(Engine(), x))


def test_holds_last_value_while_gated():
    target = 146.83
    x = np.concatenate([pluck(target, seconds=0.5), np.zeros(SR // 5, np.float32)])
    res = run(Engine(), x)
    last = res[-1][1]
    assert last[HAS_PITCH] == 0.0
    assert last[SILENT_FRAMES] > 5
    assert abs(cents_between(last[SMOOTHED_HZ], target)) < 2


def test_level_gate_blocks_quiet_input():
    e = Engine(min_db=-30)
    res = run(e, pluck(110.0, seconds=0.4, amp=0.005))
    assert not any(r[HAS_PITCH] for _, r in res)
    e.set_gate(-80, 0.9)
    res = run(e, pluck(110.0, seconds=0.4, amp=0.005))
    assert any(r[HAS_PITCH] for _, r in res)


def test_accepts_buffer_protocol_objects():
    # Chaquopy hands over Java float[] as a buffer; array('f') is the closest stand-in.
    x = pluck(110.0, seconds=0.3)
    e = Engine()
    r = e.feed(array.array("f", x.tolist()))
    assert r[HAS_PITCH] == 1.0
    assert abs(cents_between(r[RAW_HZ], 110.0)) < 2
    assert r[CLARITY] >= 0.9


def test_reset_clears_state():
    e = Engine()
    e.feed(pluck(110.0, seconds=0.3))
    e.reset()
    r = e.feed(np.zeros(512, np.float32))
    assert r[HAS_PITCH] == 0.0
    # Even a chunk too short to complete a hop must not return the pre-reset reading.
    e.feed(pluck(110.0, seconds=0.3))
    e.reset()
    assert e.feed(np.zeros(100, np.float32))[:2] == (0.0, 0.0)


@pytest.mark.parametrize("kw", [dict(hop=0), dict(hop=-1), dict(window=2048, hop=4096)])
def test_rejects_bad_hop(kw):
    with pytest.raises(ValueError):
        Engine(**kw)


@pytest.mark.parametrize("bad", [np.nan, np.inf, -np.inf])
def test_non_finite_samples_do_not_poison_the_output(bad):
    x = with_silence(pluck(110.0, seconds=1.5))
    x[int(0.8 * SR)] = bad
    res = [r for _, r in run(Engine(), x)]
    assert all(np.isfinite(v) for r in res for v in r[:5])
    tail = [r[SMOOTHED_HZ] for r in res[-20:] if r[HAS_PITCH]]
    assert tail and all(abs(cents_between(h, 110.0)) < 1 for h in tail)


def test_non_finite_estimates_fail_the_gates(monkeypatch):
    e = Engine()
    monkeypatch.setattr(e.detector, "estimate", lambda frame: PitchEstimate(float("nan"), float("nan"), float("nan"), "mpm"))
    assert all(r[HAS_PITCH] == 0.0 for _, r in run(e, pluck(110.0, seconds=0.3)))


@pytest.mark.parametrize(
    "convert",
    [
        lambda a: a.astype(np.float64),
        lambda a: array.array("d", a.astype(np.float64)),
        lambda a: a.astype(np.float64).tolist(),
    ],
    ids=["float64 ndarray", "array('d')", "list"],
)
def test_non_float32_input_is_converted_not_reinterpreted(convert):
    x = with_silence(pluck(110.0, seconds=0.8))
    e = Engine()
    r = None
    for i in range(0, len(x) - 511, 512):
        r = e.feed(convert(x[i : i + 512]))
    assert r[HAS_PITCH] == 1.0
    assert abs(cents_between(r[SMOOTHED_HZ], 110.0)) < 1


def _fake_frames(e, monkeypatch, voiced):
    """Drive the engine with scripted detector results: True = a clean 110 Hz frame."""
    script = iter(voiced)
    monkeypatch.setattr(
        e.detector,
        "estimate",
        lambda frame: PitchEstimate(110.0, 0.99, -20.0, "mpm") if next(script) else PitchEstimate(0.0, 0.0, -20.0, "none"),
    )
    return [e.feed(np.zeros(e.hop, np.float32)) for _ in voiced]


def test_a_lone_frame_after_silence_is_not_published(monkeypatch):
    res = _fake_frames(Engine(), monkeypatch, [False] * 20 + [True] + [False] * 20)
    assert not any(r[HAS_PITCH] for r in res)
    assert res[-1][SMOOTHED_HZ] == 0.0


def test_a_pluck_is_published_once_confirmed(monkeypatch):
    res = _fake_frames(Engine(confirm_frames=2), monkeypatch, [False] * 20 + [True] * 3)
    assert [r[HAS_PITCH] for r in res[-3:]] == [0.0, 1.0, 1.0]
    # Short gaps inside a note don't need confirming again.
    res = _fake_frames(Engine(), monkeypatch, [False] * 20 + [True] * 5 + [False] * 3 + [True])
    assert res[-1][HAS_PITCH] == 1.0


@pytest.mark.parametrize("note", ["E2", "A2", "G3", "E4"])
@pytest.mark.parametrize("detune", [5.0, -8.0])
def test_held_value_after_fading_into_noise(note, detune):
    # Regression: a note that fades into room noise used to be held at its noisiest last
    # frames, up to 8 c away from where it had settled ("In tune" for a +5 c string).
    # The held value must stay where the healthy part of the note settled.
    target = midi_to_hz(parse_note(note)) * 2 ** (detune / 1200)
    drift = []
    for seed in range(4):
        x = with_silence(pluck(target, seconds=6.0, decay=1.5, seed=seed))
        x = x + white_noise(len(x) / SR, db=-45, seed=100 + seed)
        res = run(Engine(), x)
        assert not res[-1][1][HAS_PITCH], "the note should have faded below the gate"
        settled = np.median([r[SMOOTHED_HZ] for t, r in res if 0.6 < t < 1.3 and r[HAS_PITCH]])
        drift.append(abs(cents_between(res[-1][1][SMOOTHED_HZ], settled)))
    assert np.median(drift) < 1.0
    assert max(drift) < 2.0
