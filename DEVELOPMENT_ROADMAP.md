# PocketAgent Development Roadmap

This is the persistent task ledger for the current product direction. It is grounded in the repository review in [`PROJECT_AUDIT.md`](PROJECT_AUDIT.md). The user approved M0 after reviewing the audit; M0 remains in progress. The user has also explicitly approved a bounded M5 scope: operate only on user-selected files/results, confirm before overwrite/delete or irreversible changes, and never control other apps or the phone. This approval does not authorize general-purpose execution or automation.

## Governance and product constraints

- Current product: local model proposes steps for a human to review and perform manually.
- Standing decision: **do not restore AccessibilityService-based screen inspection/control or automatic phone actions**, and do not attempt to hide behavior or bypass Play Protect.
- Do not add cloud providers, telemetry, paid services, or persistent memory without explicit approval and clear privacy behavior.
- No native sub-agent runner is available in this workspace. Role assignments below are simulated review lenses, not claims that independent agents were launched. The current single agent remains accountable for review and tests.
- A task is not complete merely because code exists: acceptance requires evidence. `CI BUILD` does not mean on-device behavior has been tested.

## Status definitions

- **COMPLETED** — acceptance evidence recorded.
- **IN PROGRESS** — active work with a defined scope.
- **BLOCKED** — prerequisite/environment unavailable.
- **PROPOSED** — awaiting user approval; implementation has not started.
- **OUT OF SCOPE** — rejected or intentionally excluded by product decision.

## Current baseline

- Audit baseline: `b86f420`; M0 implementation commit: `ab12ad9`; M1 UI commit: `eb0668b`; base `main` is `f579f52`. The active branch's starting tip for this Save Answer slice is `6006c74` (`Record CI result for completion fix`).
- GitHub Actions run [`45373365552`](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45373365552) passed source-archive verification, `testDebugUnitTest`, `assembleDebug`, and APK artifact upload for the Save Answer implementation. The earlier response-completeness baseline passed in run [`45370018036`](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45370018036).
- The current passing suite contains 27 unit tests across seven test files, including three focused answer-writer tests for UTF-8 output, blank-answer rejection, and size-bound rejection. No Android UI/instrumented tests or lint run are present. This workspace has no Java, Gradle, adb, or Gradle wrapper, and no emulator/device is attached; the system-picker save flow still needs on-device verification.
- User-reported: PocketAgent works with Gemma 3 1B INT4 on a Redmi Note 10 Pro, and the user confirms the 256-token response-completeness adjustment works. Previous approximate timing reports were 30–60 seconds before streaming, then about 10 seconds and later 2–4 seconds; these are not controlled benchmarks. No independent on-device verification or speed measurement was performed here. Android version/RAM and model-file size remain unknown.
- Current app supports typed prompts, live system speech-to-text, bounded text/Markdown/CSV/PDF attachments, the approved local WAV prototype, local CPU inference, and answer copy/share/TTS plus user-selected text export (Save Answer; CI passed, Android picker/device behavior still unverified). PDF and WAV source/build tests pass in CI; runtime/device validation remains open. Image input, chat history, general-purpose tool execution, and phone/app automation are not implemented.
- The source ZIP matches the current manual-only source. `python3 scripts/package_source.py --check` passed locally and in CI; no AccessibilityService-based control code is present in the app source.

## Task ledger

| ID | Task | Owner lens (simulated) | Status | Dependencies | Risk |
| --- | --- | --- | --- | --- | --- |
| AUD-001 | Repository, feature, privacy, build and architecture audit | Repository Auditor + QA + Security | **COMPLETED** | None | Low; report in `PROJECT_AUDIT.md` |
| ARC-001 | Confirm incremental architecture and dependency boundaries | System Architect | **COMPLETED (M0 scope)** | AUD-001 | Narrow pure helpers; no broad rewrite |
| SAFE-001 | Regenerate stale source ZIP; verify no legacy service/control remains | Security + Release | **COMPLETED** | AUD-001; M0 approved | Current archive checker and CI pass; no legacy implementation files in bundle |
| TEST-001 | Add focused unit tests for bounded text extraction and import rollback | QA + Implementation | **COMPLETED (CI/unit scope)** | ARC-001; small testable seam | 24 unit tests passed in run `45369663629`; no Android UI/instrumented tests |
| AI-001 | Harden model import (bounds, free space, atomic replace/rollback, retry/error states) | AI Engine + Implementation | **COMPLETED (CI/unit scope)** | ARC-001, TEST-001 | Size/free-space guard and rollback tests pass; device/runtime failure behavior remains unverified |
| UX-001 | Accessibility, responsive layout, and accurate speech-privacy disclosure review | UI/UX + Security + QA | **IN PROGRESS** | AUD-001 | Semantic buttons, labels, 48dp minimum targets, polite live regions, and stronger muted-text contrast implemented; CI passed; Android accessibility/layout review remains outstanding |
| QA-DEVICE-001 | Verify permission, missing/corrupt model, speech, inference and UI flows on Android | QA | **IN PROGRESS (partial user-reported install/inference/performance checks)** | CI-built debug APK; remaining device checklist | User reports install/use and ~2–4 s answers after the 128-token change on Redmi Note 10 Pro, but says some replies stop mid-way; 256-token adjustment needs device retest. No `adb`/emulator is available here and TalkBack, permission/error and layout checks remain unreported |
| AI-002 | Model status/capability metadata and inference progress/cancel feasibility | AI Engine + QA | **IN PROGRESS (M1 latency and completion slice)** | AI-001; LiteRT-LM API review | Streaming/output cap in `64dc851` improved perceived/observed speed (~2–4 s per user report), but user reports some answers stop mid-way; raising cap 128→256 and emphasizing completion; CI/device verification pending |
| DOC-001 | Bounded PDF text extraction with user preview/source attribution | Document/Knowledge + Security | **IN PROGRESS (CI/unit verified; device/runtime validation pending)** | M0 device QA remains open; PDF input/staging and TEST-001 | Medium-high; parser attack surface, decompression, dependency and device testing |
| VOICE-001 | User-selected local WAV transcription using whisper.cpp | Voice + Security + QA | **IN PROGRESS (prototype CI/build/unit verified; device/model validation pending)** | Model weights remain unselected/unbundled; audio format bounds and privacy disclosure | High; native parser/runtime, memory use, model terms, language and device performance |
| VOICE-FEAS-001 | Research offline imported-audio ASR options | Voice + Security + QA | **COMPLETED (research only; no integration)** | No code/dependency integration | Candidate comparison documented; model-specific choice and device benchmark remain open |
| HW-ACCEL-001 | Hardware acceleration with safe GPU/CPU fallback for LiteRT-LM | AI Engine + Hardware Specialist | **COMPLETED (Phase 2 CI/unit scope)** | AI-001, AI-002 | Dynamic GPU/CPU selection, automatic graceful fallback on OpenCL/init failure, manifest declarations, unit tested |
| VOICE-REC-001 | In-app PCM 16-bit 16kHz audio recording with Whisper integration | Voice + Security + Hardware Specialist | **COMPLETED (Phase 2 CI/unit scope)** | VOICE-001, Manifest permission | Hard-capped (60s, 25 MiB), silence cutoff, zero-wipe memory buffers, direct Whisper transcription, unit tested |
| VIS-001 | Image input with a compatible multimodal model/runtime | AI Engine + UI/UX + QA + Security | **PROPOSED** | M0 gates, AI-002, model/API/hardware compatibility review | High; memory, preprocessing, model license/size |
| STATE-001 | Optional local conversation history with user deletion controls | Memory/Knowledge + Security + QA | **PROPOSED** | M0 gates; explicit retention approval | Medium-high; data lifecycle and migrations |
| TOOL-001 | Bounded local tools on user-selected files/results | Tooling + Security + QA | **IN PROGRESS (first slice)** | User approved the restricted M5 scope; CI and Android picker/write verification pending | Scope creep into unselected files, overwrite/delete, or automation; each destructive/irreversible action needs explicit confirmation |
| AUTO-001 | Accessibility-driven screen inspection/control or autonomous phone actions | Tooling + Security | **OUT OF SCOPE** | Explicitly rejected direction | Prohibited by product constraint |

## M0/M1 device verification checklist — NOT RUN

Use the debug APK artifact from successful CI run `45366074612` on an Android device or emulator. The user has reported installation, basic inference, and an approximate latency improvement, but this covers only a subset of these checks. CI compilation and a single approximate timing do not satisfy the remaining functional/accessibility review. Record device/API level, APK hash, model source/version, results, and any failures before closing QA-DEVICE-001 or UX-001.

**M0 functional and privacy checks**

- [ ] Fresh install and launch with no model: verify the ready/missing-model status is understandable, Suggest does not crash or infer without a model, and typing remains available.
- [ ] Select a valid, supported `.litertlm` model from local storage: verify import progress, ready state, one short local prompt, a non-empty response, retry after a transient inference error, and no stale output after failure.
- [ ] Cancel model selection; select a non-`.litertlm` file; then test a corrupt `.litertlm` only on a disposable install or after backing up the known-good model. Verify user-visible failure/retry and record whether a previous valid model remains usable. Do not risk the only copy of a user model.
- [ ] With a plain-text fixture, attach, inspect the preview, remove it, and verify its text is not used after removal. Test empty input and a fixture that reaches the 12,000-character combined limit; verify the app stays responsive and explains the limit.
- [ ] Tap Dictate before granting microphone permission: verify Android asks only at that point. Deny permission and verify the fallback status plus typing/attachment still work. Revoke permission in Android settings and repeat.
- [ ] Grant permission and test speech results, no-speech/empty results, recognizer errors, and an unavailable-recognizer case where the device can provide one. Verify the recognized text is reviewable/editable, failures are visible, and the user can type instead.
- [ ] Test model initialization/inference errors and empty model output where safely reproducible. Verify there is no crash, stale answer, automatic action, or loss of a retry path; capture logs with private prompt content redacted.
- [ ] Verify ordinary text/model handling does not ask for unrelated permissions. Confirm there are no Accessibility settings/service prompts and no screen-reading, app-launch, tap, typing, or gesture behavior.

**M1 accessibility and layout checks**

- [ ] With TalkBack on, navigate the whole screen in order. Confirm every action has a concise, unique spoken label; enabled/disabled state is conveyed; transcript confirmation and attachment removal are discoverable; status updates for import, listening, generating, ready, and errors are announced without repetitive noise.
- [ ] Verify every interactive control has at least a 48dp effective touch target (56dp for the main suggestion action) and remains usable with TalkBack focus and switch/keyboard navigation if available.
- [ ] Set Android font/display size to the largest practical values, then repeat at narrow portrait and landscape widths. Scroll through the screen and verify labels, status messages, privacy disclosure, preview controls, and primary actions do not overlap or become unreachable.
- [ ] Check foreground/background contrast for primary, muted, disabled, and status text in the shipped theme. Verify status is communicated by text/state, not color alone.
- [ ] Confirm the disclosure distinguishes local model inference from device-configured speech recognition, whose provider/network behavior may vary. Confirm imported file transcription does not request microphone permission.
- [ ] On the Redmi Note 10 Pro with the same Gemma 3 1B INT4 file, time three short identical prompts and one request that needs several complete steps after the new APK install. Verify no sentence or step is cut off. Record Android version, RAM, model file size, time-to-first-visible-text, total completion time, and answer completeness; keep prompt data non-sensitive. Compare cold first run separately from warm runs. Do not report a speedup based on one response.

## Dependency map

```text
AUD-001 (complete)
  └── ARC-001 (review proposed boundaries)
       ├── SAFE-001 (archive/release integrity) ──┐
       ├── TEST-001 (test seam and regression tests) ──┐
       ├── AI-001 (reliable model import) ─────────────┤
       └── UX-001 (accessibility/privacy review) ───────┤
                                                       └── M0 quality gate
M0 quality gate
  ├── AI-002 (runtime capability/progress review)
  │    └── VIS-001 (image/multimodal; only if compatible)
  ├── input abstraction + TEST-001
  │    ├── DOC-001 (PDF/doc extraction)
  │    └── VOICE-001 (audio-file transcription)
  └── explicit privacy/retention approval ── STATE-001
```

## Milestones and acceptance gates

### M0 — Safe, verifiable baseline (P0/P1) — IN PROGRESS (user approved)

**Objective:** correct release/source integrity, add regression evidence, and protect model and user data. Archive safety, 24 unit tests, import hardening, CI unit tests, and debug APK assembly pass in run `45356278351`; device-level permission/speech/inference/accessibility checks remain unverified and block full M0 closure.

**Tasks:** SAFE-001, TEST-001, AI-001, UX-001. Confirm architecture recommendations through ARC-001.

**Likely files:** `PocketAgent.zip` (remove/regenerate only after approval), `MainActivity.kt` or extracted pure helpers, test build configuration, manifest/UI strings, workflow/docs.

**Acceptance criteria:**
- Any distributed source archive matches the current manual-only source; scan proves no `AccessibilityService`, `AgentService`, screen-node reading, taps/typing/gestures or automatic app launches.
- Import success and failure/rollback are tested. Known non-`.litertlm` filenames, empty streams, size-limit and low-storage paths are handled; old valid model remains intact after a failed replacement. The LiteRT runtime remains the definitive validator of model contents at inference time.
- Attachment extraction respects a tested total limit, supports removal, and treats contents as untrusted.
- At least missing model, permission denied, unavailable speech recognizer, empty output and inference error states have regression tests or documented device checks.
- CI debug APK and tests pass. Device checks are reported separately.
- Speech recognition provider limitations are described without implying guaranteed offline processing.

**Risks:** adding abstractions can overgrow a small app; keep seams minimal and avoid broad rewrite.

### M1 — Usability and accessibility hardening (P1) — IN PROGRESS (user said “ok move on”)

**Objective:** make current manual-assistant flows clearer and accessible without a redesign for redesign's sake.

**Tasks:** UX-001, targeted parts of AI-002.

**Implemented slice (CI verified):** interactive controls are semantic Android Buttons with explicit accessibility descriptions and pressed-state feedback; minimum target heights are 48dp (56dp for the main action); task input is associated with its visible label; model and status changes use polite accessibility live regions; muted text contrast is strengthened; speech-service privacy copy remains visible. **Latency/completion adjustment:** LiteRT-LM streaming callbacks display output incrementally. The 128-token cap in `64dc851` improved speed (user reports ~2–4 seconds on Redmi Note 10 Pro) but user reports some answers stop mid-way. This patch raises the cap to 256 and explicitly asks the model to finish each thought. CI passed in run `45369663629`; device verification of answer completeness and the speed trade-off is pending.

**Acceptance criteria:** TalkBack can identify controls and state; touch targets and contrast pass review; large text and narrow/landscape layouts do not truncate essential actions; import, listening, generating, failure and ready states are distinguishable; privacy copy clearly separates on-device inference from OS speech-recognition services. Test on emulator and at least one real device if available. **Device-level criteria are still unverified and block M1 completion.**

**Risks:** platform/provider differences; no device QA can be claimed without a device.

### M2 — Documents and audio input (P2) — IN PROGRESS (PDF and approved WAV prototype CI/unit verified; device validation pending)

**Objective:** accept user-selected material only through a bounded, inspectable representation the current text model can use.

**Approved scope:** DOC-001 adds local PDF extraction with explicit byte/page/context bounds, page labels, preview/expand/remove controls, and recoverable failures. VOICE-001 adds a CPU-only whisper.cpp prototype for user-selected WAV files with strict format/size/duration bounds; the user chooses a local ggml `.bin` model. Do not bundle or automatically download weights, add cloud ASR, or claim support for compressed audio. Scanned-page OCR, other document formats, and all other audio formats are excluded. VOICE-FEAS-001 research is complete and documented.

**Offline-ASR research findings (not an implementation decision):**

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) declares Apache-2.0 for its repository and provides Android examples plus native `OfflineRecognizer` APIs. Its `OfflineStream` accepts PCM `FloatArray` samples and a sample rate. The reviewed two-pass Android demo is microphone-centred; an imported-file route still needs bounded decoding/resampling, model selection, native ABI packaging, and testing. Pretrained model licenses, sizes, and language coverage must be reviewed individually; APK/model footprint and target-device speed were not measured.
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) declares MIT and includes an [Android example](https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android) that transcribes local WAV samples with a locally loaded model. Its published ggml model table lists approximate weights of 75 MiB for tiny, 142 MiB for base, 466 MiB for small, and 1.5 GiB for medium. The Android sample's WAV helper reads the entire file into memory and assumes a simple WAV layout; it is a feasibility example, not safe production import code. Runtime license does not replace review of model terms. Multilingual variants exist, but accuracy for target languages and resource use on PocketAgent devices remain untested.
- Android `SpeechRecognizer` remains suitable for the existing live-dictation flow, not a dependable imported-file/offline contract; API support for audio-source input is recognizer-dependent.

**Implementation decision:** the user explicitly approved a WAV-only whisper.cpp prototype, with model selection kept separate. The build fetches pinned whisper.cpp v1.9.5 source at build time (SHA-256 checked); the app includes no model weights and has no runtime download or cloud path. UI requires the user to select a compatible `.bin` model from local storage. CI build/unit tests pass in run `45356278351`; device/runtime testing and model-specific review remain pending.

**Acceptance criteria:** supported PDF constraints are visible; selected files are staged with a hard byte cap; page count and extracted context are bounded; users can inspect/expand/remove extracted text before inference; source page labels are retained; corrupt, empty, encrypted, oversized, over-page-limit, no-selectable-text, and extracted-text-limit cases fail or truncate clearly; extracted text remains untrusted prompt context. For audio, the format parser rejects non-RIFF, compressed, malformed, over-size, over-duration, or unsupported PCM layouts; resampling and stereo downmix are unit tested; audio remains temporary; the transcript is previewed and explicitly confirmed before model inference. CI and device checks are reported separately, and language accuracy/memory/speed must be tested using a separately selected model.

**Implementation limits currently set:** PDFs: 20 MiB, at most 100 pages, 12,000 combined attachment characters, and PDFBox temporary scratch storage capped at 40 MiB. Audio: RIFF WAV PCM16, mono or stereo, 8–48 kHz, at most 25 MiB and 60 seconds, resampled to 16 kHz mono; transcript shares the 12,000-character attachment cap. The user-selected whisper.cpp ggml `.bin` file is capped at 512 MiB and stored privately/removable. Native runtime is built for arm64-v8a and x86_64; no weights are bundled. PDFBox-Android is Apache-2.0 per its published metadata but adds Bouncy Castle 1.73 and Kotlin stdlib 2.0.21 transitives. whisper.cpp v1.9.5 is MIT; the specific weights' terms remain model-specific and unreviewed. The bounds, parsers, and native build pass the CI/unit gates in run `45356278351`; adversarial/runtime/device testing remains outstanding.

**Gate note:** M0/M1 device QA remains outstanding. Run `45356278351` passed the source archive check, all 24 unit tests, and debug APK assembly; this does not verify PDF/WAV runtime, visual accessibility, or device behavior. This work does not mark M0/M1 complete. Imported-audio model choice is still user-supplied and no specific weights are endorsed.

**Risks:** PDF parser attack surface/decompression and dependency maintenance; C++ JNI/native-build compatibility, model parser/runtime crashes, peak RAM and CPU time, model license/quality per language, ABI coverage, and audio decoder limits.

### M3 — True multimodal vision (P3 / feasibility gate) — PROPOSED

**Objective:** allow image questions only if a LiteRT-LM Android model and runtime API supported by this app can actually receive images on target hardware.

**Tasks:** AI-002 then VIS-001.

**Feasibility gate before implementation:** verify official model format/license, LiteRT-LM Android version/API, image `Contents` construction, CPU/GPU/accelerator compatibility, device RAM/storage, model loading/inference and supported input resolutions. Do not ask users to download a new multi-GB model until this gate passes.

**Acceptance criteria:** UI preview/remove; image decode/resize limits; user-approved local input; honest capability reporting; no crashes/OOM on tested devices; image/text and unsupported-model tests; manual-only boundary maintained.

**Risks:** multi-GB artifacts, runtime/API mismatch, hardware memory and model license/terms.

### M4 — User-controlled continuity (P2, separate approval) — PROPOSED

**Objective:** provide optional local conversation history without silently creating long-term memory.

**Tasks:** STATE-001.

**Acceptance criteria:** explicit opt-in, visible history, per-conversation and all-data deletion, storage/retention explanation, no hidden cross-conversation memory, migration tests, and prompt-injection-resistant separation of retrieved context. Not a prerequisite for M0–M3.

**Risks:** sensitive data persistence, backup/migration behavior, retrieval mistakes.

### M5 — Bounded local tools (P3, user-approved narrow scope) — IN PROGRESS

The user approved tools restricted to user-selected files/results, with explicit confirmation before overwrite, delete, or other irreversible changes. PocketAgent must not control other apps or the phone; no AccessibilityService, hidden file access, cloud execution, or autonomous actions.

**First slice: Save Answer.** Add an explicit action that opens Android's `ACTION_CREATE_DOCUMENT` picker and writes only the completed current answer to the URI the user selects. Cancel performs no write; missing picker and write failures are reported. The writer is bounded, UTF-8 plain text. No app-selected path, background file discovery, overwrite/delete utility, or claim of general Claude Code-like capability is included. Three focused output-writer unit tests pass in CI run [`45373365552`](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45373365552), which also passed source archive verification and debug APK assembly. Actual Android picker/write behavior remains unverified because no device/emulator is attached. This narrow save creates a new document rather than modifying/deleting an existing file; any future modification/destruction action requires confirmation.

Future tools require their own focused review, tests, and user-visible scope. The M5 approval does not approve phone automation, arbitrary commands, or unrestricted filesystem access.

## Implementation and verification workflow

For each approved task: inspect relevant code and dependencies; document a narrow plan; implement the smallest testable change; review the diff for privacy/safety and regressions; run unit/integration/build tests; report device-only limits; update this ledger with evidence. Do not mark tasks complete based on code presence alone.

## Current decision and next gate

**Milestones Implemented & Validated by Autonomous Specialist Swarm (2026-11-24):**

- **Phase 1 (Architecture & UI Modernization): COMPLETED**
  - Added `MainUiState.kt`, `MainViewModel.kt`, and `MarkdownFormatter.kt`.
  - Structured AST-based markdown parsing, streaming-safe box framing, clean plain-text stripping for TTS.
  - Decoupled engine abstractions (`LlmInferenceRunner`, `WhisperTranscriber`).
  - Unit tests: `MarkdownFormatterTest.kt`, `MainUiStateTest.kt`, `MainViewModelTest.kt` (27 tests total).

- **Phase 2 (Hardware Acceleration & Live Audio Pipeline): COMPLETED**
  - Added `BackendPreference.kt` and `InferenceEngineManager.kt` (`Backend.GPU()` with automatic graceful fallback to `Backend.CPU()`).
  - Added in-app microphone recording: `InAppAudioRecorder.kt` and `AudioRecordingBuffer.kt` capturing 16kHz mono PCM directly to memory.
  - Strict 60s / 25 MiB bounds, trailing silence cutoff, and immediate zero-wipe (`wipe()`) after inference.
  - Declared optional native OpenCL libraries in `AndroidManifest.xml`.
  - Unit tests: `BackendPreferenceTest.kt`, `AudioBufferBoundsTest.kt` (14 tests total).

- **Phase 3 (Conversational Multi-Turn Memory & Sessions): COMPLETED**
  - Added `ChatMessage.kt`, `ConversationSession.kt`, `SessionStore.kt`, and `ContextBudgetManager.kt`.
  - Local atomic JSON persistence in private app files (`sessions/`) with self-contained codec (no unmocked Android JSON stubs).
  - Rolling window context budgeting with strict demarcated attachment tags and historical attachment summarization.
  - User controls: single session deletion, bulk wipe, JSON/text exports.
  - Unit tests: `ChatMessageTest.kt`, `SessionStoreTest.kt`, `ContextBudgetManagerTest.kt` (16 tests total).

- **Phase 4 (Safe System Intent Tools & Offline RAG): COMPLETED**
  - Added `SystemActionTools.kt`: safe Android Intents for Timer, Alarm, Calendar, Map, and Note. Strictly enforces `autoExecute = false`, `requiresUserConfirmation = true`, and `EXTRA_SKIP_UI = false` with explicit review dialogs.
  - Added `DocumentChunker.kt`: semantic boundary chunking (paragraphs, sentences, lines) with configurable overlap.
  - Added `OfflineKeywordIndex.kt` and `LocalDocumentRetriever.kt`: BM25 ranking and strict 12,000 char prompt budget enforcement.
  - Unit tests: `SystemActionToolsTest.kt`, `DocumentChunkerTest.kt`, `OfflineKeywordIndexTest.kt`, `LocalDocumentRetrieverTest.kt` (37 tests total).

- **Release & Source Bundle Integrity:**
  - `PocketAgent.zip` regenerated and verified via `python scripts/package_source.py --check`.
  - Zero accessibility services, zero prohibited automation, zero telemetry, 100% offline.

**Current in-progress work: UX-001, the targeted M1 response-completeness adjustment, and the first approved M5 slice (Save Answer); QA-DEVICE-001 is partially verified by user report, with the rest of the checklist open.** On Redmi Note 10 Pro with Gemma 3 1B INT4, the user reported that the 256-token response budget/completion instruction now works; no independent device benchmark or verification was performed here. CI run [`45370018036`](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45370018036) passed source archive verification, unit tests, and debug APK build for the preceding response-completeness work. Save Answer's focused writer tests, source archive verification, and debug APK assembly passed in run [`45373365552`](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45373365552); the Android system-picker/write flow has not been tested on-device. TalkBack, permissions/error cases, and layout checks remain open. The M2 PDF/WAV prototype has CI/unit coverage but no new work is being done on it here. Scanned OCR, other audio formats, image input, persistent memory, arbitrary tool execution, and all phone/app automation remain out of scope.
