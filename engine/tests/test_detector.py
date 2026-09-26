import numpy as np
import pytest

from devtools.synth import SR, babble, pluck, sine, white_noise
from strumbum_engine.detector import PitchDetector
from strumbum_engine.notes import cents_between, midi_to_hz, parse_note

WINDOW = 2048


def frames(x: np.ndarray, hop: int = 512, skip_s: float = 0.0):
    start = int(skip_s * SR)
    for i in range(start, len(x) - WINDOW, hop):
        yield x[i : i + WINDOW]


@pytest.fixture(scope="module")
def det():
    return PitchDetector(SR, WINDOW)


@pytest.mark.parametrize("freq", [61.7, 65.41, 73.42, 82.41, 110.0, 146.83, 196.0, 246.94, 329.63, 440.0, 659.26, 987.77, 1318.5])
def test_pure_sine_is_within_half_a_cent(det, freq):
    errors = [cents_between(det.estimate(f).frequency, freq) for f in frames(sine(freq, 0.3))]
    assert max(abs(e) for e in errors) < 0.5


# Standard, Drop D, DADGAD, open tunings, and half/full step down: every open string the
# presets can ask for.
PRESET_STRINGS = sorted(
    {
        parse_note(n)
        for n in "E2 A2 D3 G3 B3 E4 D2 A3 D4 G2 B2 F#3 G#3 C#3 Eb2 Ab2 Db3 Gb3 Bb3 Eb4 C3 F3".split()
    }
)


@pytest.mark.parametrize("midi", PRESET_STRINGS)
@pytest.mark.parametrize("detune", [-23.0, 0.0, 7.5])
def test_plucked_strings_median_within_one_cent(det, midi, detune):
    target = midi_to_hz(midi) * 2 ** (detune / 1200)
    x = pluck(target, seconds=1.0, seed=midi)
    ests = [det.estimate(f) for f in frames(x, skip_s=0.05)]
    hz = [e.frequency for e in ests if e and e.clarity >= 0.9]
    assert len(hz) > 50
    assert abs(cents_between(float(np.median(hz)), target)) < 1.0
    # No octave (or fifth) errors in individual frames.
    assert all(abs(cents_between(h, target)) < 30 for h in hz)


@pytest.mark.parametrize("midi", [parse_note("E2"), parse_note("A2"), parse_note("D2")])
def test_no_octave_error_with_dominant_second_harmonic(det, midi):
    target = midi_to_hz(midi)
    x = pluck(target, seconds=0.8, second_harmonic_boost=2.5, seed=3)
    hz = [e.frequency for e in (det.estimate(f) for f in frames(x, skip_s=0.05)) if e and e.clarity >= 0.9]
    assert hz
    assert all(abs(cents_between(h, target)) < 30 for h in hz)


def test_noisy_room_still_reads_the_string(det):
    target = midi_to_hz(parse_note("G3"))
    x = pluck(target, seconds=0.8, noise_db=-40, seed=5)
    hz = [e.frequency for e in (det.estimate(f) for f in frames(x, skip_s=0.05, hop=512)) if e and e.clarity >= 0.9]
    assert len(hz) > 30
    assert abs(cents_between(float(np.median(hz)), target)) < 1.0


def test_silence_returns_none(det):
    assert det.estimate(np.zeros(WINDOW, dtype=np.float32)) is None


@pytest.mark.parametrize("make", [white_noise, babble])
def test_noise_is_low_clarity(det, make):
    x = make()
    clarities = [e.clarity for e in (det.estimate(f) for f in frames(x)) if e]
    passed = sum(c >= 0.9 for c in clarities)
    assert passed <= len(clarities) * 0.05


def test_rejects_wrong_frame_size(det):
    with pytest.raises(ValueError):
        det.estimate(np.zeros(100))


def test_yin_fallback_when_mpm_finds_no_peak(det, monkeypatch):
    monkeypatch.setattr(det, "_mpm", lambda nsdf: None)
    target = midi_to_hz(parse_note("A2"))
    x = pluck(target, seconds=0.5)
    ests = [det.estimate(f) for f in frames(x, skip_s=0.05)]
    assert {e.method for e in ests} == {"yin"}
    hz = [e.frequency for e in ests if e.clarity >= 0.9]
    assert abs(cents_between(float(np.median(hz)), target)) < 1.0


BAND = (SR / PitchDetector(SR, WINDOW).max_lag, SR / PitchDetector(SR, WINDOW).min_lag)


@pytest.mark.parametrize("seed", range(40))
def test_babble_never_reports_a_pitch_outside_the_band(det, seed):
    # Regression: the YIN fallback's parabola used to extrapolate past max_lag and report
    # e.g. 23 Hz at clarity 1.0 on babble.
    for f in frames(babble(3.0, db=-30, seed=seed)):
        e = det.estimate(f)
        if e and e.method != "none":
            assert BAND[0] <= e.frequency <= BAND[1]
            assert 0.0 <= e.clarity <= 1.0


@pytest.mark.parametrize("freq", [30.0, 41.2, 50.0, 58.0, 1420.0, 1500.0, 1760.0, 2093.0])
def test_notes_outside_the_band_are_rejected(det, freq):
    # Below 60 Hz or above ~1411 Hz there is no right answer inside the band, so no
    # confident reading at all (not a wrong note or an octave).
    x = pluck(freq, seconds=1.0)
    assert not [e for e in (det.estimate(f) for f in frames(x)) if e and e.clarity >= 0.5]


@pytest.mark.parametrize("freq", [61.0, 1400.0])
def test_band_edges_still_read(det, freq):
    hz = [e.frequency for e in (det.estimate(f) for f in frames(pluck(freq, seconds=0.8), skip_s=0.05)) if e.clarity >= 0.9]
    assert len(hz) > 50
    assert abs(cents_between(float(np.median(hz)), freq)) < 1.0


def test_yin_without_a_dip_is_unvoiced(det, monkeypatch):
    monkeypatch.setattr(det, "_mpm", lambda nsdf: None)
    ests = [det.estimate(f) for f in frames(white_noise(0.5, db=-30))]
    assert ests and all(e.method == "none" and e.clarity == 0.0 and e.frequency == 0.0 for e in ests)


@pytest.mark.parametrize("note", ["G3", "B3", "E4", "A4"])
@pytest.mark.parametrize("stiffness", [1e-4, 3e-4])
def test_stiff_strings_read_the_fundamental(det, note, stiffness):
    # Stiffness puts partial k at k*f0*sqrt(1 + B k^2); the autocorrelation alone reads
    # +1.9 c (B = 1e-4) and +5.3 c (B = 3e-4) sharp. The fundamental itself is only
    # sqrt(1 + B) sharp.
    target = midi_to_hz(parse_note(note))
    x = pluck(target, seconds=1.0, inharmonicity=stiffness, decay=30.0, seed=7)
    hz = [e.frequency for e in (det.estimate(f) for f in frames(x, skip_s=0.05)) if e.clarity >= 0.9]
    expected = cents_between(target * np.sqrt(1 + stiffness), target)
    assert abs(cents_between(float(np.median(hz)), target) - expected) < 0.3


def test_parabolic_stays_between_its_neighbours():
    from strumbum_engine.detector import _parabolic

    # A monotone slope has no vertex nearby; the shift must not run off to it.
    x, _ = _parabolic(np.array([3.0, 2.0, 1.5]), 1)
    assert 0.5 <= x <= 1.5
