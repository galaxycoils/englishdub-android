# Changelog

## 0.2.1 preview

- Preserve playback intent across delayed Media3 pause/resume callbacks after seeking or buffering.
- Add decoder-backed regressions for a stalled dub, repeated seeks, pause/resume, original/dub switching, and final speech overflow; retain the original 150 ms assertion.
- Include the generated JFK WAV explicitly in instrumentation assets so full native inference runs in fresh checkouts.
- Add a Mac environment helper, ARM64 Autonom evidence, and separate historical/current validation records.
- Fix GitHub Actions SDK installation after Google removed the obsolete `tools` package.
- Publish source and complete history to `galaxycoils/englishdub-android`.
- Version code 3; Mac preview uses a new local debug signer, without continuity with the old cloud signer.

## 0.2.0 preview

- Recycled library cards and independent progress updates.
- Event-driven playback synchronization with reduced stable polling and no paused polling.
- Bounded storage/progress checks; targeted database writes.
- Exclusive reusable native audio buffers and explicit UTF-8 segment decoding.
- Native scheduler cancellation propagation; Android-compatible subtitle markup parsing and a real offline-voice synthesis check.
- Recognition thread limits based on device RAM; earlier speech/translation resource release.
- Compatible preparation fingerprints, Room 1→2 migration, and repeated-line speech/translation caches.
- Optional, verified Silero VAD; experimental and disabled by default.
- Incomplete model-download removal, storage estimates, and task-admission ownership.
- Apache 2.0 licensing, contributor documentation, CI packaging, and expanded regression/device checks.

## 0.1.0 preview

Initial offline player, subtitle translation and speech, optional Whisper dialogue translation, direct downloads, resumable preparation, and saved English AAC playback.
