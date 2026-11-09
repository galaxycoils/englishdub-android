# Validation — 0.3.0 experimental preview

Current optimization changes (2026-11-09) pass 52 JVM tests, lint with zero errors / 67 warnings, debug/test packaging, and unsigned release packaging. No target was connected for this change, so the Android runtime results and APK hashes below describe the earlier build. See [optimization validation](docs/validation/OPTIMIZATION-2026-11-09.md). The 36-case evaluation is a synthetic text/pipeline regression, not measured language accuracy; see the corrected [evidence boundary](docs/evaluation/EVALUATION-SET-0.3.md#evidence-boundary).

The 0.3.0 release delivers track inspection & source recommendations, 60-second scene previews, searchable dialogue review/editing with spoken-only pronunciation overrides and preserved song ranges, a persistent reorderable queue with charging-only policy and storage cleanup, SRT/M4A exports, Room 2→3 migration, reusable GZIP-compressed speech clips (`.pcm.gz`), and smart hardware auto-detection (`HardwareProfile`). It passes 50 JVM unit regressions and 16 Android instrumentation tests on the accelerated ARM64 Android 15 emulator. Language quality remains labeled **experimental** pending human bilingual review.

| Check | Current result |
| --- | --- |
| JVM regressions (including SQLite v1→v2→v3 migration & 36-clip evaluation check) | 50 passed; zero failures/errors |
| Android lint | 0 errors, 56 warnings |
| Android offline core, 0.3 preview/rebuild, hardware auto-setup, and full native inference | 12 passed; zero failures/skips (10.024 s) |
| Strict sync and playback recovery | 4 passed; zero failures/skips (15.452 s) |
| Maximum sampled stable drift | 53 ms; limit remains 150 ms |
| Preview packaging (`app-preview.apk`, versionCode 4) | ARM64, 20,314,534 bytes, SHA-256 `7d6860373565673e4e6ee2cac8adfa00352be5fd0c14255cd013070510e8b720` |
| Unsigned release packaging (`app-release-unsigned.apk`, versionCode 4) | ARM64, 20,306,326 bytes, SHA-256 `fa18ecdcb61e61229780ff7197ca8df6a8012d509266dae40701e26a33d2f036` |
| 36-clip Hindi/Urdu/Tamil evaluation set (`docs/evaluation/EVALUATION-SET-0.3.md`) | 36 text fixture scenarios (12 per language across 6 conditions); reference translations and synthetic audio, not a measured historical-build or language-quality comparison |
| Physical-phone efficiency and synchronization | Unverified |
| Hindi/Urdu/Tamil human bilingual sign-off | Pending (`bilingualReviewed: false` in `docs/evaluation/evaluation-manifest-0.3.json`) |

See the [full Mac report](docs/validation/MAC-2026-11-07.md) and [0.3 evaluation set](docs/evaluation/EVALUATION-SET-0.3.md) for the target, raw sample counts, before/after reproduction, fixture repair, test durations, and remaining acceptance work. Exact artifact hashes are in `verification.json`.

The historical API 26 software x86 sync failure (886 ms) remains in
[0.2.0 validation](docs/validation/VALIDATION-0.2.0.md). The accelerated baseline
also passes the original strict test, so that environment-specific failure is
not claimed resolved solely by the new emulator result. The added recovery
suite independently demonstrates the seek-resume hang before the fix and passes
after it.

The Mac preview uses a new local debug key. It cannot update the original
cloud-signed preview in place. Preserve user data; production signing remains
separate. See [publishing](docs/PUBLISHING.md).
