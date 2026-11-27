# Release engineering notes (v1.6.1)

This file records the release decisions that can be made without repository-owner credentials. No keystore, password, signed APK, tag, or GitHub release is committed.

## Version

- `versionName = "1.6.1"`
- `versionCode = 13`
- Target tag: `v1.6.1` (owner action, not performed by the hardening agent)

## Build artifacts

CI uploads the unsigned debug APK as `PocketAgent-debug-apk`. The release APK is intentionally not built in CI because signing material must stay outside the repository.

## Signing template

`app/build.gradle.kts` reads signing material only from environment variables:

- `POCKETAGENT_RELEASE_STORE_FILE`
- `POCKETAGENT_RELEASE_STORE_PASSWORD`
- `POCKETAGENT_RELEASE_KEY_ALIAS`
- `POCKETAGENT_RELEASE_KEY_PASSWORD`

If the store-file variable is absent, the `release` build remains unsigned and no signing config is created. Real keystores, `signing.properties`, `*.jks`, and `*.keystore` are ignored by Git. The owner supplies these values in the release environment after merge.

## R8/minify decision

`isMinifyEnabled = false` for `v1.6.1`. The app loads native LiteRT-LM and whisper.cpp libraries and uses reflection/PDF parsing paths whose shrinker rules are not yet validated on device. Enabling R8 without a dedicated shrinker verification run could remove runtime-only classes. The decision is to ship unminified, then add an R8 verification job before a later release. `proguardFiles` are retained so the future change is configuration-only.

## Owner-only release steps

1. Merge the hardening commits in order.
2. Confirm `main` has a fresh ≥19/20 green run window and the required `Build APK / Build debug APK` check.
3. Create tag `v1.6.1` and release notes from [EVIDENCE.md](EVIDENCE.md).
4. Verify the README status link points to a green commit on `main`.
