# PocketAgent Repository Audit

**Audit date:** 2026-11-24  
**Repository:** `sanjib20jgec-arch/Pockagent`  
**Audited branch/commit:** `arena/85d065bd-pockagent` / `b86f420`  
**Base:** `main` at `f579f52` (merge of PR #8)  
**Scope:** Read-only application and repository audit. No application code was changed for this audit; the audit and roadmap documents are the deliverables.

## Executive summary

PocketAgent is a small, single-activity Android application written in Kotlin using platform Views and Google's LiteRT-LM Android runtime. The current app is a **manual-guidance assistant**, not an autonomous phone-control agent. It accepts typed tasks, live speech-to-text dictation, and bounded text/Markdown/CSV attachments; it runs a user-selected `.litertlm` model locally on CPU and presents suggestions. It has copy/share/read-aloud controls.

The structure is deliberately simple, but almost all UI, document handling, speech, model lifecycle, prompt construction, and inference are concentrated in `MainActivity.kt`. There are no automated tests, no Gradle Wrapper, no dedicated app resources, and no app-level architecture layer. The latest GitHub Actions debug APK build succeeded on this audited commit (`45342681795`); local builds cannot run in this workspace because Java and Gradle are not installed. No emulator or physical-device QA was available in this audit.

**Release-integrity issue to address before distributing source bundles:** the tracked `PocketAgent.zip` is stale. Inspection of its contents confirms it contains the removed `AgentService.kt`, an AccessibilityService declaration, and the old screen-control implementation. The current source tree and current manifest do not contain that service. Do not use or distribute that ZIP as a copy of the current app; regenerate it from the current safe source or remove it after an approved packaging change. This is especially important because the product decision is to keep PocketAgent manual-only and not restore Accessibility-based control.

## 1. Repository and architecture

### Project structure

| Path | Purpose / audit note |
| --- | --- |
| `app/src/main/java/com/example/pocketagent/MainActivity.kt` | The only app source file. Builds all Views in code and owns model selection/import, text attachment parsing, dictation, inference, response actions, status and lifecycle cleanup. |
| `app/src/main/AndroidManifest.xml` | Declares the launcher Activity, microphone permission, and speech-recognition service query. No AccessibilityService and no `INTERNET` permission in the current manifest. |
| `app/build.gradle.kts` | Android app module; LiteRT-LM Android dependency. |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` | Root AGP/Kotlin versions, repositories, project inclusion, AndroidX and JVM memory configuration. |
| `.github/workflows/build.yml` | CI builds a debug APK with Java 17 and Gradle 8.14.4 and uploads the APK artifact. Failure diagnostics are also emitted in the workflow summary/annotations. |
| `README.md` | Setup, limitations and build instructions; one older button label (“Dictate goal”) does not exactly match the current UI (“Dictate”). |
| `DEVELOPMENT_ROADMAP.md` | Earlier milestone notes. This audit supersedes its brief status statements with a task-led roadmap. |
| `PROJECT_AUDIT.md` | This audit. |
| `PocketAgent.zip` | Tracked source archive; stale and contains pre-redesign AccessibilityService/control code. |

There are no `app/src/test` or `app/src/androidTest` directories, resources-based layouts/themes, navigation graph, database, repository/service classes, ViewModel, or Gradle Wrapper.

### Build and dependency configuration

- Android application; Kotlin/JVM 17.
- Android Gradle Plugin `8.7.3`; Kotlin Android plugin `2.4.0`.
- `compileSdk 35`, `targetSdk 34`, `minSdk 28`, app version `1.0` / version code `1`.
- Direct runtime dependency: `com.google.ai.edge.litertlm:litertlm-android:0.19.1`.
- CPU backend is selected by the app. The model must be supplied separately as a compatible `.litertlm` file.
- The manifest declares `RECORD_AUDIO`; Android's Storage Access Framework is used for file selection, so broad storage permission is not requested.

### Current data flow

1. User types a task, taps Dictate, or selects text file(s) through the Android document picker.
2. Dictation is handled by the device's configured Android speech-recognition provider. `EXTRA_PREFER_OFFLINE` is a preference, not a guarantee that the provider runs offline.
3. Selected text/Markdown/CSV content is read on the worker thread and capped at 12,000 characters across the attachment context.
4. The prompt and attachment text are sent to a LiteRT-LM `Conversation` with a system instruction. The model is initialized lazily from `filesDir/model.litertlm`, using CPU inference and `cacheDir`.
5. A complete response is posted to the UI. The user can copy, share, select, or ask Android TextToSpeech to read it aloud.

Model and transient app data stay in app-private storage. There is no conversation database or memory store. The app itself has no declared Internet permission or cloud-provider implementation; the external speech-recognition provider remains a separate privacy boundary.

## 2. Existing feature inventory

“Implemented” below describes code presence. It does not imply full real-device verification.

| Feature | Status | Evidence / limitation |
| --- | --- | --- |
| Manual task suggestions | Implemented; user reports app runs | Prompt explicitly says suggestions only; no screen observation or execution in current source. Model quality/output formatting has not been independently evaluated. |
| Local `.litertlm` selection/import | Implemented; user reports model loaded | SAF picker copies the chosen file into private storage. It checks only that the copy is non-empty; it does not validate extension, compatibility, integrity, file size, or available storage before copying. |
| CPU local inference | Implemented; CI builds | Engine lazily initializes on first request. No performance or memory measurements on target hardware. |
| Typed prompt | Implemented | One multiline `EditText`; no persistent conversation or prompt history. |
| Live dictation | Implemented in code | Uses Android `SpeechRecognizer`; requests microphone permission. Offline preference depends on provider; dictated text is inserted for user review. |
| Text attachments | Implemented in code | Multiple text MIME files, Markdown and CSV; content is bounded to 12,000 chars and can be removed. No PDFs, images, audio/video files, or citation/source highlighting. Runtime file-picker behavior not device-tested. |
| Answer copy/share | Implemented in code | Explicit user action; share target may transmit content outside the app. |
| Answer read-aloud | Implemented in code | Uses system TextToSpeech; available voices/languages depend on device. Not device-tested. |
| Model replacement | Implemented in code | Closes cached engine after import. Existing model is deleted before the temporary copy is renamed, so a failed replacement can leave no model. |
| Model switching/status | Partial | UI shows selected model size/readiness, not actual model identity, backend, capabilities, or compatibility. |
| Image understanding | Not implemented | UI/runtime pass text only; current Gemma 3 1B INT4 use is text-first. A different model alone would not enable image input. |
| Audio-file transcription/understanding | Not implemented | Live dictation is provider-based speech recognition; no file-based ASR path. |
| PDF/document extraction | Not implemented | No PDF parsing/indexing or document retrieval. |
| Conversation history/memory | Not implemented | Activity fields only; no opt-in persistent memory, edit/delete UI, or history. |
| Tool calling / autonomous workflows | Intentionally absent | Current product is manual-only. Do not add Accessibility-based screen reading/control or automatic execution; that conflicts with the user's earlier explicit direction. |
| Automated tests | Not implemented | No unit, integration, or UI test source sets. |
| Release/source archive consistency | Broken | `PocketAgent.zip` is confirmed stale and contains the removed AccessibilityService/control implementation. |

## 3. Technical audit: defects, risks, and debt

### High-confidence issues

1. **Stale unsafe source archive (`P0`, release integrity):** `PocketAgent.zip` includes `AgentService.kt`, which extends Android `AccessibilityService`, reads screen nodes and issues actions, and its archived manifest registers that service. The live source tree does not. This contradicts the current manual-only product and can mislead a user building from the archive. Keep it out of release/distribution until regenerated or removed.
2. **No automated regression tests (`P1`):** model import, bounded attachment reading, permission flows, error states and answer actions have no test evidence. Current CI proves compilation/package assembly only.
3. **Single 500+ line Activity (`P1` maintainability):** UI construction, model/runtime, file parsing, speech, permissions, clipboard, share, TTS and lifecycle are coupled. A bug or feature change in one area is hard to isolate and test.
4. **Weak model import validation (`P1` reliability):** accepts arbitrary openable files, has no maximum file size or free-space check, and deletes the current model before the replacement is safely finalized. Failed copy/import can strand a partial or missing model.
5. **Misleading privacy badge risk (`P1` privacy UX):** “PRIVATE · ON-DEVICE” is broadly true for LLM inference, but speech recognition is delegated to the system provider and may not be offline. The UI/README disclose the provider dependency, but the badge could imply every stage is local.
6. **State is Activity-scoped (`P1` UX/reliability):** prompt and selected attachment context are not saved across Activity recreation/process death. Model file persists; in-memory task/attachments do not.
7. **No inference cancellation or streaming (`P2` usability/performance):** a single-thread executor performs full inference and only updates UI with a generic thinking state; users cannot stop a slow generation or see partial output.
8. **Model capability not described/validated (`P2`):** file size alone is shown; no model metadata, architecture, supported modalities or compatibility preflight is available.
9. **Limited UI accessibility verification (`P2`):** custom clickable TextViews replace buttons; touch target, TalkBack labels, focus order, font scaling, contrast and landscape behavior have not been tested. There is no resource-based dark theme.
10. **README/archive drift (`P2`):** README contains a stale dictation button label, while the zip is materially out of date.

### Security and privacy review

- Positive: manual-only boundary is encoded in the system prompt and current app has no AccessibilityService or Internet permission. Files are chosen through SAF. Microphone is requested only on dictation. No telemetry, remote API, or long-term memory implementation was found.
- Limits: a system prompt is not an enforceable security boundary; model output must still be reviewed. Attachment text is labelled untrusted, but prompt-injection resistance is probabilistic.
- Model binaries are untrusted inputs. The app should bound size, preserve the existing valid model on failed replacement, surface errors clearly, and document trusted model sources. Do not embed tokens or fetch gated model weights from the app.
- Speech provider may use network according to its own settings. Clearly distinguish local LLM inference from speech-recognition privacy.
- Copy/share are user-initiated but can move generated text outside PocketAgent; no extra transmission occurs automatically in this code.
- The stale archive is the most urgent product/security inconsistency. Do not restore its AccessibilityService implementation.

### Performance review

- A single-thread executor keeps model work off the UI thread and serializes import/inference, which is a useful baseline.
- The model is loaded lazily and retained in memory, avoiding reload per request. No streaming, cancellation, inference profiling, memory guard, or explicit model unload control exists.
- `largeHeap=true` is enabled globally without measured need; it may postpone memory failures rather than solve them.
- Future multimodal models/files may be several GB; storage and peak RAM limits must be measured on target phones before recommending them.
- No device, cold-start, sustained inference, battery, or memory benchmarks were run.

## 4. Architecture assessment and improvement plan

### Assessment

The current architecture is appropriate for a prototype's one-screen, local-first scope but is not yet a robust foundation for multiple modalities. `MainActivity` is a “god Activity” with direct Android, LiteRT-LM, and storage dependencies. There is no test seam around inference, input extraction, or state. Avoid a broad Compose/MVVM migration until tests and a concrete need justify it.

### Proposed incremental architecture (not yet implemented)

1. **UI layer:** keep the current platform View UI initially. Extract view construction and state rendering from `MainActivity` only as features/tests require. Introduce a small immutable screen state/reducer or ViewModel after adding lifecycle/state tests.
2. **Input layer:** separate interfaces for `TaskInput` and `AttachmentExtractor`. Start with current plain-text extraction; add PDF/OCR/audio-specific extractors as separate opt-in capabilities. Each extractor returns bounded text and source metadata, not opaque file bytes silently injected into a text model.
3. **Model layer:** wrap LiteRT-LM in a `ModelSession`/provider interface with initialize, capability metadata, generate, cancel-if-supported, and close. Keep the existing CPU provider as the first implementation; do not add remote/cloud providers without explicit product approval.
4. **State/storage layer:** keep selected model in private storage. Add atomic import/rollback and model metadata. Do not add long-term memory until the user approves explicit opt-in, visibility, editing, deletion and data-retention behavior.
5. **Safety boundary:** the assistant returns suggestions only. Any future read/prepare/confirm/restricted tool scheme must remain a separate, explicitly approved feature; autonomous screen inspection/control stays out of scope.
6. **Error handling:** define user-visible outcomes for unsupported model, bad/corrupt file, storage full, permission denied, speech unavailable, inference failure, cancellation and empty output. Preserve the previous valid model if replacement fails.
7. **Tests:** inject a fake model session/extractor into unit-testable orchestration; use instrumented tests for SAF, microphone/TTS, accessibility and lifecycle behavior where emulator/device support exists.

## 5. Product brainstorm and prioritization

Priority is based on user value, reliability, feasibility and privacy, not novelty.

### P0 — Release integrity / blockers

- **AUD/SEC-001: Remove or regenerate stale `PocketAgent.zip`.** Prevent old AccessibilityService code from being mistaken for the current app. Acceptance: archive either omitted or regenerated from current safe source; scan archive for `AccessibilityService`, `AgentService`, screen-control actions and stale manifest entries; current source remains manual-only.

### P1 — High value

- **QA-001: Add regression tests and CI quality gates.** Cover import success/failure/rollback, attachment character bounds, empty input, missing model, inference failure and state restoration where feasible.
- **AI-001: Harden model import and readiness.** Validate file size/extension where provider metadata allows, check free space, copy atomically, preserve previous model on failure, expose meaningful loading/error/retry state.
- **UX-001: Accessibility and privacy clarity.** Audit screen reader semantics, touch targets, contrast, font scale and device sizes; label speech provider as potentially external rather than implying every operation is local.
- **AI-002: Improve response UX and control.** Add structured/streaming output only if LiteRT-LM API supports it; provide cancel if supported and ensure status/scroll behavior works.

### P2 — Medium priority

- **DOC-001: PDF/text document support.** Extract bounded text, show filename/page/source references, handle scanned PDFs explicitly with OCR, and do not send unbounded document content.
- **VOICE-001: Audio-file transcription.** Add an explicitly selected on-device ASR path or disclose provider data flow; reuse resulting transcript with current text model.
- **STATE-001: User-controlled conversation history.** Opt-in local history, clear/delete controls, no automatic long-term memory. Add migration/retention tests before persistence.
- **UX-002: Preserve draft/task state across rotation and process recreation.** Save only user-visible ephemeral state; keep sensitive prompt retention transparent.

### P3 — Advanced / experimental; only after approval

- **VISION-001: Image understanding.** Requires an image-capable `.litertlm` model, compatible LiteRT-LM Android API path, image preprocessing, size limits, user preview/removal and device memory/performance testing. A model swap alone is insufficient.
- **TOOL-001: Safe local tools (e.g. calculator, user-selected file summarization).** Strictly separate read/prepare/confirm/restricted actions. No accessibility-driven phone control; do not add web/cloud tools by default.
- **MODEL-001: Model catalog/capability UI.** Only after formats, licenses, hardware and runtime compatibility can be validated; do not bundle multi-GB files in the APK.

## 6. Simulated specialist review

No native sub-agent spawning capability is available in this workspace. The following are **role-separated simulated reviews by the single available agent**, not independent agent executions. Findings were checked against repository files and CI evidence.

| Specialist lens | Findings | Confidence |
| --- | --- | --- |
| Repository Auditor | One Activity, one external runtime dependency, no tests, stale zip. | High; directly inspected. |
| System Architect | Split UI/input/model boundaries incrementally; avoid wholesale rewrite. | High recommendation; not implemented. |
| Product Strategist | Fix packaging integrity and test/loader reliability before advanced modalities. | High product priority. |
| UI/UX Specialist | Improve accessibility, loading/errors, provider privacy wording; avoid unnecessary redesign. | Medium; no device visual QA. |
| AI Engine Specialist | CPU LiteRT-LM path works at build/code level; no multimodal bytes are sent; need capability metadata and safe import. | High from source; hardware compatibility remains unknown. |
| Memory/Knowledge Specialist | No persistent memory/document retrieval; keep future persistence opt-in and deletable. | High from source. |
| Tooling/Agent Specialist | No tools/autonomous execution in live code; archived prototype does have it. Preserve manual-only policy. | High from source. |
| Implementation Reviewer | A minor compile defect was caught by CI and fixed (`textButton` called with an unsupported color argument). Follow-up build passed. | High from Actions. |
| QA Specialist | CI assemble is green; no unit/UI/device tests. Build success is not behavioral verification. | High. |
| Security/Privacy Specialist | SAF and least-privilege permissions are positive; stale archive, binary validation, prompt injection and speech-provider disclosure are key risks. | High for archive/permissions; medium for runtime threat exposure. |
| Performance Specialist | No measurements; model memory/cold start and `largeHeap` need measurement before multimodal expansion. | High on missing evidence; impact estimate is provisional. |

## 7. Baseline and evidence

- **Git state:** clean at audit start on `arena/85d065bd-pockagent`, commit `b86f420`, based on `main` `f579f52`.
- **CI build:** GitHub Actions run `45342681795` completed successfully for this branch/commit; `assembleDebug` and APK artifact upload both passed. Artifact name: `PocketAgent-debug-apk`.
- **Earlier failure and fix:** CI caught a Kotlin compile error because `textButton` was called with an extra color argument. That call was corrected in `b86f420`; the subsequent build passed. A separate TTS language call was also made explicit via `setLanguage`.
- **Local build:** not run; this workspace has no `java` or `gradle` executable and no Gradle Wrapper.
- **Automated tests/lint:** none exist, so no test suite or lint result can be claimed.
- **Manual/device tests:** not run in this audit. The user previously reported that the app launches and the model loads, but that is user-reported rather than a QA run against this exact APK.
- **Diff hygiene:** `git diff --check` passed; no audit-time application code edits were made.

## 8. Risks, blockers, and recommended first milestone

### Risks

- The stale zip can reintroduce behavior intentionally removed from the live app.
- No automated tests make changes difficult to verify.
- Speech provider's offline/network behavior varies by device and is not guaranteed by the request extra.
- Model import can consume excessive storage and can destroy the prior model on replacement failure.
- Multimodal models can exceed device memory/storage; selected format/runtime compatibility must be proven.

### Blockers

- No sub-agent runner, local Android SDK/Java/Gradle or emulator in this workspace. CI is available and green for the audited commit.
- Real-device interaction and performance QA requires a physical Android device or emulator.
- Before packaging/distribution, stale ZIP disposition needs to be agreed (remove versus regenerate); either choice must preserve the manual-only design.

### Recommended first milestone: M0 — Safe, verifiable baseline

1. Resolve the stale `PocketAgent.zip` release-integrity issue without restoring old behavior.
2. Add focused tests for bounded text extraction and model import transaction/rollback; extract only the minimum pure logic needed for tests.
3. Improve model import validation and user-visible failure/retry states.
4. Review privacy language for speech recognition and run accessibility checks on the current UI.
5. Keep the CI build green and require a test result before accepting follow-on modality work.

**Acceptance:** source bundle is confirmed current/manual-only; tests pass for import and attachment bounds; debug APK builds on CI; privacy/accessibility limitations are documented; no autonomous screen control is present. Do not start image/audio-file integration or long-term memory before M0 is reviewed and approved.

## 9. M0 follow-up (after the audit snapshot)

M0 was approved by the user. On the active feature branch after audit commit `b86f420`, these scoped changes were made:

- Regenerated `PocketAgent.zip` from the current tree and added `scripts/package_source.py` to create/check the bundle. The checker rejects legacy AccessibilityService source/manifest registrations; local `--check` passed.
- Extracted bounded text reading and model-file import into small testable helpers. Model imports are capped at 5 GiB, reserve free space, stream to a temporary file, sync before replacement, and atomically replace/rollback so empty, over-limit, storage-limited or interrupted imports do not intentionally delete the prior complete model.
- Added nine JUnit tests for bounded text reads and model import success, empty/oversized/insufficient-space/stream-failure cases and limit validation.
- Clarified that the model inference is local while dictation's offline/network behavior depends on the device speech service.
- CI run `45347145665` passed source-archive verification, `testDebugUnitTest`, `assembleDebug`, and APK artifact upload for commit `223bd31`.

**M0 is not fully closed:** no emulator or physical-device permission, microphone, TalkBack, layout, model-format, or inference-failure checks were available. Those checks remain tracked as `QA-DEVICE-001`. These follow-up changes are on the active branch and are not yet merged into `main`.
