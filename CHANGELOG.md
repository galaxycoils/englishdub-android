# Changelog

## 0.3.0 experimental preview

- Add audio and text-subtitle track inspection with language normalization, track labels, "Unknown language" fallback, priority recommendations (English audio → English subtitles → translated subtitles → speech recognition), explicit overrides, and millisecond subtitle timing offsets.
- Add 60-second scene previews (`PreviewRange`) shortened at movie end, separate from full-film completion state, reusing translation and speech assets, with full-film preparation time estimates marked as `(estimate)`.
- Add searchable dialogue review and correction: edit English text, adjust cue timing, mark cues "subtitles only," jump to cue playback, and rebuild dubs without repeating speech recognition or unchanged translations while keeping the previous playable dub until atomic commit.
- Prevent speech translation (`mode == "speech"`) from presenting its English output as an original-language transcript (`transcriptAvailable = false`).
- Add film-specific pronunciation replacements (`PronunciationEntry`) applied only to spoken audio while captions and SRT export retain normal spelling.
- Add offline English voice selection with short sample playback, caption visibility/text-size controls, per-film remembered playback position and preferences, "Replay last dialogue," and manual song ranges (`PreservedRange`) that preserve original audio without synthesized speech.
- Add a persistent, reorderable preparation queue with charging-only default, power-disconnect pause handling, active-session continuation, and granular storage accounting/cleanup for previews, reusable speech clips, completed dubs, and app-owned downloads.
- Add UTF-8 SRT and English voice-track M4A exports through Android's document picker with recoverable cancellation and write-failure handling.
- Migrate Room schema 2→3 (`MIGRATION_2_3`), split stage cache signatures (`recognition`, `translation`, `speech`, `timeline`), and store reusable compressed speech clips (`.pcm.gz`) with incremental decoding and 256 MiB LRU eviction protecting active-job assets.
- Add the documented 36-clip Hindi/Urdu/Tamil evaluation set (`docs/evaluation/EVALUATION-SET-0.3.md`); keep language quality labeled experimental pending human bilingual review.

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
