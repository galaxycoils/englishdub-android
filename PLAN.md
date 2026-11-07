# Lightweight Offline English Dubbing for Android

## Summary

Build a native Android video player that prepares English dubbing before watching, then plays the saved dub with minimal processing.

Support local videos and direct downloadable media links. Prioritize Hindi, Urdu, and Tamil on budget and midrange phones.

## Core behavior

Use the lightest available option:

1. Play an existing English audio track.
2. Read English subtitles aloud using an installed offline voice.
3. Translate Hindi, Urdu, or Tamil subtitles offline, then generate English speech.
4. When subtitles are unavailable, use an optional offline speech model to translate dialogue into English.

Offer subtitle-based and speech-based preparation explicitly. Download required models during setup; preparation and playback must work in airplane mode afterward.

Use one neutral English voice. Lower the original soundtrack during generated speech, with an adjustable background volume. This preserves some original dialogue; actor voice replacement and music separation are outside the first version.

## Implementation

- **Platform:** Kotlin, native Android Views, Android 8+, ARM64. Use Media3 for hardware-accelerated video playback. Avoid WebViews and cross-platform runtimes.
- **Inputs:** Android file picker, embedded text subtitles where supported, and external SRT/WebVTT files. Download direct HTTP/HTTPS video links locally before preparation. Protected streaming-service playback is excluded.
- **Translation:** ML Kit on-device translation with individually downloadable Hindi, Urdu, and Tamil packs.
- **Speech recognition:** `whisper.cpp` through JNI, using a quantized multilingual base model in translation-to-English mode. Validate film dialogue quality before declaring each language supported.
- **Voice:** Android TextToSpeech with a verified offline English voice. Guide users through voice installation when none is available.
- **Preparation:** Decode audio in bounded chunks, process one job at a time, checkpoint completed segments, and save the resulting English track as AAC. Never load an entire movie into memory.
- **Playback:** Use the video player as the master clock and synchronize a second audio player for the prepared dub. Coordinate buffering, pause, seek, and playback speed; correct drift greater than 100 ms. Derive original-audio ducking from saved speech timings.
- **Storage:** Persist jobs and timed dialogue with Room. Reuse completed dubs; provide deletion controls for downloads, models, and generated audio.

Provide four simple screens: library, preparation progress, player, and offline downloads/settings.

## Efficiency and failure handling

- Unload recognition and translation models before playback. Run no AI inference while watching.
- Use at most two recognition threads by default. Pause preparation under severe thermal pressure.
- Run preparation as a visible foreground task with cancellation and checkpointed recovery, including Android service time limits.
- Preserve complete spoken lines, use bounded speech-rate adjustment, and queue overflowing lines without overlap. Flag timing mismatches before playback.
- Report missing models, unsupported codecs, insufficient storage, and unreadable files with a specific recovery action.
- Initial release targets, subject to device measurement: base download under 30 MB excluding models, playback under 250 MB PSS, and preparation under 800 MB PSS. Report preparation speed rather than promising real-time performance.

## Verification and acceptance

- Test all four audio paths, including Hindi, Urdu, and Tamil samples reviewed by speakers of those languages.
- Verify preparation and playback in airplane mode after setup.
- Test full-length films, crowded subtitles, silence, cancellation, process termination, storage exhaustion, and interrupted downloads.
- Verify synchronization after repeated seeks, buffering, and speed changes; target drift within 150 ms during stable playback.
- Measure memory, battery, temperature, and preparation time on a 3–4 GB budget phone and a 6–8 GB midrange phone.
- Set up the Android SDK/NDK and build tooling in the empty workspace, then run release builds, lint, automated timing/checkpoint tests, and device playback tests.

Offline speech translation is the heavier optional mode. Lightweight playback and subtitle-based dubbing are the primary efficiency goals.
