# ZenPEQ

Lightweight system-wide parametric EQ for Android (API 28+) built on `DynamicsProcessing`.

- Up to 10 parametric filters (PK / low shelf / high shelf), live-edited
- Auto-Preamp, AutoEQ / squig.link text import, JSON profiles in DataStore
- Wired 3.5 mm headset detection: plug → IEM profile on, unplug → bypass
- Two Quick Settings tiles: EQ on/off, cycle profile

## Build
```
gradle wrapper --gradle-version 8.9   # once, to generate ./gradlew
./gradlew assembleDebug
```
CI (`.github/workflows/build.yml`) uses `gradle/actions/setup-gradle`, so no wrapper is required there.
Optional repo secrets for a signed release: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Notes
- The engine attaches to audio session 0. Google deprecated global effects, and some OEM builds ignore or reject them; the app shows the error when that happens.
- DynamicsProcessing EQ bands have only a cutoff and a gain (no Q). ZenPEQ computes the exact RBJ biquad response of your filters and renders it onto 48 log-spaced bands (falls back to 24 / 10 if a device refuses).

## v1.1
- Export/share any profile as JSON or Equalizer APO `.txt` (shelves exported as `LSC`/`HSC`; `LSQ`/`HSQ` are accepted on import). Import `.json` or `.txt`.
- Audio session mode: Auto (global, falling back to per-app), Global, Per-app. Per-app attaches to sessions announced via `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`; players that never broadcast it can't be equalized in that mode.
- Boot restart + battery-optimization hint. Note: targeting API 35+ forbids starting a `mediaPlayback` foreground service from `BOOT_COMPLETED`.
- Drag band points on the graph; ±0.1 dB steppers; profile search.
