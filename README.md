# PocketAgent

PocketAgent is an on-device task-instruction assistant. Select a compatible `.litertlm` model, describe a task, dictate it, or attach plain-text reference files, and the model suggests steps for you to review and perform manually.

The current Gemma 3 1B INT4 setup is text-first: it does not understand image attachments or transcribe imported audio files. Live dictation uses the speech-recognition provider configured on the device. See [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md) for planned modality work and current scope.

**This redesigned version does not read other apps' screens, launch apps, tap, type, scroll, or change settings.** It has no Accessibility Service. It requests microphone access only if you choose to dictate a task; speech recognition behavior depends on the provider installed on your device. The app requests offline recognition when supported, but that preference is controlled by the speech provider.

## Requirements

- Android 9 (API 28) or newer
- Java 17
- Android SDK with API 35 installed
- Gradle 8.14.4

## Build a debug APK

From the repository root, run:

```bash
gradle --no-daemon assembleDebug
```

The APK is created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Run unit tests with `gradle --no-daemon testDebugUnitTest`. The project uses Gradle build files directly and does not include a Gradle Wrapper, so install Gradle 8.14.4 separately before running these commands.

Regenerate the source bundle after source or documentation changes with `python3 scripts/package_source.py`; verify it matches the tree with `python3 scripts/package_source.py --check`. The packager rejects legacy AccessibilityService implementation files in the live app source.

## Build with GitHub Actions

The **Build APK** workflow verifies the source bundle, runs unit tests, and assembles a debug APK on branch pushes, pull requests targeting `main`, and manual dispatch. To start it manually, open **Actions → Build APK → Run workflow** in GitHub. After a successful run, download the `PocketAgent-debug-apk` artifact from the run summary. If the Gradle build fails, the workflow summarizes diagnostic lines and uploads a `PocketAgent-gradle-failure-log` artifact.

## First launch

1. Install and open PocketAgent.
2. Tap **Choose model (.litertlm)** and select a compatible model file.
3. Type a task, or tap **Dictate** and review the recognized text.
4. Tap **Suggest steps**, then review and carry out any suggestions yourself.

The model is copied to the app's private files directory and is not bundled in the APK. Imports are limited to 5 GiB and use a temporary file so a failed import preserves the previous complete model. Dictation uses the speech-recognition provider configured on the device; its offline/network behavior is provider-dependent. Suggestions are not executed automatically; verify each instruction, especially for account, payment, or security tasks.
