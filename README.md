# PocketAgent

PocketAgent is an on-device task-instruction assistant. Select a compatible `.litertlm` model, describe a task, dictate it, attach text/PDF reference material, or use the experimental local WAV transcription path; the language model suggests steps for you to review and perform manually.

Image attachments are not supported. The imported-audio prototype uses a separate whisper.cpp runtime and a `.bin` model selected by the user; no transcription model weights are bundled and no cloud ASR path is provided. The current Gemma 3 1B INT4 model remains text-only. Live dictation still uses the speech-recognition provider configured on the device. WAV prototype CI/device verification and model-specific language/accuracy review remain pending. See [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md) for scope and limits.

**This redesigned version does not read other apps' screens, launch apps, tap, type, scroll, or change settings.** It has no Accessibility Service. It requests microphone access only if you choose to dictate a task; speech recognition behavior depends on the provider installed on your device. The app requests offline recognition when supported, but that preference is controlled by the speech provider.

## Requirements

- Android 9 (API 28) or newer
- Java 17
- Android SDK with API 35 installed
- Gradle 8.14.4
- Android NDK 27.2.12479018 and CMake 3.22.1 (for the CPU whisper.cpp build)
- Build-time access to GitHub to fetch the hash-pinned whisper.cpp source; the app does not fetch engine code or model weights at runtime

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
4. Optionally, choose a local whisper.cpp `.bin` model (up to 512 MiB), then select a WAV file to transcribe. The transcript is attached for preview; expand and confirm it before asking the text model to use it.
5. Tap **Suggest steps**, then review and carry out any suggestions yourself.

The LiteRT model is copied to the app's private files directory and is not bundled in the APK. Its imports are limited to 5 GiB and use a temporary file so a failed import preserves the previous complete model. Whisper model weights are also not bundled; a user-selected `.bin` model is stored privately and can be removed in the UI. The experimental WAV path accepts uncompressed PCM16, one or two channels, 8–48 kHz, at most 25 MiB and 60 seconds, converts samples to 16 kHz mono, and deletes its temporary cached WAV after processing. Compressed audio and other WAV encodings are rejected. Importing/transcribing audio does not request microphone permission. Dictation uses the speech-recognition provider configured on the device; its offline/network behavior is provider-dependent. Suggestions are not executed automatically; verify each instruction, especially for account, payment, or security tasks.
