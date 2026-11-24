# PocketAgent

PocketAgent is an **offline-first, on-device Android assistant**. It runs local `.litertlm` models through LiteRT-LM, keeps conversations/notes/memory on device, and is designed around a privacy-first "answer the request yourself" UX.

> Status: **active development**. This repo contains the Android app source, JVM unit tests, and CI build workflow. Android SDK/Gradle runtime validation is performed in CI or a local Android dev environment.

## What it does today

- **Local LLM chat**: import a compatible `.litertlm` model and chat fully on-device (LiteRT-LM).
- **Attachments / RAG**: attach text/PDF reference material; local BM25-style retrieval with citations for document Q&A.
- **Voice (experimental/prototype paths)**: live dictation uses the device speech-recognition provider (provider behavior varies). Imported WAV transcription uses a user-selected whisper.cpp `ggml` `.bin` model (not bundled).
- **Sessions & local memory**: conversation sessions, personal notes, user memory, privacy audit/controls.
- **Safety/UX guardrails**: answer-first prompting, runaway/repetition guard, bounded attachment/context budgets, confirmation-first action handling, no AccessibilityService automation.

## Current constraints (honest)

- **Model/context limits are real**: default budgets are tuned for small-context on-device models (e.g. 4K context). Large attachments/history are trimmed to fit; the UI should be treated as best-effort guidance, not a guarantee.
- **Omni model utilization is partial**: the app currently uses LiteRT-LM text generation paths. Image/audio-input/audio-output capabilities of multimodal models are not fully wired as end-to-end product features yet.
- **No cloud by app design**: the app declares no `INTERNET` permission. Dictation quality/offline behavior depends on the device speech provider.
- **Release hardening in progress**: debug build workflow exists; instrumented/device validation, signing/release pipeline, localization, and full model capability registry are ongoing.

## Build

Requirements (local/CI Android environment):

- Android SDK (API 35), JDK 17
- Gradle 8.14.4 (project uses Gradle build files directly; no wrapper jar committed)
- Android NDK 27.2.12479018 + CMake 3.22.1 (whisper.cpp native build)

Commands:

```bash
gradle --no-daemon testDebugUnitTest assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Source bundle:

```bash
python3 scripts/package_source.py --check
python3 scripts/package_source.py
```

## CI

GitHub Actions workflow `.github/workflows/build.yml` runs on push/PR/manual dispatch: verifies source bundle, runs `testDebugUnitTest`, assembles debug APK, and uploads APK/log artifacts.

## Privacy & security notes

- No `INTERNET` permission in app manifest.
- Models are imported into app-private storage; import has size/sanity checks and transactional replacement.
- Backup/data extraction rules disable cloud/device-transfer backup of local app data.
- Biometric/device-credential lock is available for local access protection.
- Answers/actions are not executed automatically; sensitive tasks require user verification.

## Development docs

- [PROJECT_AUDIT.md](PROJECT_AUDIT.md) - current audit snapshot, verified gaps, and priorities.
- [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md) - phased execution roadmap.

## License

See repository license file(s) and third-party dependency licenses. Model files are user-supplied and subject to their own licenses.
