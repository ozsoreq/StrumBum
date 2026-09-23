# StrumBum

A free, offline Android guitar tuner that tells you which way to turn the peg. All pitch detection runs on the phone with classic DSP. There's no account, no cloud, and no ML.

## Layout

| Path | What |
|---|---|
| `engine/` | Python pitch engine: McLeod Pitch Method with a YIN fallback, plus median + EMA smoothing. It's embedded in the app through Chaquopy. |
| `engine/tests/` | pytest suite on synthetic plucks, noise and speech-like babble |
| `engine/devtools/` | Dev-machine tools: synthetic signals, a benchmark harness, and a reference-tone renderer. None of this ships in the app. |
| `app/` | Kotlin + Jetpack Compose app |
| `.github/workflows/build.yml` | CI: engine tests and benchmark, then Android unit tests, APK and AAB |

### How audio flows

```
AudioRecord 48 kHz float ──► strumbum-audio thread ──► Python Engine.feed (MPM/YIN, gates, smoother)
                                                          │
                                   StateFlow<PitchReading> ◄┘
                                          │
MainViewModel: string picker, in-tune detector ──► Compose tuner (spring needle, haptic tick on lock)
```

The engine analyses a 2048-sample window every 512 samples, about 94 readings per second. A frame is dropped when its level is below −55 dBFS or its MPM clarity is below 0.9. When a new pluck follows silence, the smoother resets so the first reading comes quickly.

## Pitch engine

```bash
cd engine
pip install -e ".[dev]"
pytest -q
python -m devtools.benchmark                   # accuracy, first-reading latency, frame cost
python -m devtools.benchmark --wav-dir DIR     # add real recordings, e.g. E2.wav, G3_+12c.wav
python -m devtools.benchmark --pyin            # compare with librosa pYIN (pip install -e ".[reference]")
python -m devtools.render_tones                # regenerate app/src/main/res/raw/tone_*.ogg
```

Results on the synthetic set. The spec targets are ±1 cent and under 150 ms to the first stable reading.

- Worst median error: 0.42 cents. Clean signals stay under 0.1.
- First stable reading: 24–56 ms after the pluck.
- Frame cost: about 0.24 ms on a desktop CPU. It still needs profiling on a real phone.

**Still to do:** run the benchmark on real guitar recordings. The synthetic plucks include string stiffness and noise, but real recordings are the real test.

## Android app

Requirements: JDK 17 and the Android SDK (compileSdk 36). Chaquopy's `buildPython` must also be **Python 3.13**, the same version as the Python embedded in the app. Chaquopy finds `python3.13` on your PATH; to point it somewhere else, pass `-Pstrumbum.buildPython=/path/to/python3.13`.

```bash
./gradlew testDebugUnitTest assembleDebug   # debug APK (arm64-v8a)
./gradlew bundleRelease                     # AAB for Play
```

### Configuration

- **AdMob:** `gradle.properties` defaults to Google's public **test** IDs. For release builds, set `strumbum.admobAppId` and `strumbum.admobBannerId` in `~/.gradle/gradle.properties`, or use the `ADMOB_APP_ID` / `ADMOB_BANNER_ID` CI secrets.
- **Release signing:** either create `keystore.properties` in the repo root (gitignored) with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`, or set these CI secrets:
  - `STRUMBUM_KEYSTORE_BASE64`
  - `STRUMBUM_STORE_PASSWORD`
  - `STRUMBUM_KEY_ALIAS`
  - `STRUMBUM_KEY_PASSWORD`
- **ABIs:** arm64-v8a only. To add armeabi-v7a later, add it to `abiFilters` in `app/build.gradle.kts`.
- **Python 3.13:** Chaquopy recommends it for Play's 16 KB page-size requirement.

### What's in v1.0 (MVP)

- Chromatic tuner: note, octave, cents offset (±50) and Hz.
- Auto string detection with hysteresis. Tap a string to lock it.
- Presets: Standard, Drop D, DADGAD, Open G/D/E, Half-step down, Full-step down, plus a Chromatic mode.
- A4 calibration from 415 to 466 Hz.
- In-tune lock: the needle turns green and you feel a haptic tick after the pitch holds within ±3 cents for 400 ms.
- Reference tones, synthesized in Python and played through SoundPool. Hold a string or tap Play. Calibration is applied through the playback rate, and the mic is muted while a tone plays.
- Themes: dark-first, follows the system, with AMOLED black and high-contrast options.
- Accessibility: arrows plus "Tune up/down" text so colour is never the only signal. Touch targets are at least 48 dp, and TalkBack gets labels and polite live regions.
- Onboarding with two cards: why the app needs the mic, then a live "pluck a string" demo.
- Ads: Google's UMP consent is asked after onboarding. Adaptive banners appear only on the Tunings and Settings pages, never on the tuner or during onboarding. An "Ad privacy choices" entry appears where UMP requires one.

### Not in this build

These are on the spec's roadmap:

- **Phase 2:** Peg Coach, Tune-All (with its capped interstitial), noise-proof mode, rewarded unlocks, Practice corner.
- **Phase 4:** Strobe view, String Health.
- **v1.1:** Bass, ukulele, 7-string and custom tunings.
- **Firebase Analytics/Crashlytics:** needs a `google-services.json` from your Firebase project.
- **Privacy policy page:** required for the mic permission and ads.
