# PocketAgent

PocketAgent is an Android app prototype for controlling a phone with an on-device LiteRT-LM model. You choose a compatible `.litertlm` model, enable the app's Accessibility Service, then enter or speak a goal. The agent reads visible UI elements and can tap, type, scroll, open apps, or use the system Back and Home actions.

> **Important:** Accessibility access gives the service the ability to inspect screen content and interact with apps. Enable it only if you understand and trust this app. The model is not included in this repository; select a compatible model in the app. Speech recognition depends on the recognizer installed on the device, even though the app requests offline recognition.

## Requirements

- Android 9 (API 28) or newer
- Java 17
- Android SDK with API 35 installed
- Gradle 8.9

## Build a debug APK

From the repository root, run:

```bash
gradle --no-daemon assembleDebug
```

The APK is created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The project currently uses Gradle build files directly and does not include a Gradle Wrapper, so install Gradle 8.9 separately before running the command.

## Build with GitHub Actions

The **Build APK** workflow runs on branch pushes, pull requests targeting `main`, and manual dispatch. To start it manually, open **Actions → Build APK → Run workflow** in GitHub. After a successful run, download the `PocketAgent-debug-apk` artifact from the run summary. If the build fails, the workflow also uploads a `PocketAgent-gradle-failure-log` artifact to help diagnose the Gradle error.

## First launch

1. Install and open PocketAgent.
2. Tap **Pick model (.litertlm)** and select a compatible model file.
3. Tap **Enable accessibility service** and enable PocketAgent in Android Settings.
4. Enter a goal or tap **Speak (offline)**, then tap **Run** (spoken goals run immediately after recognition).

The app has a fast path for goals like `open Settings`; other goals require a selected model. The model is stored in the app's private files directory and is not packaged in the APK.
