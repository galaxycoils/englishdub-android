# Changelog

## 1.0.0

- Add Picture-in-Picture (PiP) multitasking support in `PlayerActivity` with automatic transition on home gesture/leaving app and sleek overlay hiding in mini-window mode.
- Add player double-tap gesture controls to seek ±10 seconds with animated on-screen HUD pill ("« 10s" / "10s »").
- Add live in-player subtitle timing synchronization dialog with one-tap offset presets (`−250 ms`, `Reset (0 ms)`, `+250 ms`) that realigns speech and captions on the fly and saves adjustments to the database.
- Optimize cue lookups to O(log N) binary search (`Subtitles.findCueAt` and `Subtitles.findSpokenCueAt`), eliminating linear scans during 10 Hz playback clock polling.
- Optimize timeline audio I/O with buffered stream reads (`BufferedInputStream` 32 KiB chunking before `GZIPInputStream`) and selective silence zero-filling in `AacTimeline`, minimizing syscall overhead during full-film dub assembly.
- Add friendly first-run onboarding guide in `LibraryAdapter` with a one-tap "▶ Try 10-Second Sample Demo" that self-synthesizes an offline dub demo without requiring user video files.
- Streamline "Choose source & prepare English" dialog with Hero recommendation banner, direct English voice selection & sample listening, and collapsible Advanced Settings accordion with offset adjustment buttons.
- Upgrade dialogue review and cue editing with filter chips (`All`, `⚠ Warnings`, `Subtitles only`), exact timing overflow metrics (`+XX ms overflow`), and "🔊 Audition line" button that speaks edited dialogue via the offline synthesizer.
- Add one-tap "Free temporary cache space" button in Storage settings to clear previews and LRU speech clips without touching completed movie dubs.

## 0.3.1 experimental preview

- Index speech-cache sizes and LRU order once per stage; trim after active work ends and avoid repeated protected-set and checkpoint-list copies.
- Publish new full-film audio versions with matching Room metadata, preserving the previous output until publication succeeds and cleaning orphaned versions during recovery.
- Preserve explicit VAD opt-in, keep detector downloads explicit, and propagate translation-pack failures before marking setup complete.
- Match displayed and saved recognition-thread settings to the native one/two-thread limit; report AAC hardware support independently of video acceleration.
- Add platform regression execution in GitHub Actions and correct the synthetic language-evaluation evidence boundary.
- Version code 5; uses the same local Mac preview signer. Physical-phone efficiency, synchronization, and bilingual review remain pending.

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
- Add smart hardware auto-detection and setup (`HardwareProfile`): inspect RAM, CPU cores, storage headroom, and hardware AAC codecs to classify phone tier (Budget, Balanced, High Performance), tune inference threads and speech cache limits, and auto-download verified offline packs with one tap.
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
