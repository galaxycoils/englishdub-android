# GitHub publishing

The public repository is [galaxycoils/englishdub-android](https://github.com/galaxycoils/englishdub-android).
The original 0.1 baseline and 0.2 optimization history were restored from the
handoff bundle and pushed without rewriting commits. The earlier cloud
repository-creation HTTP 403 was resolved by creating the approved repository
through the owner's signed-in browser and authenticating GitHub CLI on the Mac.

```sh
./scripts/publish-github.sh
```

The script uses the exact approved repository, checks an existing origin before
pushing, and uses GitHub CLI's credential helper without embedding a token in a
URL. It does not create a differently named repository or force-push history.

## Preview release

The 0.2.1 preview has version code 3. Release assets include the complete ARM64
APK, checksums, and the validation evidence ZIP. The release notes identify the
exact source commit, signer, tested target, pass/skip/failure results and known
limitations. Physical-phone efficiency and Hindi/Urdu/Tamil quality remain
unverified. This is an experimental prerelease, not production acceptance.

The Mac uses a **new local debug signer**. The original cloud preview private key
was not transferred, so Mac-built previews cannot update old cloud-signed
previews in place. New Mac previews can update earlier previews from the same
Mac key. Do not uninstall a user's app or delete user data to bypass a signature
mismatch. Preserve signing keys outside Git. Production distribution requires a
separately managed release key.

GitHub Actions builds the unsigned production APK and a matched pair of
Mac-independent debug application/instrumentation APKs, then runs JVM checks and
lint. CI does not provision a device or establish Android runtime acceptance.
Each CI runner's debug signer is independent of this Mac and the cloud preview.
