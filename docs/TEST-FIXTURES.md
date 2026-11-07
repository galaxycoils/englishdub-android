# Instrumentation fixtures

The two short MP4 files in `app/src/androidTest/assets` are synthetic QA inputs.
They exercise existing-English-audio bypass and offline English subtitle preparation.
They are test assets and are not included in the application APK.

`jfk.wav` is the public JFK speech excerpt from the vendored whisper.cpp v1.7.6
sample `app/src/main/cpp/vendor/whisper/samples/jfk.mp3`. This is the English
recognition regression used by upstream; it does not establish Hindi, Urdu or
Tamil recognition or translation quality.

The WAV is committed explicitly because upstream ignores generated WAV files.
Referencing upstream's samples folder as a Gradle asset directory did not make
the locally generated WAV available in a fresh Git checkout.

Conversion on macOS:

```sh
afconvert -f WAVE -d LEI16@16000 -c 1 \
  app/src/main/cpp/vendor/whisper/samples/jfk.mp3 \
  app/src/androidTest/assets/jfk.wav
```

The result is 16 kHz, mono, signed 16-bit little-endian PCM. Decoders on other
platforms may produce different bytes; tests use the committed fixture.

| Asset | SHA-256 |
| --- | --- |
| Upstream `jfk.mp3` | `20d3323a2bcce6f25498b8911a397503a0a99fa92b6ba58d62788cb42b6e5459` |
| Test `jfk.wav` | `3ffb000b19ad63b404ac99611b11e557466349133c8c6f776469e95754766b1f` |

The optional native test model is downloaded separately and must match the exact
size and hash in `ModelStore`. Model weights are never committed or bundled in
test/application APKs. See [testing](TESTING.md) for installation commands.
