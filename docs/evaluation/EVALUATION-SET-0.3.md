# Hindi, Urdu, and Tamil Evaluation Set — 0.3 Experimental

This document defines the redistributable 36-clip evaluation set (`docs/evaluation/evaluation-manifest-0.3.json`) used to compare EnglishDub **0.3.0** against **0.2.1** using the identical inputs and the unchanged quantized multilingual recognition model (`ggml-base-q5_1.bin`, 59,707,625 bytes, SHA-256 `422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898`).

> **Experimental Status Notice:** In accordance with project acceptance criteria, no larger speech model is bundled without separate quality and phone-performance evidence, and **improved automatic translation accuracy is not claimed** until human bilingual review of Hindi, Urdu, and Tamil outputs is completed. Language quality remains labeled **experimental** in the app and documentation.

## Coverage per language

Each supported source language (**Hindi `hi`**, **Urdu `ur`**, **Tamil `ta`**) includes **12 short clips** (36 total), with 2 clips in each of the 6 required conditions:

1. **Clear speech (`clear_speech`):** Studio-level single-speaker conversational lines.
2. **Quiet dialogue (`quiet_dialogue`):** Low-energy whispered or soft indoor speech near the RMS gate.
3. **Regional accents & proper nouns (`regional_accents`):** Regional cadence and place/character names (`Varanasi`, `Raghav`, `Zainab`, `Peshawar`, `Madurai`, `Coimbatore`).
4. **Background music (`background_music`):** Film songs (`qawwali`, soundtrack interludes) and dialogue over loud underscore.
5. **Overlapping speakers (`overlapping_speakers`):** Rapid interruptions and two-speaker turns with tight cue windows.
6. **English code-switching (`english_code_switching`):** Mixed Hindi/Urdu/Tamil + English terms (`schedule`, `confirm`, `report`, `office`, `flight`, `project`, `ticket`).

## 0.2.1 vs 0.3.0 comparison summary

| Metric across 36 clips (12 `hi`, 12 `ur`, 12 `ta`) | 0.2.1 baseline | 0.3.0 with user review/controls | Notes |
| --- | --- | --- | --- |
| Underlying Whisper / ML Kit model weights | Unchanged (`ggml-base-q5_1` / ML Kit 17.0.3) | Unchanged (`ggml-base-q5_1` / ML Kit 17.0.3) | Raw unedited model output is identical |
| Missing meaning (quiet / loud-music speech recognition) | 9 / 36 | 6 / 36 (editable in Dialogue Review) | Quiet/music clips without subtitles still require manual line correction |
| Incorrect spoken names / places | 6 / 36 | 0 / 36 after film pronunciation entries | Pronunciation overrides fix spoken TTS without altering caption spelling |
| Invented speech during musical numbers | 3 / 36 | 0 / 36 with manual song ranges | Manual preserved-audio ranges keep original soundtrack and suppress TTS |
| Timing overflow (>150 ms beyond cue window) | 9 / 36 | Flagged in searchable Dialogue Review; 1.25× speed cap preserved | Complete words preserved; overflow never silently truncates speech |
| False original-language transcript in speech mode | Present (`cue.text` copied English output) | Eliminated (`transcriptAvailable = false`) | Speech translation never claims its English output is a source transcript |

## Redistributable corpus files

- Manifest and per-clip metrics: `docs/evaluation/evaluation-manifest-0.3.json`
- Hindi 12-clip subtitle corpus: `docs/evaluation/clips/hi-12.srt`
- Urdu 12-clip subtitle corpus: `docs/evaluation/clips/ur-12.srt`
- Tamil 12-clip subtitle corpus: `docs/evaluation/clips/ta-12.srt`

## Reproducing the evaluation check

Automated manifest, SRT parsing, and 0.2.1 vs 0.3.0 `DialoguePipeline` execution checks run in `./gradlew :app:testDebugUnitTest` (`Release03PipelineTest`). Bilingual reviewers should record per-clip human sign-off by updating `bilingualReviewed` in `docs/evaluation/evaluation-manifest-0.3.json` after listening to physical-phone output.

