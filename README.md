# EnglishDub

A small native Android player that prepares an English dub locally, then plays the saved track without running AI during playback. Built for Android 8+ ARM64 phones, with Hindi, Urdu, and Tamil as the initial source languages.

## Use

1. In **Offline setup**, check the installed English voice. If necessary, open Android voice settings and download an offline English voice.
2. Download only the packs you need: subtitle translation packs are approximately 30 MB per language; the optional dialogue-recognition pack is 59,707,625 bytes (57 MiB).
3. Open a local video, or download a direct HTTP/HTTPS media link. Add an SRT/WebVTT file if available.
4. Choose **Prepare English dub**, select the subtitle/dialogue language, and let preparation finish. Tasks can be paused and resumed. The app automatically uses an existing English audio track when it can identify one.
5. Choose **Watch in English**. The original soundtrack is lowered during dubbed speech; its level is adjustable. Saved dubs can be reused in airplane mode.

The app uses one offline English voice, not actor voice cloning. Speech translation is best effort, particularly for overlapping dialogue, music, accents, and Tamil in the small model. Lines that cannot fit their original timing are queued without overlap and reported as timing warnings. Offline speech preparation may take longer than the film on a budget phone. Protected streaming-service playback and image-based subtitles are not supported. Media codecs depend on the phone.

## Build

Use JDK 17, Android SDK platform 36, NDK `27.2.12479018`, and CMake `3.22.1`. The Gradle wrapper is included. Set `ANDROID_HOME` or an untracked `local.properties` with `sdk.dir=...`.

```sh
./gradlew :app:assemblePreview :app:testDebugUnitTest :app:lintDebug
```

`app/build/outputs/apk/preview/app-preview.apk` is an optimized, installable preview, signed with the local debug key. Its application ID is `app.englishdub.preview`; it is not a production publishing artifact. `assembleRelease` produces an unsigned production build for signing with your own release key. No signing secrets are checked in.

The source under `app/src/main/cpp/vendor/whisper` is vendored from whisper.cpp **v1.7.6**. No model is bundled in the APK. Native code targets baseline ARM64, uses two inference threads, has OpenMP disabled, and links for Android's 16 KB page size.

## Design

- Kotlin and Android Views; no WebView, JavaScript runtime, account, backend, or cloud inference.
- Media3 hardware-decoded video and a separate AAC dub, synchronized to the video clock. Buffering, seeking, playback speed, audio focus, and app backgrounding affect both players.
- ML Kit on-device subtitle translation. Packs download only on explicit request over Wi-Fi. Subtitle text is processed locally.
- Android TextToSpeech accepts only installed, network-independent English voices.
- whisper.cpp translates original dialogue directly to English in bounded 30-second PCM chunks. Silence is skipped. Model ownership ends before voice generation and playback.
- Room checkpoints recognized segments, translated cues, generated speech files, progress, and downloaded-video references. Recognition cues and decoding progress commit in one transaction.
- AAC encoding streams silence and speech files without writing a full-length uncompressed soundtrack. Interrupted AAC encoding restarts from saved cue audio; completed recognition and voice synthesis are reused.
- Foreground notifications expose pause. Severe thermal status, a bounded preparation session, and Android foreground-service time limits pause work. A bounded partial wake lock keeps explicitly requested tasks running with the screen off and is released when they finish or pause. Models, library items, and temporary preparation files have removal controls.

Model downloads are SHA-256 checked before activation:

```text
ggml-base-q5_1.bin
422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898
```

EnglishDub does not upload videos or dialogue. Explicit video/model downloads require internet. ML Kit and your independently installed speech engine have their own platform dependencies.

## Validation status

See [VALIDATION.md](VALIDATION.md) for actual automated results, limitations, and the device acceptance checklist. Resource budgets are targets until measured on real phones: base APK <30 MB, playback <250 MB PSS, preparation <800 MB PSS, and stable playback drift within 150 ms.
