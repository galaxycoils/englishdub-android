# EnglishDub 0.3: Better English Dubbing for Hindi, Urdu, and Tamil Films

## Summary

Deliver **one combined release** that improves translation review, movie preparation, and everyday viewing.

The main flow becomes:

**Open movie → choose track and language → preview a scene → prepare English → review or correct dialogue → watch or export.**

Keep one native Android APK, offline processing, Android 8+ compatibility, and ARM64 distribution. Hindi, Urdu, and Tamil remain the supported source languages for this release.

## Plan refinements (0.3 engineering improvements)

1. **Lossless streaming GZIP speech clips (`.pcm.gz`) instead of tandem per-cue AAC re-encoding:** Completed speech clips in `files/speech-cache/` are compressed losslessly with `GZIPOutputStream` and decoded incrementally via `GZIPInputStream` during `AacTimeline.encode`. This avoids generational lossy transcoding (TTS → AAC → PCM → AAC), eliminates hardware `MediaCodec` instantiation overhead per cue during assembly, preserves exact PCM sample counts in `.complete` markers, and requires zero external dependencies.
2. **Single content-addressed 256 MiB LRU speech cache (`files/speech-cache`):** Preview scenes, full-film preparation, and post-edit rebuilds share one content-addressed directory keyed by `(spokenText, voiceId, rate, pipeline)`. Preview clips are reused automatically during full-film preparation without cross-directory file copying or index tables. LRU eviction protects active-job paths.
3. **Immutable source timestamps + pure effective projection:** `SavedCue.startMs`, `endMs`, `text`, and `english` remain immutable after extraction/translation. Effective timestamps (`effectiveStartMs`, `effectiveEndMs`), edited text (`effectiveEnglish`), spoken phonetics (`Pronunciation.apply`), and silence suppression (`subtitlesOnly` or `SongRangePolicy.overlapsPreserved`) are projected without mutating source cues, so changing offsets, pronunciations, or song ranges never invalidates recognition or translation.
4. **Flat Room 2→3 migration with additive defaults:** `jobs` and `cues` add `DEFAULT`-backed columns for track indices, subtitle offset, preview state, split stage signatures, queue order, charging-only policy, viewing preferences, and cue overrides, plus two cascade-deleted child tables (`pronunciations` and `preserved_ranges`). Version 1 and 2 databases upgrade in place without copying or deleting existing films or dubs.
5. **Single-Switch Player hierarchy for test & TalkBack stability:** `PlayerActivity` keeps a single `Switch` for the dub/original toggle and uses accessible `>=48dp` `WRAP_CONTENT` buttons for caption visibility/size cycling and dialogue replay, preventing large-font clipping and preserving instrumentation compatibility.

## User-facing improvements

### 1. Choose the right source

- List available audio and supported text-subtitle tracks, including language, track label, and an “Unknown language” fallback.
- Recommend existing English audio first, then English subtitles, translated subtitles, and finally speech recognition.
- Let viewers preview and override the recommendation. Never silently select another audio track.
- Add a subtitle timing offset in milliseconds, with earlier/later buttons and a preview. Preserve original timestamps and apply the offset separately.

### 2. Preview before processing a whole movie

- Let users select a starting position and prepare a 60-second scene, shortened at the movie’s end.
- Use the selected track, language, voice, and subtitle offset.
- Show English captions alongside the generated voice and allow immediate comparison with the original audio.
- Keep preview results separate from the full movie’s completion state. Reuse compatible translation and speech assets.
- Estimate full preparation time from measured progress, clearly marking it as an estimate.

### 3. Review and correct dialogue

- Add a searchable dialogue list with timestamps, English text, playback, and timing warnings.
- Show original subtitle text when available. Speech translation must not present its English output as an original-language transcript.
- Allow editing the English text, adjusting cue timing, and marking a cue “subtitles only.”
- Regenerate changed speech and recalculate affected downstream timing. Rebuild the final audio file without repeating speech recognition or unchanged translations.
- Keep the previous playable dub until its replacement is successfully committed.
- Add film-specific pronunciation replacements applied to spoken text only; captions retain their normal spelling.
- Retain the existing 1.25× speech-speed ceiling and complete-word preservation. Flag overflow rather than silently cutting dialogue.

### 4. Improve watching

- Offer installed offline English voices with a short sample before selection.
- Add caption visibility and text-size controls, remembered playback position, and “Replay last dialogue.”
- Persist voice choice, original-audio volume, captions, and playback position per film.
- Allow manually selected song ranges to preserve original audio and display available English captions without synthesized speech. Do not claim automatic lyric translation or song detection.
- Support screen readers, large system fonts, clear focus order, and accessible touch targets.

### 5. Prepare and manage a movie library

- Add a persistent, reorderable preparation queue, processing one film at a time.
- Default queued preparation to charging-only; allow an explicit override.
- Provide clear states for waiting, running, paused, missing files or packs, low storage, failure, and completion.
- Start processing from an explicit user action. Pause safely when unplugged under charging-only mode, overheated, interrupted, or stopped by Android.
- Restore queue and checkpoints after reopening or rebooting; require Resume when a new foreground session is necessary.
- Continue eligible queued films within the active processing session. Do not promise unlimited overnight execution: Android imposes foreground-service time limits. [Android documentation](https://developer.android.com/develop/background-work/services/fgs/timeout)
- Add storage accounting and separate actions to remove previews, reusable speech clips, completed dubs, or app-owned downloads. Keep user-owned originals intact.

### 6. Export useful results

- Export English subtitles as UTF-8 SRT and completed English speech as M4A through Android’s document picker.
- Export corrected text and effective subtitle timestamps; identify the M4A as the generated voice track, without the original soundtrack.
- Make cancellation and write failures recoverable without damaging the prepared film.
- Exclude video remuxing and shared-project import from this release.

## Implementation and compatibility

- Separate media inspection, preparation, speech generation, and final audio assembly from the service lifecycle. Keep one coordinator responsible for exclusive processing.
- Introduce typed internal requests for selected tracks, full-film versus preview ranges, voice, and subtitle offset. No external API or account system is needed.
- Migrate Room database version 2 to version 3 without deleting existing films or dubs. Persist track selections, editable cue overrides, pronunciation entries, preserved-audio ranges, queue state, and viewing preferences.
- Split cache identities by stage: source recognition, translation, speech generation, and final timeline. Changing captions or playback preferences must not invalidate inference.
- Store completed speech clips as compressed, reusable assets, decoding them incrementally during final assembly. Key assets by spoken text, voice identity, rate, and pipeline version.
- Cap reusable speech storage at 256 MiB by default using least-recently-used eviction. Protect active-job assets; if evicted clips are needed again, regenerate their speech without retranslating.
- Keep assembly memory bounded by buffers rather than movie length. Interrupted final assembly may restart, but completed recognition, translation, and available speech assets remain reusable.
- Preserve legacy completed dubs. On their first edit, explain that missing reusable speech clips must be regenerated.
- Preserve atomic output replacement, source-change detection, explicit model downloads, thermal safeguards, and offline operation.

## Validation and acceptance

**Language quality**

- Build a documented, redistributable evaluation set with at least 12 short clips per language, covering clear speech, quiet dialogue, accents, music, overlapping speakers, and English code-switching (`docs/evaluation/EVALUATION-SET-0.3.md` and `docs/evaluation/evaluation-manifest-0.3.json`).
- Compare against 0.2.1 using the same inputs and model. Record missing meaning, incorrect names, invented speech, and timing overflow.
- Keep the current recognition model for this release; do not bundle a larger model without separate quality and phone-performance evidence.
- Require bilingual review before claiming improved translation accuracy. Until then, label language quality experimental.

**Functional and recovery tests**

- Test multiple and unlabeled audio tracks, explicit overrides, preview boundaries, subtitle offsets, line edits, pronunciation changes, song ranges, and unavailable voices.
- Verify unchanged recognition and translation are reused after edits, and failed regeneration preserves the previous playable dub.
- Test migrations from database versions 1 and 2, revoked file access, process death, reboot recovery, unplugging, low storage, thermal pauses, and service timeouts.
- Test export cancellation, write failures, and recovery without damaging the prepared film.
