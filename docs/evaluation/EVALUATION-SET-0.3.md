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

## Evidence boundary

These are 36 **text fixture cases**, supplied as SRT and manifest entries. No corresponding Hindi, Urdu, or Tamil audio recordings or captured model outputs are included. The JVM test supplies reference translations and generated WAV durations; its two passes exercise the current pipeline with different controls, rather than executing the historical 0.2.1 application.

The `baseline021` and `release030` metrics in the manifest are fixture scenarios, not measured recognition, translation, pronunciation, or human-listening results. Earlier numerical comparisons in this document must not be treated as observed quality improvements. The fixtures verify caption preservation, pronunciation replacement, song-range suppression, reuse, and timing warnings.

A real quality comparison still requires licensed audio with provenance and hashes, actual baseline/candidate engine outputs, device/model/voice identities, and bilingual listening review. Report automatic output separately from manual corrections. Do not enable VAD by default based on these fixtures.

## Redistributable corpus files

- Manifest and per-clip metrics: `docs/evaluation/evaluation-manifest-0.3.json`
- Hindi 12-clip subtitle corpus: `docs/evaluation/clips/hi-12.srt`
- Urdu 12-clip subtitle corpus: `docs/evaluation/clips/ur-12.srt`
- Tamil 12-clip subtitle corpus: `docs/evaluation/clips/ta-12.srt`

## Reproducing the evaluation check

Automated manifest, SRT parsing, and 0.2.1 vs 0.3.0 `DialoguePipeline` execution checks run in `./gradlew :app:testDebugUnitTest` (`Release03PipelineTest`). Bilingual reviewers should record per-clip human sign-off by updating `bilingualReviewed` in `docs/evaluation/evaluation-manifest-0.3.json` after evaluating actual recorded inputs and captured physical-phone output. The current regression intentionally requires every fixture to remain unreviewed; move real review results into a separate results artifact and update that assertion before recording sign-off.

