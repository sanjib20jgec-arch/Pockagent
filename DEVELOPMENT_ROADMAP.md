# PocketAgent Development Roadmap (Corrected)

_Updated: 2026-11-24. Evidence-based reconciliation of branch `arena/925d477f-pockagent` (HEAD `aea1832`, aligned with `origin/arena/925d477f-pockagent` at audit time). This roadmap replaces ambiguous completion claims with verifiable status._

## Status taxonomy

| Status | Meaning |
|---|---|
| **Proposed** | Idea/design only; not approved for implementation. |
| **Approved** | Approved for implementation; no code yet. |
| **In Progress** | Actively being implemented. |
| **Implemented** | Code/config present in repo and/or wired; not yet verified by CI/device. |
| **CI Verified** | Passing CI evidence on current head. |
| **Device Verified** | Verified on real device/emulator with recorded evidence. |
| **Completed** | Acceptance criteria met with evidence (CI/device/review as required). |
| **Blocked** | Cannot proceed due to dependency, failure, or unresolved risk. |
| **Out of Scope** | Not in approved scope for this roadmap phase. |

**Rules:** CI is not device testing. Code presence is not verified functionality. No "Completed" claim without acceptance evidence.

## Current baseline (verified)

| Area | Evidence |
|---|---|
| Branch/HEAD | `arena/925d477f-pockagent` @ `aea1832` (synced with origin during this audit) |
| CI | `.github/workflows/build.yml` runs `package_source.py --check`, `testDebugUnitTest`, `assembleDebug`. Latest branch runs: `45516167988` (aea1832, failure), `45515134152` (e4060c3, failure), `45514135744` (e68f0af, failure). **No CI Verified claim for current head.** |
| App config | `compileSdk 35`, `minSdk 28`, `targetSdk 35`, `versionCode 2`, `versionName 1.1.0-dev`, `applicationId com.example.pocketagent`, NDK 27.2.12479018, CMake 3.22.1, Java/Kotlin 17, `isMinifyEnabled=false`. |
| Source/tests | 42 main Kotlin files (~15,426 LOC), 37 JVM test files, 0 instrumented tests (`androidTest` absent). |
| Runtime posture | No `INTERNET` permission declared; `RECORD_AUDIO` declared; SpeechRecognizer/TTS provider behavior is device/provider-dependent and must not be described as fully offline app-controlled. |
| Architecture | Live path is `MainActivity.kt` (~4,147 LOC). `MainViewModel.kt` (~1,219 LOC) is not referenced by `MainActivity` (dead parallel path / false-confidence risk). |

## Guiding constraints

- **Offline-first / privacy-first:** no INTERNET permission; no telemetry; user data stays local.
- **Small-device reality:** 4–6GB RAM devices are targets; large Omni models have high RAM/context risk. Design for graceful degradation.
- **Honest capability UX:** never imply context window, modality, or live capability the loaded model/runtime cannot provide.
- **Verification discipline:** distinguish Implemented / CI Verified / Device Verified / Completed.
- **Confirmation-only actions:** system intents/actions require user review/confirmation; autonomous execution is Out of Scope.

## Milestone M1 — Trustworthy Green Baseline (Recommended next)

**Goal:** establish a clean, reproducible, evidence-backed baseline before new feature work.

| ID | Task | Status | Acceptance criteria | Evidence / Gap |
|---|---|---|---|---|
| M1-1 | Reconcile git baseline | Implemented (synced during audit) | Local branch clean and aligned with intended remote tip; no ambiguous dirty state | Synced to `aea1832` during audit; keep clean going forward |
| M1-2 | Make current head CI green | In Progress | Green CI run URL for `package_source.py --check`, `testDebugUnitTest`, `assembleDebug` | Previous runs failed; root cause identified as leftover conflict markers in UserMemoryStore/PersonalNotesStore; fix pushed, awaiting CI |
| M1-3 | Correct roadmap/audit status taxonomy | Implemented | Docs use evidence-based statuses; no ambiguous `[x]=completed` | This doc + PROJECT_AUDIT update |
| M1-4 | Record CI failure root cause and fix | In Progress | Failure log identified, fix committed, green rerun | Check-run annotations revealed leftover merge conflict markers in `UserMemoryStore.kt:316` and `PersonalNotesStore.kt:319` (pre-existing in ee6cd79); removed in fix commit; awaiting green CI |
| M1-5 | Baseline verification note | Proposed | README/CI badge or doc note states latest verified commit/run | Add after green CI |

**M1 exit criteria:** M1-1 to M1-4 completed with evidence. No new feature scope added.

## Phase 1 — Correctness & Safety (P0)

| ID | Item | Status | Evidence | Gap / Next |
|---|---|---|---|---|
| P1-1 | Honest 4K-class context defaults | Implemented; CI Unverified; Device Unverified | `ContextBudgetManager` 4096/1024/384; `MainUiState` 6000 chars / 1024 response; `RunawayGuard` 12000; warm reset 2800 | Make model-capability-aware; device validation |
| P1-2 | Reserve out-of-band system budget | Implemented; CI/Device Unverified | `ContextBudgetManager` uses `max(estimatedSystemTokens, reservedSystemTokens)`; test added | CI green required |
| P1-3 | Trim newest turn on overflow | Implemented; CI/Device Unverified | `pruneHistory` trims latest turn attachment/content; test added | CI green required |
| P1-4 | LiteRT-LM 0.20.0 bump | Blocked/Reverted | Attempted 0.20.0; reverted to 0.19.1 after CI compile failure (exact log unavailable in sandbox; suspected classfile/toolchain incompatibility) | Retry only with verified toolchain-compatible release and green CI |
| P1-5 | SamplerConfig stability tuning | Partial / Blocked | Constants present in `InferenceEngineManager`; ConversationConfig wiring deferred after compile-risk; no active wiring | Verify LiteRT-LM API for target version and wire safely behind CI |
| P1-6 | Conversation/session state isolation | Partial | Conversation cache keyed by session/model/system/attachment hash | Device smoke test for Omni/state bleed; fallback strategy |
| P1-7 | Lifecycle memory handling | Implemented; CI/Device Unverified | `MainActivity.onTrimMemory/onLowMemory`, 120s idle unload | Foreground service, RAM preflight, thermal handling |
| P1-8 | Model import sanity checks | Implemented; CI/Device Unverified | `ModelFileStore` min-size/header check; tests | Hash/manifest/capability registry still needed |
| P1-9 | Model capability-aware budget/registry | Proposed | — | Approved design needed before implementation |
| P1-10 | Foreground service / RAM preflight / thermal | Proposed | — | Android 14+ FGS compliance; device testing |

## Phase 2 — Verification & Release Hygiene (P1)

| ID | Item | Status | Evidence | Gap / Next |
|---|---|---|---|---|
| P2-1 | JVM tests for recent P0 changes | Implemented | `MainUiStateTest`, `ModelFileStoreTest`, `ContextBudgetManagerTest` updated | CI still failing on head |
| P2-2 | CI green baseline | Blocked | Workflow exists; recent runs failing | Diagnose/fix/log access |
| P2-3 | Lint/detekt/static analysis gate | Proposed | — | Start non-blocking, then gate |
| P2-4 | Instrumented smoke tests | Proposed | No `androidTest` | Add minimal device smoke harness |
| P2-5 | MainViewModel dead-path decision | Proposed | `MainViewModel` not referenced by `MainActivity`; tests exist | ADR: wire incrementally or deprecate/remove duplication |
| P2-6 | TargetSdk/version bump | Implemented (config); CI/Device Unverified | `targetSdk 35`, `versionCode 2`, `versionName 1.1.0-dev` | CI/device behavior check |
| P2-7 | Adaptive launcher icon | Implemented; CI Unverified | `mipmap-anydpi-v26`, `drawable/ic_launcher_foreground.xml`, `values/colors.xml`, manifest refs | Verify build/resource packaging in CI |
| P2-8 | Release hygiene bundle | Proposed | — | applicationId decision, strings/i18n, R8, signing, wrapper/checksum, license audit |
| P2-9 | Hand-rolled JSON hardening/migration plan | Proposed | Session/UserMemory/PersonalNotes stores custom JSON | Evaluate kotlinx.serialization/Moshi migration behind interfaces |

## Phase 3 — Model Capability & Omni UX (P2, approval-gated)

| ID | Item | Status | Notes |
|---|---|---|---|
| P3-1 | Model capability registry/pool | Proposed | Must include context/modalities/RAM/license/hash; no INTERNET/privacy regression |
| P3-2 | Image input end-to-end | Proposed | Not implemented; requires capability gating |
| P3-3 | Omni audio-in path | Proposed | Not implemented; compare with Whisper before retiring |
| P3-4 | Model/offline TTS strategy | Proposed | Provider-dependent caveat required; evaluate Omni TTS feasibility separately |
| P3-5 | Native tool-calling + action log/undo | Proposed | Confirmation-only invariant remains mandatory |
| P3-6 | Semantic retrieval / memory review | Proposed | No silent auto-fact writes without user control |

## Out of scope (current)

- Autonomous device control / AccessibilityService automation.
- Claims of cloud-free behavior for third-party Android speech/TTS providers.
- Any feature requiring INTERNET permission without explicit product/privacy approval.
- New feature implementation during roadmap reconciliation.

## Evidence gaps / risks (must remain visible)

- Current head is **not CI verified** (latest 3 branch runs failed).
- No **device verification** exists (0 instrumented tests).
- `MainActivity` is a 4147 LOC God Activity; `MainViewModel` is dead parallel path.
- Model budgets are static 4K defaults, not capability-aware.
- LiteRT-LM 0.20.0 bump was reverted to 0.19.1 after CI compile failure; SamplerConfig wiring remains unverified/deferred.
- Large Omni models carry RAM/thermal/process-death risk on 4–6GB devices.

## Next 5 tasks (recommended)

1. **M1-2/M1-4:** Get CI green on current head; capture exact failure log and fix.
2. **M1-1 hygiene:** Keep git baseline clean/aligned; avoid dirty/diverged working state.
3. **P2-3:** Add minimal static/lint diagnostics in CI (non-blocking initially).
4. **P2-5:** Decide/wire MainViewModel live-path strategy or isolate dead path.
5. **P1-9:** Draft model capability registry/budget contract before Omni expansion.
