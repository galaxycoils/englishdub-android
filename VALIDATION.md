# Validation — 0.3.0 experimental preview

The 0.3.0 release delivers track inspection & source recommendations, 60-second scene previews, searchable dialogue review/editing with spoken-only pronunciation overrides and preserved song ranges, a persistent reorderable queue with charging-only policy and storage cleanup, SRT/M4A exports, Room 2→3 migration, and reusable GZIP-compressed speech clips (`.pcm.gz`). It passes 44 JVM unit regressions and 15 Android instrumentation tests on the accelerated ARM64 Android 15 emulator. Language quality remains labeled **experimental** pending human bilingual review.

| Check | Current result |
| --- | --- |
| JVM regressions (including SQLite v1→v2→v3 migration & 36-clip evaluation check) | 44 passed; zero failures/errors |
| Android lint | 0 errors, 56 warnings |
| Android offline core, 0.3 preview/rebuild, and full native inference | 11 passed; zero failures/skips (9.082 s) |
| Strict sync and playback recovery | 4 passed; zero failures/skips (15.452 s) |
| Maximum sampled stable drift | 53 ms; limit remains 150 ms |
| Preview packaging (`app-preview.apk`, versionCode 4) | ARM64, 20,297,950 bytes, SHA-256 `154498c6698ae52aeb501a192100cfcf493e7396dfda9988f84ad1a69c2d5c96` |
| Unsigned release packaging (`app-release-unsigned.apk`, versionCode 4) | ARM64, 20,289,742 bytes, SHA-256 `9797b0fb693bff90b8f8f3f2d27be8fd0335d1c124dceda9c168cfc9aeac5418` |
| 36-clip Hindi/Urdu/Tamil evaluation set (`docs/evaluation/EVALUATION-SET-0.3.md`) | 36 redistributable clips (12 `hi`, 12 `ur`, 12 `ta` across 6 conditions) compared against 0.2.1; labeled experimental pending human bilingual review |
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
