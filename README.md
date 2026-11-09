# EnglishDub

A native Android player that prepares an English voice track on your phone, then reuses it while you watch. Designed for low resource use on budget and midrange phones.

**Status: 0.3.1 experimental preview.** Hindi, Urdu, and Tamil are the initial translation paths. Language quality, battery use, and phone memory targets require device validation. This is an offline preparation app: speech recognition may take longer than the film on a slow phone.

The strict 150 ms playback clock test, buffering/seek recovery tests, 0.3 scene preview and dialogue-edit rebuild regressions, Room 1→2→3 migrations, and the 36-case text-fixture regression suite pass on the accelerated ARM64 emulator. Physical-phone synchronization is still unverified; the older software x86 failure is retained in the evidence. See [validation](VALIDATION.md) and the [evaluation fixtures and their limits](docs/evaluation/EVALUATION-SET-0.3.md) before treating this preview as ready for regular viewing.

## Choose the lightest path

| What your video has | What EnglishDub does | Extra setup |
| --- | --- | --- |
| English audio | Prefer that track; skip AI preparation | None |
| English SRT/WebVTT subtitles | Generate one neutral English voice | Offline Android English voice |
| Hindi, Urdu, or Tamil text subtitles | Translate on-device, then generate English speech | Voice and the corresponding translation pack |
| Dialogue without subtitles | Translate speech with quantized Whisper, then generate English speech | Voice and the optional 57 MiB recognition pack |

One complete APK includes all four paths. Videos and dialogue are not uploaded for inference. Internet is needed for explicit video/model downloads and initial voice installation; after setup, local preparation and playback work offline.

## Install and use

1. Install the preview APK on an **ARM64 phone running Android 8 or later**.
2. Open **Offline setup → Check offline English voice**. Listen to a short voice sample and select your preferred installed offline English voice.
3. Download only the packs you need. Translation packs wait for Wi-Fi. The app asks before downloading the larger speech pack using your current connection.
4. Open a local video using Android's file picker, or download a direct HTTP/HTTPS media link.
5. Select **Prepare / Preview**: EnglishDub inspects audio and text-subtitle tracks, recommends the lightest path, and lets you override tracks, pick a voice, or adjust subtitle timing offsets (`±250 ms` or custom milliseconds).
6. Select **Preview 60s Scene** to test a 60-second window and inspect the full-film preparation time `(estimate)`, or select **Prepare Full Film** / **Queue (Charging Only)** to process one film at a time.
7. Select **Review / Edit Dialogue** to search cues, edit English phrasing, adjust cue start/end times, mark cues "subtitles only," configure film-specific spoken pronunciations, or preserve original audio during manual song ranges, then **Rebuild Dub Audio** without repeating recognition or unchanged translations.
8. Select **Watch in English** (or **Watch 60s Preview**). Control background audio volume, cycle caption visibility and text size, use **Replay last dialogue**, or **Export SRT / M4A** via Android's document picker.

Keep the app foregrounded when starting tasks. The notification exposes Pause, and Android may limit the duration of background processing. A bounded wake lock allows explicitly requested preparation to continue while the screen is off; it is released when the task ends or pauses.

### Optional speech detection

The 0.9 MB Silero speech detector can help skip non-dialogue sections. It is **experimental and disabled by default**. It must be downloaded and explicitly enabled in Offline setup. Quiet dialogue and music-heavy scenes require language-quality testing before this setting becomes a default. Missing detector data automatically leaves the normal recognition path available.

## Scope and limitations

- Local files and direct downloadable media URLs. Protected streaming-service playback is outside this app's scope.
- One English voice at a time, with original audio lowered during speech (and restored to full volume during `subtitlesOnly` cues and manual song ranges). Actor voice cloning, automatic lyric translation, and dialogue/music stem separation are not included.
- English, Hindi, Urdu, and Tamil subtitle paths; Hindi, Urdu, and Tamil dialogue options. Small-model accuracy varies with accents, language, music, overlapping speakers, and recording quality; language quality remains labeled **experimental** pending human bilingual review.
- UTF-8 SRT/WebVTT and supported embedded text subtitles. Image subtitles, ASS styling, video remuxing, and full support for every container are not promised.
- Hardware-supported codecs are preferred. Actual supported video and audio formats depend on the device.
- Long translated lines are sped up by at most 1.25×, then queued without overlapping or cutting words. Timing warnings appear in Dialogue Review and before playback. Final speech can finish over the last video frame.
- A document provider that cannot supply a reliable seekable source identity forces preparation-cache invalidation. Ordinary local files are the most reliable input.

## What's new in 0.3.1

- Incremental speech-cache accounting removes directory scans on each cue; recognition appends checkpoints without copying the entire cue list.
- Full-film dub replacements use separate output versions and commit the matching cue metadata and file reference together, preserving the previous audio on failure.
- Auto-setup preserves explicit speech-detector preferences, propagates missing-pack failures, and reports the native one/two-thread limit accurately.
- Hardware AAC availability is reported independently of video decoding support. The evaluation documentation distinguishes synthetic pipeline fixtures from measured language quality.

## What's new in 0.3

- **Track inspection & source recommendations:** Enumerates audio and text-subtitle tracks with language normalization and `"Unknown language"` fallback, recommends existing English audio → English subtitles → translated subtitles → speech recognition, honors explicit overrides, and applies non-destructive subtitle timing offsets.
- **60-second scene previews:** Prepares a 60-second window (clamped at movie end) separate from full-film completion, reuses translation and speech assets, and computes full-film preparation time marked as `(estimate)`.
- **Searchable dialogue review & fast rebuilds:** Edit English text, adjust cue timing, mark cues "subtitles only," add spoken-only pronunciation overrides (`PronunciationEntry`), and define manual song ranges (`PreservedRange`). Rebuilds regenerate only changed speech clips and commit atomically while preserving the previous playable dub on failure.
- **Stage-split caches & compressed `.pcm.gz` speech cache:** Splits cache identities into `recognition`, `translation`, `speech`, and `timeline`. Stores reusable speech clips as lossless `.pcm.gz` streams capped at 256 MiB LRU while protecting active jobs.
- **Viewing & library management:** Offline voice picker with sample playback, caption visibility/size controls, remembered playback position, "Replay last dialogue," persistent reorderable queue with charging-only default, granular storage cleanup, and recoverable UTF-8 SRT / voice-only M4A exports.

## What's optimized in 0.2

- Recycled library rows replace full-screen reconstruction on progress updates.
- Progress display/database updates are coalesced to about 1 Hz; actual recognition/cue checkpoints remain immediate.
- Stable dubbed playback checks clocks at 4 Hz rather than 10 Hz, with a short 10 Hz settling interval after startup, resume, seek, and speed changes. Paused playback has no periodic synchronization loop; playback events trigger immediate updates.
- Storage preflight checks are bounded by elapsed time or output volume rather than running for every encoder buffer.
- A reusable direct PCM buffer crosses JNI without allocating another native audio vector for every window.
- Recognition uses one thread on phones with at most 4 GiB total RAM and two on larger phones; severe thermal status pauses work.
- Recognition, translation, and voice resources close as soon as their stages finish. Playback runs no recognition, translation, or synthesis.
- Repeated lines share a bounded translation cache and reusable speech cache keyed by spoken text, voice, and speech rate.
- Source/subtitle/model/voice fingerprints invalidate stale work. Version 1 and 2 libraries migrate to Room schema version 3 without deleting saved videos or dubs.

These are implementation properties, not measured battery-saving percentages. See [performance evidence](docs/PERFORMANCE.md) and [validation](VALIDATION.md).

## Models and storage

| Component | Download | Storage behavior |
| --- | --- | --- |
| Complete ARM64 APK | Approximately 20.3 MB; exact release size is reported with artifacts | Includes native translation/runtime code |
| Hindi/Urdu/Tamil translation | Approximately 30 MB per installed language | Individually removable |
| Whisper multilingual base Q5_1 | 59,707,625 bytes / 57 MiB | Optional, verified before use |
| Silero VAD 5.1.2 | 885,098 bytes | Optional, verified before use |
| English voice | Depends on the installed Android TTS engine | Managed in Android voice settings |
| Reusable speech cache (`.pcm.gz`) | Capped at 256 MiB LRU (`files/speech-cache`) | Shared across previews, full films, and edits; clearable from Storage & Cleanup |
| Saved dub | Approximately 22 MB/hour at the configured 48 kbps AAC rate | Reused until the dub or library item is removed |

Preparation also needs temporary speech files. The app estimates remaining space and checks periodically while writing. Temporary audio is removed after a successful final commit. Library and granular storage actions delete only app-owned downloads, previews, cached speech clips, or generated dubs; they keep your original local video. Incomplete speech-model downloads can be removed from Offline setup.

## Build from source

Requirements: JDK 17, SDK platform 36, Build Tools 35, NDK `27.2.12479018`, and CMake `3.22.1`. On the configured handoff Mac, run `source scripts/mac-env.sh` first. Set `ANDROID_HOME` or an untracked `local.properties` containing `sdk.dir=...`. The Gradle 8.11.1 wrapper includes the official distribution checksum.

```sh
./gradlew :app:assemblePreview
```

Output: `app/build/outputs/apk/preview/app-preview.apk`. The preview ID is `app.englishdub.preview`; its version code is 5 (`0.3.1-preview`). The Mac preview is signed with this Mac’s local debug key. The original cloud preview key was not transferred, so this APK cannot update the old cloud-signed 0.2.0 preview in place. It can update 0.2.1+ previews signed on this Mac. Keep existing app data until you have exported anything you need; do not uninstall merely to resolve a signer mismatch. Never use this key for production publication.

```sh
./gradlew :app:assembleRelease
```

This creates an **unsigned** production APK (`app/build/outputs/apk/release/app-release-unsigned.apk`). Configure your independently managed release signer outside Git. No signing keys, credentials, model weights, or user videos belong in the repository.

For an x86 emulator only:

```sh
./gradlew -Pdub.abi=x86 :app:assembleDebug :app:assembleDebugAndroidTest
```

The default distribution stays ARM64. See [testing](docs/TESTING.md) for the Test Android Apps workflow and [contributing](CONTRIBUTING.md) for development checks.

## Documentation

- [Release 0.3 plan](PLAN.md)
- [Architecture and data flow](docs/ARCHITECTURE.md)
- [0.3 Evaluation set (Hindi/Urdu/Tamil)](docs/evaluation/EVALUATION-SET-0.3.md)
- [Testing and device acceptance](docs/TESTING.md)
- [Performance measurements and targets](docs/PERFORMANCE.md)
- [Validation status](VALIDATION.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Roadmap](ROADMAP.md) and [changelog](CHANGELOG.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## License

Original EnglishDub code is licensed under [Apache License 2.0](LICENSE). Vendored whisper.cpp/ggml and separately downloaded models retain their upstream licenses. Google ML Kit and the installed speech engine are subject to their provider terms. See [NOTICE](NOTICE) and [third-party notices](THIRD_PARTY_NOTICES.md).
