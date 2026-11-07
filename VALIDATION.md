# Validation — 0.2.1 preview

The Mac continuation fixes a reproduced seek-resume callback race and passes the
unchanged 150 ms playback test plus three decoder-backed recovery tests on an
accelerated ARM64 Android emulator. It remains an experimental preview.

| Check | Current result |
| --- | --- |
| JVM regressions | 25 passed; zero failures/errors |
| Android lint | 0 errors, 37 warnings |
| Android offline core and full native inference | 9 passed; zero failures/skips |
| Strict sync and playback recovery | 4 passed; zero failures/skips |
| Maximum sampled stable drift | 53 ms; limit remains 150 ms |
| Preview packaging | ARM64, 20,248,794 bytes, v2 signature, 16 KiB ZIP/native load alignment |
| UI navigation | Library → offline setup → library → prepared player captured with Autonom |
| Physical-phone efficiency and synchronization | Unverified |
| Hindi/Urdu/Tamil end-to-end quality | Unverified; representative clips and bilingual review pending |

See the [full Mac report](docs/validation/MAC-2026-11-07.md) for the target, raw
sample counts, before/after reproduction, fixture repair, test durations,
Autonom sessions and remaining acceptance work. Exact artifact hashes are in
`verification.json` and the release checksums.

The historical API 26 software x86 sync failure (886 ms) remains in
[0.2.0 validation](docs/validation/VALIDATION-0.2.0.md). The accelerated baseline
also passes the original strict test, so that environment-specific failure is
not claimed resolved solely by the new emulator result. The added recovery
suite independently demonstrates the seek-resume hang before the fix and passes
after it.

The Mac preview uses a new local debug key. It cannot update the original
cloud-signed preview in place. Preserve user data; production signing remains
separate. See [publishing](docs/PUBLISHING.md).
