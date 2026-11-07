# Troubleshooting

| Symptom | Action |
| --- | --- |
| No offline English voice | Open Android voice settings, install English voice data, then run the voice check again. Some devices need a separate TTS engine. |
| Translation pack appears to wait | These downloads require Wi-Fi. Check Offline setup after the download completes. |
| Speech recognition pack missing | Download the optional 57 MiB pack; subtitle speech and existing English audio remain separate paths. |
| Phone too warm | Let it cool, then resume. Recognition checkpoints are retained. |
| Low storage | Remove unneeded app downloads/models or library entries. Resume after making space. Preparation needs more temporary space than the final AAC track. |
| File is inaccessible after restarting | Select it again from a provider that supports persistent document access, or copy it to ordinary local storage. |
| Repeated preparation after changing a file | Changed or uncertain source identities invalidate cached work intentionally. |
| Video or original sound cannot decode | Try a format supported by the phone. EnglishDub uses platform decoders rather than bundling every codec. |
| Download fails or saves no playable video | Use the direct downloadable media URL, rather than a website or protected streaming-service page. |
| Some lines finish late | Long speech is preserved, bounded to 1.25× acceleration, and queued. Use shorter/better-timed subtitles for closer timing. |
| Quiet dialogue is missing with speech detection | Disable experimental speech detection and regenerate the dub. It is not enabled by default. |
| Preview update cannot install | Check that the package ID and signing certificate match the previous preview. CI-generated debug keys may differ. Back up needed files before uninstalling. |

When reporting an issue, include Android version, phone model/RAM, app version, mode/language, codec/container, the failing stage, and whether airplane mode changes the result. Share a short redistributable sample when possible. Do not post private videos, signed URLs, credentials, or full heap dumps publicly.
