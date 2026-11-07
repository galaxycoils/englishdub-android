# GitHub publishing

Approved destination: **galaxycoils/englishdub-android**, public. Original code uses Apache 2.0.

During implementation, the connected GitHub credential rejected both GraphQL `createRepository` and REST `POST /user/repos` with “Resource not accessible by integration” (REST HTTP 403). Repository creation therefore requires an account connection with creation access, or the owner creating an empty public repository first. No repository or release is claimed to exist until GitHub confirms it.

When an empty repository is available and accessible:

```sh
./scripts/publish-github.sh
```

The script uses the exact approved repository, checks an existing origin before pushing, and uses GitHub CLI's credential helper without embedding a token in a URL. It does not create a differently named repository or overwrite another destination.

A Git bundle is also included with the delivery, so the full baseline/optimization history can be transferred independently. A source ZIP contains the current source without local SDK paths, build outputs, keys, or model weights.

## Preview release

After required QA evidence is reviewed, publish `v0.2.0-preview` with the optimized ARM64 preview APK and SHA-256 checksums. Include the exact source commit, build variant, signer continuity instructions, tested devices, pass/skip/blocked results, and known limitations. An emulator smoke test does not establish real-phone battery or language quality.

Preview APKs use the existing local debug signer to update earlier previews. Keep that private key outside Git; a freshly generated CI debug key is not interchangeable. Production publication requires a separately managed release signing key. CI uploads an unsigned production APK and a debug-signed instrumentation APK; neither is the locally signed preview update.
