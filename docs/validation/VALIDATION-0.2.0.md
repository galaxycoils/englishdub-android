# Validation — 0.2.0 preview

## Results

| Check | Result |
| --- | --- |
| Optimized ARM64 preview build | Pass; JDK 17, Gradle 8.11.1, SDK 36, NDK 27.2.12479018 |
| JVM regressions | **25 tests passed**, zero failures/errors |
| Android lint | **0 errors, 37 warnings** |
| Complete preview APK | **20,248,794 bytes** (20.25 MB decimal), excluding separate model/voice downloads |
| Packaging | APK Signature Scheme v2; 16 KiB ZIP/native ELF load alignment; ARM64 only |
| Update signer | Matches the original 0.1 preview; uses the existing local debug key |
| Android core instrumentation | **8 passed, 0 failed, 0 skipped**, final run 95.928 seconds |
| Isolated UI navigation | Passed library → offline setup → library and prepared-player opening; UI trees/screenshots retained |
| Playback synchronization | **Failed**: strict 150 ms assertion observed 886 ms on software emulator |
| Full Android base-model transcription | **Resource-limited / unverified**, not counted as a pass |
| Phone battery, peak memory, thermal behavior | **Unverified** |
| Hindi/Urdu/Tamil end-to-end quality | **Unverified**; bilingual review pending |

The preview is 380,392 bytes smaller than the 20,629,186-byte 0.1 artifact (about 1.84%). This is an artifact-size comparison, not a measured processing or battery improvement. Exact hashes and structured status are in `verification.json`.

## Android evidence

All Android QA/profiling followed the requested **Test Android Apps** skills. They supply adb procedures, not a hosted device service. A locally provisioned Android 8 / API 26 x86 emulator ran in software mode with 1,536 MiB configured RAM and no KVM. The tested debug build explicitly selected x86; the delivered APK remains optimized ARM64. A separately installed official eSpeak NG 1.52.0 engine supplied an offline English voice. Airplane mode was on, with Wi-Fi and cellular data disabled.

The eight core checks exercise actual platform/JNI code: AAC silence/speech timeline round trip; offline voice file synthesis; complete English subtitle → voice → AAC preparation through the foreground service; existing-English-audio bypass without AI packs; real Room version 1→2 migration preserving a saved entry; stable source identity across descriptor reopening and invalidation after a source change; Android ICU markup parsing with Tamil/Urdu text; and pre-aborted inference with the exact verified quantized base model followed by safe closure.

The last check is a cancellation regression, not successful recognition. Full JFK transcription was attempted using the base Q5_1 model, including optimized debug kernels, but exceeded the practical software-emulator capture budget. A prior host smoke test transcribed that English sample; it does not establish this Android full-inference path or any source-language accuracy.

The playback clock test is retained with its original 150 ms limit. It sampled up to 886 ms between video and dub clocks while both players repeatedly entered buffering. Its initial assertion failed, so later pause/seek/speed assertions were not reached. State CSV and logs are preserved. A shared-playback-thread experiment did not help and was removed. Physical-phone sync is still unverified; this failure is not dismissed as a pass or proof of a phone-specific result.

Setup also exposed an external Pico TTS native crash and an emulator platform H.264 decoder SIGILL. The QA target switched to the separately installed offline engine and a compatible emulated CPU before the final core checks. A concurrent host build caused a System UI ANR during one navigation capture; the dialog and failed capture are preserved, and navigation was repeated in isolation. These environment/engine events are distinct from the unresolved app playback-clock test.

## Supporting regressions

The JVM suite covers SRT/WebVTT parsing, markup, malformed input and timing overflow; WAV downmix/resampling; validated HTTP resumption and range rejection; time/byte gates, thread/polling policy, bounded translation cache, task admission and stale completion, signatures and scratch estimates; and actual speech-cache reuse across reopen, voice/rate invalidation, and recovery from incomplete PCM/failed synthesis.

Lint warnings concern hardcoded English text, available dependency updates and Android API/style/configuration recommendations. They are retained in the report. A clean lint error count does not establish device acceptance.

See [testing commands](../TESTING.md), [performance evidence](../PERFORMANCE.md) and the accompanying evidence ZIP for logs, reports, screenshots/UI trees, raw Simpleperf samples and state CSV. A screenshot showing a player is not a synchronization pass.

## Release gate

This remains an experimental preview. Before production acceptance, resolve the clock failure and verify it on budget ARM64 (3–4 GB) and midrange ARM64 (6–8 GB) phones. Run real Hindi, Urdu and Tamil subtitle/speech clips in airplane mode with bilingual review. Exercise movie-length preparation, cancellation during active inference, process death, source/voice changes, low storage, thermal/service limits, seek/buffering/speed changes, audio focus/headphone removal, final speech overflow, accessibility, rotation and multiple Android/OEM versions.

Measure preparation throughput, peak PSS, battery and temperature on those phones; no phone efficiency percentage is claimed. Keep VAD disabled until quality is established. Production distribution needs an independently managed signing key. GitHub publication is separately blocked by repository-creation permissions, as documented in [publishing](../PUBLISHING.md).
