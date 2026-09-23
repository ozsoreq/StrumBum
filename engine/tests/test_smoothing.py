import pytest

from strumbum_engine.smoothing import PitchSmoother, cents_to_hz, hz_to_cents


def test_round_trip():
    assert cents_to_hz(hz_to_cents(82.41)) == pytest.approx(82.41)


def test_single_octave_glitch_is_ignored():
    s = PitchSmoother()
    for _ in range(5):
        s.push(110.0)
    out = s.push(220.0)
    assert out == pytest.approx(110.0, rel=1e-6)


def test_note_change_snaps_instead_of_gliding():
    s = PitchSmoother()
    for _ in range(6):
        s.push(110.0)
    outs = [s.push(146.83) for _ in range(3)]
    assert outs[-1] == pytest.approx(146.83, rel=1e-6)


def test_small_moves_are_eased():
    s = PitchSmoother(alpha=0.35)
    for _ in range(6):
        s.push(440.0)
    sharp = 440.0 * 2 ** (10 / 1200)
    first = [s.push(sharp) for _ in range(3)][-1]
    assert 440.0 < first < sharp
    for _ in range(40):
        last = s.push(sharp)
    assert hz_to_cents(last) == pytest.approx(hz_to_cents(sharp), abs=0.01)


def test_rejects_bad_params():
    with pytest.raises(ValueError):
        PitchSmoother(alpha=0)
