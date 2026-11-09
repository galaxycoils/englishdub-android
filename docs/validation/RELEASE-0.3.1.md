# Release validation — 0.3.1 preview (2026-11-09)

Runtime source: `1ec90899374985cac94ebaa91f0b6e2a43ece2d8`. Version code 5, Android 8+ ARM64, same Mac preview certificate as 0.3.0.

All local checks passed: 52 JVM tests (zero failures/errors/skips), lint (zero errors; 67 warnings), debug application and instrumentation packaging, signed preview packaging, and unsigned release packaging. APK signature v2 and 16 KiB ZIP alignment verified. The preview uses a local debug key, not a production release key.

`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assemblePreview :app:assembleRelease --console=plain`

No local Android target was connected. Previous emulator acceptance belongs to the earlier build preserved in `verification-0.3.0.json`. The GitHub Actions platform job runs seven dependency-independent Android tests; consult that run for its result. Physical-phone efficiency, full-player synchronization on this build, actual process-death/Room integration, and human bilingual review remain unverified.

The full source archive includes the Gradle project, vendored native source, tests, and documentation. Model weights, optional voices, signing keys, machine-local configuration, and private videos are not distributed. Download the optional packs and an offline English voice in the app before offline use.

Artifact hashes and byte sizes are recorded in the root `verification.json` and release `SHA256SUMS.txt`. See [optimization validation](OPTIMIZATION-2026-11-09.md) for the regression evidence and implementation scope. Synthetic language fixtures do not establish language accuracy improvements.
