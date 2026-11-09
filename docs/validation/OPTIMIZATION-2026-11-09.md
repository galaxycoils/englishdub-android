# Optimization validation — 2026-11-09

Source: working-tree changes based on `38fbceb`, macOS ARM64, JDK 17.0.21. No connected adb target. This report does not supersede earlier runtime evidence with a new device pass.

## Changes

- Incremental speech-cache size/LRU accounting with one directory scan per instance, no growing protected-set copies on cache hits, and trimming after active work ends.
- Append recognition checkpoints in place rather than copying the entire accumulated cue list each window.
- Keep native and displayed recognition tuning at one/two threads, including previously saved four-thread overrides. Report hardware AAC availability only when an AAC hardware encoder is advertised.
- Keep experimental VAD downloads explicit and preserve the user's opt-in setting during auto-setup. Translation-pack errors and cancellation propagate; readiness is checked before recording successful setup.
- Encode full-film replacements to distinct output versions, then publish matching cues and output reference in one Room transaction. Failed publication deletes only the candidate. Startup recovery and storage cleanup recognize legacy and versioned outputs.
- Define seven dependency-independent Android platform regressions in CI. Workflow YAML parsing and embedded Bash syntax validation passed locally; the hosted emulator job has not run here.
- Correct the language-evaluation evidence boundary: text scenarios/reference translations/synthetic audio are pipeline tests, not recorded language-quality measurements.

## Local checks

`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease`

Passed. 52 JVM tests; zero failures, errors, or skips. Lint: zero errors, 67 warnings. Debug, instrumentation, and unsigned release APKs built.

`./gradlew :app:testDebugUnitTest :app:assemblePreview`

Passed. 52 JVM tests; zero failures, errors, or skips. Installable preview built with the local preview/debug signer.

New regressions cover 100 generated and 100 warm-cache cue requests with exactly one directory enumeration, protected assets surviving an over-budget pass and trimming afterward, publication failure preserving previous audio, orphan cleanup retaining the referenced output and source video, and versioned-output storage accounting/deletion. Android hardware preference assertions were updated and compiled, but not executed locally.

Raw Gradle logs, JVM XML, lint XML, and artifact metadata are retained locally in `qa-artifacts/optimization-2026-11-09/` (ignored by Git).

## Built artifacts

| Variant | Bytes | SHA-256 |
| --- | ---: | --- |
| debug | 26,874,616 | `9af37a809db451dc5b8e87abfff1a37bd9b3ad09d21a294b2f7dcf64621fefdf` |
| preview | 20,314,534 | `92436aef7a5e8af706a58b031ab63e41e59008f9177d0e2e65a37faf6b0c326f` |
| release | 20,306,326 | `2ce2c4bc0f4cc1026365a9ece2fa4cb4592b2cab8075464421199d7b2dc49733` |

No APK was installed or published. Physical ARM64 timing, peak memory, battery/thermal behavior, actual process-death/Room integration, full-player synchronization, and Hindi/Urdu/Tamil bilingual review still need provisioned targets. These implementation reductions do not establish a measured phone speedup or power saving.
