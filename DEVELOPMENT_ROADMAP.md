# PocketAgent Development Roadmap

This is the persistent task ledger for the current product direction. It is grounded in the repository review in [`PROJECT_AUDIT.md`](PROJECT_AUDIT.md). The user approved M0 after reviewing the audit; M0 is now in progress. Later milestones still require separate review.

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

- Audit baseline: `b86f420`; M0 implementation commit: `ab12ad9`; M1 UI commit: `eb0668b`; PDF implementation commit: `5ad1201` with CI compile-fix follow-up `d9e5c83` on `arena/85d065bd-pockagent`; base `main` is `f579f52`.
- GitHub Actions run `45351718497` passed source-archive verification, `testDebugUnitTest`, `assembleDebug`, and APK artifact upload for `d9e5c83`.
- Twelve unit tests cover bounded text reading, PDF staging size/cleanup, and model import success/failure/rollback. No Android UI/instrumented tests, lint, or device/emulator tests exist. Local build remains unavailable (no Java/Gradle executable or wrapper).
- User-reported: app launches and loads the Gemma 3 1B INT4 model; generated output/UI need improvement.
- Current app supports typed prompts, live system speech-to-text, bounded text/Markdown/CSV/PDF attachments, local CPU inference, and answer copy/share/TTS. The PDF slice passed CI; device/runtime validation remains open. Image input, imported audio transcription, chat history and tool execution are not implemented.
- The stale source ZIP has been regenerated. The guard verifies current files and rejects active AccessibilityService/control code; local and CI archive checks passed.

## Task ledger

| ID | Task | Owner lens (simulated) | Status | Dependencies | Risk |
| --- | --- | --- | --- | --- | --- |
| AUD-001 | Repository, feature, privacy, build and architecture audit | Repository Auditor + QA + Security | **COMPLETED** | None | Low; report in `PROJECT_AUDIT.md` |
| ARC-001 | Confirm incremental architecture and dependency boundaries | System Architect | **COMPLETED (M0 scope)** | AUD-001 | Narrow pure helpers; no broad rewrite |
| SAFE-001 | Regenerate stale source ZIP; verify no legacy service/control remains | Security + Release | **COMPLETED** | AUD-001; M0 approved | Current archive checker and CI pass; no legacy implementation files in bundle |
| TEST-001 | Add focused unit tests for bounded text extraction and import rollback | QA + Implementation | **COMPLETED** | ARC-001; small testable seam | Nine JUnit cases passed in CI run 45346913699 |
| AI-001 | Harden model import (bounds, free space, atomic replace/rollback, retry/error states) | AI Engine + Implementation | **COMPLETED (CI/unit scope)** | ARC-001, TEST-001 | Size/free-space guard; atomic replace; previous model retained on tested failures; no device validation |
| UX-001 | Accessibility, responsive layout, and accurate speech-privacy disclosure review | UI/UX + Security + QA | **IN PROGRESS** | AUD-001 | Native button semantics, explicit labels, 48dp minimum controls, live status regions, stronger muted-text contrast implemented; CI passes; device review remains unverified |
| QA-DEVICE-001 | Verify permission, missing/corrupt model, speech, inference and UI flows on Android | QA | **BLOCKED** | M0 app build available; physical device/emulator needed | No device/emulator is available in workspace; user-device checks requested after APK install |
| AI-002 | Model status/capability metadata and inference progress/cancel feasibility | AI Engine + QA | **PROPOSED** | AI-001; LiteRT-LM API review | Medium; no unsupported capability promises |
| DOC-001 | Bounded PDF text extraction with user preview/source attribution | Document/Knowledge + Security | **IN PROGRESS (CI verified; device/runtime validation pending)** | M0 device QA remains open; PDF input/staging and TEST-001 | Medium-high; parser attack surface, decompression, dependency and device testing |
| VOICE-001 | Imported audio transcription | Voice + Security + QA | **PROPOSED (implementation not approved)** | Separate ASR/provider and privacy decision | High; offline/network, model size, language and hardware availability |
| VOICE-FEAS-001 | Research offline imported-audio ASR options | Voice + Security + QA | **IN PROGRESS (research only, user-approved)** | No code/dependency integration | Medium; native binaries, model licensing/size and language coverage |
| VIS-001 | Image input with a compatible multimodal model/runtime | AI Engine + UI/UX + QA + Security | **PROPOSED** | M0 gates, AI-002, model/API/hardware compatibility review | High; memory, preprocessing, model license/size |
| STATE-001 | Optional local conversation history with user deletion controls | Memory/Knowledge + Security + QA | **PROPOSED** | M0 gates; explicit retention approval | Medium-high; data lifecycle and migrations |
| TOOL-001 | Safe local utilities (e.g. calculator) | Tooling + Security + QA | **OUT OF SCOPE** | Requires separate product approval | High if expanded into action/execution |
| AUTO-001 | Accessibility-driven screen inspection/control or autonomous phone actions | Tooling + Security | **OUT OF SCOPE** | Explicitly rejected direction | Prohibited by product constraint |

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

**Objective:** correct release/source integrity, add first regression evidence, protect model and user data before adding more formats. Archive safety, nine unit tests, import hardening, and CI assembly pass; device-level permission/speech/inference/accessibility checks remain unverified and block full M0 closure.

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

**Implemented slice (CI verified):** interactive controls are semantic Android Buttons with explicit accessibility descriptions and pressed-state feedback; minimum target heights are 48dp (56dp for the main action); task input is associated with its visible label; model and status changes use polite accessibility live regions; muted text contrast is strengthened; speech-service privacy copy remains visible.

**Acceptance criteria:** TalkBack can identify controls and state; touch targets and contrast pass review; large text and narrow/landscape layouts do not truncate essential actions; import, listening, generating, failure and ready states are distinguishable; privacy copy clearly separates on-device inference from OS speech-recognition services. Test on emulator and at least one real device if available. **Device-level criteria are still unverified and block M1 completion.**

**Risks:** platform/provider differences; no device QA can be claimed without a device.

### M2 — Documents and audio input (P2) — IN PROGRESS (PDF code and CI verified; device validation pending)

**Objective:** accept user-selected material only through a bounded, inspectable representation the current text model can use. This work is user-approved for selectable-text PDFs only; imported-audio transcription is not approved for implementation.

**Approved scope:** DOC-001 adds local PDF extraction with explicit byte/page/context bounds, page labels, preview/expand/remove controls, and recoverable failures. Scanned-page OCR, other document formats, audio ingestion, and cloud ASR are excluded. VOICE-FEAS-001 may research offline ASR options only; do not add audio dependencies or claim support. Initial candidate: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) describes offline speech processing, has Android example apps, and the repo declares Apache-2.0. This is not an engine selection: model licenses, per-language models, model sizes, Android native/ONNX Runtime footprint, playback/import pipeline, and target-device performance still require review.

**Acceptance criteria:** supported PDF constraints are visible; selected files are staged with a hard byte cap; page count and extracted context are bounded; users can inspect/expand/remove extracted text before inference; source page labels are retained; corrupt, empty, encrypted, oversized, over-page-limit, no-selectable-text, and extracted-text-limit cases fail or truncate clearly; extracted text remains untrusted prompt context. CI and device checks are reported separately. Offline-ASR research reports candidate engine, license, Android integration requirements, model size/language coverage, privacy boundary, and device cost without integrating it.

**Implementation limits currently set:** 20 MiB per PDF; at most 100 pages; at most 12,000 combined attachment characters; PDFBox temporary scratch storage capped at 40 MiB. Extraction is on-device and text-only; scanned-page OCR is not included. These limits still require adversarial/runtime testing on device. The PDFBox-Android fork is Apache-2.0 per its published metadata, but adds Bouncy Castle 1.73 and Kotlin stdlib 2.0.21 transitives; CI resolution/build and dependency/security review remain required.

**Gate note:** M0/M1 device QA remains outstanding. This user-approved PDF slice does not mark those gates or M1 complete.

**Risks:** PDF parser attack surface/decompression, dependency maintenance and APK size, scanned-document OCR quality, and (for future audio) native library/model size, licensing, language coverage, and offline hardware performance.

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

### M5 — Safe local tools (P3, separate product approval) — OUT OF SCOPE FOR NOW

Only consider user-approved, low-risk utilities such as a calculator or selected-file summarizer. Read/prepare/confirm/restricted action classes and explicit confirmation would be mandatory. No general phone automation, AccessibilityService, or destructive operations.

## Implementation and verification workflow

For each approved task: inspect relevant code and dependencies; document a narrow plan; implement the smallest testable change; review the diff for privacy/safety and regressions; run unit/integration/build tests; report device-only limits; update this ledger with evidence. Do not mark tasks complete based on code presence alone.

## Current decision and next gate

**M0's code and CI gates pass; physical-device verification remains blocked.** M1's accessibility implementation and CI pass, but device verification remains explicitly pending. The user has now approved a narrow M2 PDF-only implementation and research-only feasibility work for offline imported-audio transcription. PDF code and CI are verified; device/runtime checks remain pending. Audio implementation, OCR, image input, persistent memory, and tools remain unapproved.
