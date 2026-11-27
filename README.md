# PocketAgent

PocketAgent is an **offline-first, on-device Android assistant**. It runs local `.litertlm` models through LiteRT-LM, keeps conversations/notes/memory on device, and is designed around a privacy-first "answer the request yourself" UX.

> Status: **CI-verified at [run 45934369004](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45934369004) on `f1f99c2`** (branch `hardening/9of10`, last production-behavior commit). `main` currently resolves to `cf17544`; owner merge steps are in [docs/handoff-checklist.md](docs/handoff-checklist.md). Android SDK/Gradle runtime validation is performed in CI or a local Android dev environment.

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
- Gradle 8.14.4 via the committed wrapper (`./gradlew` / `gradlew.bat`) — no global Gradle install needed
- Android NDK 27.2.12479018 + CMake 3.22.1 (whisper.cpp native build)

Commands:

```bash
./gradlew --no-daemon --stacktrace --console=plain testDebugUnitTest assembleDebug
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

GitHub Actions workflow `.github/workflows/build.yml` runs on push/PR/manual dispatch: verifies the source bundle (`python3 scripts/package_source.py --check`), runs `testDebugUnitTest`, assembles the debug APK, verifies Roborazzi baselines, runs required `lintDebug` and `detekt`, and executes API 28/35 emulator smoke tests with a zero-test guard. It has `contents: read` only and never commits to the source tree. See [docs/EVIDENCE.md](docs/EVIDENCE.md) for the current evidence ledger and green run link.

## Privacy & security notes

- No `INTERNET` permission in app manifest.
- Models are imported into app-private storage; import has size/sanity checks and transactional replacement.
- Backup/data extraction rules disable cloud/device-transfer backup of local app data.
- Biometric/device-credential lock is available for local access protection.
- Answers/actions are not executed automatically; sensitive tasks require user verification.

## Development docs

- [docs/EVIDENCE.md](docs/EVIDENCE.md) - mark-by-mark evidence ledger with verified commits.
- [PROJECT_AUDIT.md](PROJECT_AUDIT.md) - final evidence-backed scorecard and verified gaps.
- [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md) - phased execution roadmap.
- [docs/handoff-checklist.md](docs/handoff-checklist.md) - exact owner-only merge, gate, tag, and cleanup steps.
- [docs/ci-handoff.md](docs/ci-handoff.md) - owner-only branch-protection steps.
- [docs/release.md](docs/release.md) - signing template and R8 decision.

## License

MIT — see [LICENSE](LICENSE). Third-party components are listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md). Model files are user-supplied and subject to their own licenses.
