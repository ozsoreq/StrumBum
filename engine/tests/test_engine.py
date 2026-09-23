import array

import numpy as np
import pytest

from devtools.synth import SR, babble, pluck
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
