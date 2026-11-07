# Third-party software

- **whisper.cpp v1.7.6** and its ggml source: MIT. License included at `app/src/main/cpp/vendor/whisper/LICENSE`; ggml licenses are retained in the vendored source. A local cancellation-propagation modification is documented in `ENGLISHDUB_PATCHES.md` beside that license.
- **Whisper model weights**: downloaded separately from `ggerganov/whisper.cpp` on Hugging Face; originally released by OpenAI under the MIT license. No weights are bundled in this repository or the APK.
- **AndroidX / Media3 / Room / Activity / Lifecycle / Core**: Apache License 2.0.
- **Kotlin / kotlinx.coroutines**: Apache License 2.0.
- **Google ML Kit Translation**: Google SDK and API terms apply; translation models download separately. See https://developers.google.com/ml-kit/terms.
- **Installed Android TTS engine and voice data**: supplied separately by the device or user; provider terms apply.

Upstream source licenses and dependency metadata are retained. See the individual dependencies for complete notices.
- **Silero VAD v5.1.2 model**: optional, separately downloaded ggml conversion from `ggml-org/whisper-vad`; upstream Silero VAD is MIT licensed. No VAD weights are bundled in the APK or source repository.
- **AndroidX RecyclerView**: Apache License 2.0.

Original EnglishDub application code is Apache License 2.0; upstream vendored source retains its original licenses.
