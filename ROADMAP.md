# Roadmap

## Preview acceptance

- Complete Test Android Apps emulator/device flows and publish evidence.
- Re-test the historical software x86 clock failure after the seek-resume callback fix; accelerated ARM64 sync/recovery checks pass. Verify the 150 ms target on physical ARM64 devices before production acceptance.
- Compare baseline and optimized builds on a 3–4 GB budget phone and a 6–8 GB midrange phone.
- Review Hindi, Urdu, and Tamil reference samples, especially quiet/overlapping dialogue and music.
- Keep VAD off by default until quality and processing-time evidence support enabling it.
- Validate OEM voice engines, audio-focus transitions, service limits, and low-storage recovery.

## Subsequent work, driven by measurements

- Tune recognition thread policy and thermal behavior from real phone traces.
- Investigate resumable AAC finalization if encoding restarts are a material cost.
- Evaluate additional source languages and voice choices after the initial three are validated.
- Add codec/container support only where measured need justifies installation/resource cost.
- Establish independently managed production signing and distribution after preview acceptance.

The app remains one complete, native, offline-first player. Actor cloning, cloud inference, and protected streaming-service interception are outside the current roadmap.
