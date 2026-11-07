# Performance evidence and limits

EnglishDub 0.2 reduces avoidable work. These changes do not establish a measured battery improvement on phones.

## Implemented resource controls

| Area | Policy |
| --- | --- |
| Library | RecyclerView and DiffUtil reuse cards; progress does not recreate the screen |
| Progress | Approximately one update per second; stage changes and durable cue/recognition checkpoints remain immediate |
| Storage | Free-space checks every second or 4 MiB of output, plus preflight and final checks |
| Recognition | One reused direct 30-second PCM window; no extra JNI audio vector |
| Threads | One inference thread at ≤4 GiB total device RAM, otherwise two |
| Lifetime | Recognition closes before voice generation; translation and TTS close before encoding |
| Repeated text | Translation LRU capped at 128 entries; completed job-local PCM shared by text/voice/rate |
| Playback | No inference; event-driven coordination with 100 ms settling checks and 250 ms stable checks; no paused/background polling |
| Long tasks | Single admitted task, bounded wake lock/session, thermal pause and resumable checkpoints |

The base model itself is still substantial. Loading a smaller quantized model does not make speech recognition inexpensive. Subtitle preparation is the preferred low-resource path. VAD remains optional and off until quality is established.

## Captured environment

Android QA followed the Test Android Apps `android-emulator-qa` and `android-performance` skills: adb UI trees, screenshots, logcat, instrumentation, memory snapshots and Simpleperf. The target was Android 8 / API 26 x86 with approximately 1.5 GiB emulated RAM, software CPU emulation, and no KVM. It is not a budget-phone benchmark. The distribution APK is ARM64; the emulator used an explicitly selected x86 debug build.

The initial CPU trace used an unoptimized native debug library. A 15-second frame-pointer Simpleperf capture collected 2,894 samples, zero lost, and 14.47 CPU seconds. Top self costs included `ggml_vec_dot_f16` (20.91%), `__memcpy_chk` (13.96%) and `memcpy` (10.40%). This supports investigating model math and copies, but those percentages cannot describe the optimized release or an ARM64 phone. Native debug builds now also compile kernels with `-O2`; the distribution already used optimized native compilation.

During that initial recognition attempt, meminfo snapshots showed total PSS of 130,621 and 135,127 KiB. These are two sampled points, not a measured peak, leak assessment, or proof of the <800 MB target. API 26 did not provide Perfetto; the bundled Simpleperf required PID targeting and frame-pointer unwinding.

The final isolated navigation capture sampled 30,248 KiB PSS in the library, 34,925 KiB after returning from setup, and 44,999 KiB after opening the prepared player. These are single debug emulator observations. The player capture includes buffering and does not establish stable playback memory or a phone result. The associated UI trees and screenshots confirm the requested pages opened; the final crash buffer was empty.

## Acceptance gates

| Target | Evidence/status |
| --- | --- |
| Complete APK ≤21 MB | Measure the final signed preview; exact bytes are recorded in `verification.json` |
| Playback <250 MB PSS | Physical ARM64 measurement pending; emulator snapshots are diagnostic only |
| Preparation <800 MB PSS | Physical ARM64 stage/peak measurement pending |
| Stable clock drift ≤150 ms | Automated playback clock assertion retained; see `VALIDATION.md` for the current result |
| Real battery/thermal improvement | Unmeasured; no percentage or runtime claim |
| Hindi/Urdu/Tamil quality | Representative clips and bilingual review pending |

Full base-model Android transcription exceeded the practical four-minute software-emulator capture budget, even with optimized debug kernels. It is unverified, not a pass. A verified-model pre-abort regression does pass and confirms clean error/close handling; it does not substitute for a full transcription run.

## Reproduce before making a claim

Use an ARM64 budget phone (3–4 GB RAM) and a midrange phone (6–8 GB), the same local media/voice, airplane mode after setup, fixed brightness/volume, and comparable battery/temperature. Record the commit, APK hash, build, device/API, model hashes, duration and run count. Capture library idle, recognition, synthesis, encoding and actual playback separately. Run at least three comparable baseline/candidate measurements; retain raw traces and report variability. Test final speech overflow, seek/speed changes and interruption independently of throughput.

See [testing](TESTING.md) for commands and [validation](../VALIDATION.md) for pass/fail/blocked results. No result here establishes compatibility with every movie, codec or streaming service.
