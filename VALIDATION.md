# Validation

## Automated results

- Debug and optimized preview APK builds succeed with Gradle 8.11.1, JDK 17, Android platform 36, and NDK 27.2.12479018.
- **14 unit tests pass**: SRT/WebVTT parsing, malformed input, overflow timing, clock drift thresholds, streaming resampling, PCM WAV conversion, validated HTTP resumption, server range rejection, and unvalidated partial-file recovery.
- Android lint: **0 errors, 30 warnings**. Remaining warnings concern English UI strings, dependency update suggestions, ARM64-only distribution, KAPT, backup configuration, storage preflight estimates, and style/API suggestions.
- Optimized preview APK: approximately **20.6 MB**, excluding separately downloaded voice/model data. Android APK Signature Scheme v2 and 16 KB ZIP alignment checks pass.
- Vendored whisper.cpp v1.7.6 builds for ARM64 Android and for the development host. The actual quantized model's SHA-256 matches the application's expected hash. A host smoke test in translate-to-English mode correctly processes the upstream 11-second English JFK sample with two threads. This does **not** establish Hindi/Urdu/Tamil accuracy or phone performance.
- `adb devices -l` found no attached Android devices; `/dev/kvm` is unavailable. No on-device or emulator playback results are claimed.

## Device acceptance

Test on a budget ARM64 phone with 3–4 GB RAM and a midrange phone with 6–8 GB RAM. The development environment has no attached Android device or KVM acceleration; hardware codec behavior, installed TTS engines, real battery use, language quality, and end-to-end playback need device verification.

- Existing English audio is preferred, with no model or generated dub required.
- An English SRT and a WebVTT file each produce an audible English dub.
- Hindi, Urdu, and Tamil subtitle packs translate without internet after installation.
- Speech mode recognizes and translates representative clips in all three languages; bilingual reviewers assess accuracy before a supported-language claim.
- No speech synthesis or translation network dependency appears in airplane mode after voice/model setup.
- Movie-length preparation survives pause, process termination, and service timeout. Resume reuses completed segments and cue audio. Failed downloads resume only with a server validator; otherwise they restart.
- Repeated seek, pause, speed changes, buffering, headphone removal, audio-focus loss, and leaving/reopening the player keep both tracks coordinated.
- Crowded dialogue produces timing warnings without overlapping or cutting speech. Final dialogue may continue over the last video frame if its audio extends beyond the video.
- Invalid subtitles, unsupported audio codecs, revoked file access, missing voice data, corrupt model files, and full storage produce useful errors.
- Measure drift, wall-clock preparation time, peak PSS, thermal behavior, and battery use. Use `scripts/profile-device.sh` for memory and battery snapshots; use externally recorded markers for sync measurement.
- Check TalkBack, large font sizes, rotation, a 320 dp wide screen, and Android 8/10/15/16 behavior.

## Release gate

Do not label the preview production-ready or claim the memory/battery/language targets until the above device checks pass. Publish only with an independently managed production signing key.
