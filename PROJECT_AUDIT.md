# PocketAgent Project Audit

_Updated: 2026-11-24 (branch `arena/925d477f-pockagent`, base `ee6cd79` on `main`)._
_This is a code-grounded audit snapshot. It replaces stale earlier claims where they conflict with the current tree._

## Verified snapshot

| Area | Current state |
|---|---|
| App | Android app, Kotlin, `minSdk 28`, `targetSdk 34`, `compileSdk 35`, `applicationId com.example.pocketagent` |
| Inference | `com.google.ai.edge.litertlm:litertlm-android:0.20.0` (bumped from 0.19.1 in this pass) |
| Main UI path | `MainActivity.kt` (~4.1k lines) is the live path; `MainViewModel.kt` exists but is not referenced by `MainActivity` |
| Tests | 37 JVM unit test files under `app/src/test` (no instrumented tests in repo) |
| CI | `.github/workflows/build.yml`: source bundle check, `testDebugUnitTest`, `assembleDebug` |
| Network | No `INTERNET` permission declared in manifest |
| Model import | Private app storage, transactional copy, 5 GB cap, basic header/sanity check added in this pass |

## What is good / keep

- Offline-first posture: no INTERNET permission, local model import, local session/note/memory stores.
- Answer-first prompt shaping (`AnswerDirective`), runaway/repetition guard, confirmation-before-action patterns.
- Local RAG primitives (chunking, BM25 keyword index, citation packaging), session history, personal notes.
- Biometric/device-credential lock, privacy audit/wipe flows, backup/data extraction disabled.
- Broad JVM test coverage for pure logic/helpers (though live-path confidence is limited).

## Key gaps / risks (verified)

### P0 - Correctness / reliability

1. **Small-context budget honesty (partially addressed in this pass)**
   - Defaults were too large for 4K-class models. This pass set `TokenBudget` defaults to `4096/1024/384`, `MainUiState.MAX_ATTACHMENT_CHARS=6000`, `MAX_RESPONSE_TOKENS=1024`, `RunawayGuard.MAX_ANSWER_CHARS=12000`, warm-context reset threshold to 2800.
   - `buildConversationalPrompt/buildGemmaTurnsPrompt` now reserve `max(estimatedSystemTokens, reservedSystemTokens)` so out-of-band system instructions cannot over-promise history space.
   - `pruneHistory` now trims the newest turn if it alone exceeds available budget (instead of silently overflowing).
   - **Remaining**: budgets are still static defaults, not model-capability-aware. Actual model context/runtime/device RAM can still be tighter.

2. **Conversation/session state bleed risk on multimodal/Omni models**
   - Engine/conversation reuse is keyed by session/model/system/attachment hash. This is good for warm-cache reuse but must be validated against LiteRT-LM+model behavior, especially Omni/linear-attention state.
   - Dependency bumped to `litertlm-android:0.20.0` in this pass; **device/CI validation required**.
   - **Remaining**: add explicit device smoke test for cross-session state isolation and context overflow behavior.

3. **Decoding quality / loop control**
   - Added conservative `SamplerConfig` (topK=40, topP=0.95, temperature=0.8) in conversation creation (best-effort, guarded). `RunawayGuard` remains secondary.
   - **Remaining**: expose sampling controls carefully; validate API compatibility with LiteRT-LM 0.20.0 in CI.

4. **Lifecycle / memory pressure**
   - Added `onTrimMemory`/`onLowMemory` handling and 120s idle engine unload in `MainActivity`.
   - **Remaining**: foreground-service-backed generation for long runs, thermal throttling awareness, explicit RAM preflight before loading large Omni models.

5. **Model integrity**
   - Added minimum-size and basic header/magic sanity checks in `ModelFileStore`.
   - **Remaining**: model capability registry (context, modalities, license, SHA-256/hash pinning), corruption detection beyond header, user-visible model metadata.

### P1 - Architecture / verification

- **God Activity / dead parallel path**: `MainActivity` is 4103 lines and owns UI+inference+state. `MainViewModel`/`MainUiState` have logic/tests but `MainViewModel` is not wired into `MainActivity` - false confidence risk.
- **Test confidence gap**: 37 JVM unit test files; 0 instrumented tests; no lint gate; some tests validate dead or non-live paths.
- **Release hygiene**: `applicationId` placeholder-like, `targetSdk 34`, `versionCode 1`, no launcher icon resources, no Gradle wrapper jar, R8/minify off, no signing config, no lint/detekt baseline.
- **Docs drift**: README previously claimed Gemma 3 1B/text-only/old workflow details; roadmap/audit stale. Updated in this pass.
- **Hand-rolled JSON** in stores increases maintenance/bug risk; acceptable short-term but should be hardened or migrated carefully.

### P2 - Product gaps

- **Omni underutilized**: no end-to-end image input, no model audio input, no model TTS path; Whisper/Android STT/TTS remain separate.
- **Model UX**: manual sideload only; no catalog/download/resume/hash/license/benchmark/RAM preflight.
- **Agent/actions**: action extraction is limited; no native tool-calling schema, no action log/undo surface beyond confirmations.
- **Retrieval quality**: BM25 only; no semantic embeddings/vector index.
- **Localization/accessibility/release**: no `strings.xml` localization strategy, no i18n, no instrumented accessibility checks.

## Changes made in this pass

- Bumped LiteRT-LM dependency to `0.20.0`.
- Added conservative `SamplerConfig` to conversation creation.
- Made context budgeting honest for 4K-class models: defaults `4096/1024/384`, attachment cap `6000`, response cap `1024`, runaway char cap `12000`, warm reset `2800`.
- Fixed budget reservation when system instruction is out-of-band.
- Trim newest turn when it alone exceeds available budget.
- Added basic `.litertlm` import sanity checks (min size/header).
- Added `onTrimMemory`/`onLowMemory` handling and 120s idle engine unload.
- Updated unit tests for new constants/behaviors (`MainUiStateTest`, `ModelFileStoreTest`, `ContextBudgetManagerTest`).
- Refreshed README/roadmap/audit docs.

## Validation status

- **Not run in this environment**: `gradle --no-daemon testDebugUnitTest assembleDebug lintDebug`, instrumented/device tests.
- CI workflow should be used as the source of truth for build/test execution.
