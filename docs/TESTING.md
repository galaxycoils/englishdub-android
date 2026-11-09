# Android testing with Autonom and Test Android Apps

The Mac continuation uses **Autonom** for explicit device sessions, UI trees, screenshots and the session journal, together with the **Test Android Apps** `android-emulator-qa` and `android-performance` skills for Android validation. The plugin supplies adb-driven procedures and profiling guidance; it does not provision a device automatically. Use only a connected, authorized adb target. Supporting JVM regressions and lint are part of the same validation workflow.

## Reproducible setup

Record the source revision, OS/API, ABI, RAM, device model, build variant, input fixtures, and run count. Use an ARM64 phone for real performance claims. A debug ABI override supports an x86 or x86_64 emulator without changing the default ARM64 release.

```sh
adb devices -l
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest # default ARM64; use -Pdub.abi=x86_64 for an Intel emulator
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -r -w \
  -e class app.englishdub.OfflinePipelineTest,app.englishdub.SubtitleCompatibilityTest \
  -e notAnnotation androidx.test.filters.LargeTest \
  app.englishdub.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL shell am instrument -r -w \
  -e class app.englishdub.PlaybackSynchronizationTest,app.englishdub.PlaybackRecoveryTest \
  app.englishdub.test/androidx.test.runner.AndroidJUnitRunner
```

Run core fixtures before the playback test; they create a real saved AAC dub in the debug app. Install an English offline TTS engine/voice first, then enable airplane mode and disable Wi-Fi/data. This delivery used the separately installed official eSpeak NG 1.52.0 engine. It is not bundled with EnglishDub. Tests that lack a required voice or model explicitly skip; report skips separately.

Native tests require the exact verified speech model in the debuggable app's private `files/fixture-model.bin`. On the test emulator, `adb push MODEL /data/local/tmp/fixture-model.bin` followed by `adb shell run-as app.englishdub cp /data/local/tmp/fixture-model.bin files/fixture-model.bin` installed it. Verify the SHA-256 against `ModelStore.SHA256`. Do not force-stop/reinstall concurrently with a fixture copy. This fixture is separate from the user's installed-model state; model binaries must not be committed.

The verified-model cancellation check is part of the core set. Full direct-buffer transcription is a separate `@LargeTest`, requiring a capable target and an explicit invocation:

```sh
adb -s SERIAL shell am instrument -r -w \
  -e class app.englishdub.OfflinePipelineTest#nativeWhisperReadsReusableDirectBuffer \
  app.englishdub.test/androidx.test.runner.AndroidJUnitRunner
```

## Automated coverage

- Subtitle timestamps, multiline SRT/WebVTT, markup, invalid input, ordering, overflow timing, and drift thresholds.
- Resampling and WAV conversion, including malformed audio.
- HTTP range resumption, range rejection, and partial downloads without validators.
- Time/byte gating, bounded cache eviction, thread policy, pending-start admission, stale task completion, preparation signatures, and scratch estimates.
- Speech-cache reuse across reopen, distinct voice/rate keys, incomplete PCM rejection, and retry after synthesis failure.
- Android core instrumentation: real version 1 SQLite migration; source descriptor reopening; AAC silence/speech timing through platform codecs; installed offline voice synthesis; complete English subtitle preparation; English-audio bypass with missing AI packs; verified-model cancellation; Android ICU markup parsing including Tamil/Urdu text.
- Playback instrumentation: video and dub clock advancement, ≤150 ms drift assertion, pause, seek and 1.25× speed. Recovery tests also exercise delayed local AAC loading, repeated seeks without another Play command, latest-seek recovery at 1.25×, original/dub switching, and trailing audio over the last video frame. They retain both transition/event rows and stable samples in `sync-buffering.csv`, `sync-controls.csv`, and `sync-overflow.csv`. The original test preserves a CSV of player positions/states in private `files/sync-samples.csv`, including failure paths. A player screenshot alone is not proof of successful decoding or synchronization.
- Separate full direct-buffer JNI transcription using the upstream JFK fixture when the model is supplied. Emulation may be prohibitively slow.

A skipped native model test is not a recognition pass. Emulated English smoke results do not establish Hindi/Urdu/Tamil quality.

## UI QA

Follow `android-emulator-qa`: resolve the activity, clear logs, launch, dump the UI tree, choose target bounds from that tree, tap their centers, then capture screenshots and logcat. Never infer tap coordinates from a screenshot. `scripts/android-qa.py` captures a focused library → offline setup → library flow this way; `--playback` additionally opens the generated fixture's player. Its memory/frame captures are observations, not numerical performance pass gates.

Test the following feature flows on a target with installed offline voice/model data:

1. Existing English audio returns immediately, even when AI packs are missing or free scratch space is low.
2. English SRT and WebVTT each produce audible, synchronized English speech.
3. Hindi, Urdu, and Tamil subtitle translation complete in airplane mode after pack setup.
4. Dialogue mode works without subtitles, including pause/resume and process interruption.
5. Source, subtitle, model, language, voice, or VAD changes invalidate only compatible stages.
6. Repeated lines reuse speech assets; interrupted files are not accepted as complete.
7. Seek, buffering, pause, speed, audio focus, headphone removal, backgrounding, and final speech overflow keep the two players coordinated.
8. Missing voice data, unsupported codecs, inaccessible documents, corrupt packs, storage exhaustion, thermal pressure, and Android service time limits expose recovery actions.
9. Large fonts, TalkBack, rotation, and a 320 dp screen remain usable.

## Performance procedure

Follow `android-performance`, using run-specific artifact directories outside the skill folder. Exercise one focused flow per capture.

- Startup/navigation: repeated `am start -W`, gfxinfo reset/capture, and screenshots/UI trees verifying a responsive foreground screen.
- CPU hotspots: Simpleperf on a debug/profileable build, if supported. Preserve raw samples and report whether symbols resolve.
- Main-thread and scheduler behavior: Perfetto with named capture boundaries. Report frame timelines only if present in the trace.
- Memory: dumpsys meminfo at a stable point after startup, preparation stages, and playback. One sample does not prove a leak or peak memory.
- Actual battery/thermal: compare baseline/candidate with the same film, resolution, volume, device state, and duration on physical budget/midrange phones.

Targets: complete APK ≤21 MB, playback <250 MB PSS, preparation <800 MB PSS, and stable drift within 150 ms. Report missing measurements as unverified. Software-emulator timings cannot substantiate phone speed, power consumption, or thermal improvements.

## Results

See `VALIDATION.md`, `docs/PERFORMANCE.md`, and `verification.json`. Preserve logs, test reports, screenshots, and profiler output with the tested revision. GitHub Actions builds artifacts and defines a separate Android 15 x86_64 platform-test job. That job runs seven tests covering subtitle parsing, audio bypass, migrations, source identity, AAC timing, and hardware preferences. It fails on skips or failures; offline TTS/model and dual-player acceptance still require a provisioned target. See [Mac validation](validation/MAC-2026-11-07.md) for Autonom sessions and [fixture provenance](TEST-FIXTURES.md) for the committed test WAV.

## Mac setup and Autonom

```sh
source scripts/mac-env.sh
autonom doctor
autonom devices
autonom session start --serial SERIAL --app-id app.englishdub
autonom session launch app.englishdub
autonom ui tree --format outline
autonom screenshot --task playback-sync --label library
# Run the instrumentation commands above with the same adb serial.
autonom note add "Record exact test pass/fail/skip counts here" --task playback-sync
autonom journal
autonom session stop
```

Install the official CLI separately following https://autonom.yatsuk.me/docs/install/;
the plugin contains skills, not the CLI. The configured Mac uses Python 3.13.17
and Autonom 0.36.1. No interception or proxy is needed for these offline tests.

On the handoff Mac, adb 37.0.1 intermittently aborted in host `libunwind`.
A separately downloaded official platform-tools 35.0.2 installation under
`~/.local/share/englishdub-tools/platform-tools-35.0.2/platform-tools` worked.
The helper selects that fallback when present; set `DUB_ADB_DIR` to override it.
This does not replace the SDK's platform-tools installation.
