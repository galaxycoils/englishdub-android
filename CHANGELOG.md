# Changelog

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
