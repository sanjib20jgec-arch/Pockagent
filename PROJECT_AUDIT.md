# PocketAgent Project Audit (Corrected)

_Updated: 2026-11-24 (evidence-based reconciliation). Branch: `arena/925d477f-pockagent`, HEAD: `aea1832` (aligned with origin during audit). No code/features changed as part of this audit._

## Verified snapshot

| Area | Evidence |
|---|---|
| App config | Android/Kotlin, `compileSdk 35`, `minSdk 28`, `targetSdk 35`, `versionCode 2`, `versionName 1.1.0-dev`, `applicationId com.example.pocketagent`, Java/Kotlin 17, NDK 27.2.12479018, CMake 3.22.1 |
| Inference deps | `com.google.ai.edge.litertlm:litertlm-android:0.19.1` (0.20.0 attempted, reverted due to CI compile failure), `androidx.biometric:biometric:1.1.0`, `pdfbox-android:2.0.27.1`, `kotlinx-coroutines-android:1.8.1` |
| Source/tests | 42 main Kotlin files (~15,426 LOC), 37 JVM unit test files, 0 instrumented/androidTest files |
| Live architecture | `MainActivity.kt` (~4,147 LOC) is live path; `MainViewModel.kt` (~1,219 LOC) is not referenced by MainActivity |
| CI | `.github/workflows/build.yml` runs `package_source.py --check`, `testDebugUnitTest`, `assembleDebug`. Latest branch runs all failed: `45516167988` (aea1832), `45515134152` (e4060c3), `45514135744` (e68f0af). Current head is **not CI verified**. |
| Privacy posture | Manifest declares no `INTERNET` permission; `RECORD_AUDIO` present; backup/data extraction disabled; biometric lock present. SpeechRecognizer/TTS provider behavior may be device/provider-dependent. |
| Repo hygiene | At audit start: local HEAD behind remote with dirty working tree. Synced to `aea1832` before doc correction. Keep baseline clean. |

## Status taxonomy

**Proposed / Approved / In Progress / Implemented / CI Verified / Device Verified / Completed / Blocked / Out of Scope.**  
CI is not device testing. Code presence is not verified functionality.

## Verified feature inventory (summary)

| Feature | Status | Evidence | Gap |
|---|---|---|---|
| Local `.litertlm` import/load/chat | Implemented; CI/Device Unverified | `ModelFileStore`, `InferenceEngineManager`, `MainActivity`, tests present | CI failing on head |
| Backend GPU-preferred/CPU fallback | Implemented; CI/Device Unverified | `BackendPreference`, `InferenceEngineManager` | Device runtime unverified |
| Answer-first prompting | Implemented; CI/Device Unverified | `AnswerDirective`, tests | CI/device |
| Sessions/history | Implemented; CI/Device Unverified | `SessionStore`, `ConversationSession`, tests | Hand-rolled JSON; CI/device |
| User memory fact store | Implemented; CI/Device Unverified | `UserMemoryStore`, `MemoryFactExtractor`, tests | Heuristic local memory, not semantic/verified |
| Personal notes | Implemented; CI/Device Unverified | `PersonalNotesStore`, tests | CI/device |
| Local RAG (BM25/chunk/citation) | Implemented; CI/Device Unverified | `DocumentChunker`, `OfflineKeywordIndex`, `LocalDocumentRetriever`, PDF/text bounds, tests | BM25 only; CI/device |
| Local calculator | Implemented; CI/Device Unverified | `LocalCalculatorTool`, tests | CI/device |
| System intent tools (confirmation-only) | Implemented; CI/Device Unverified | `SystemActionTools` (696 LOC), confirmation invariant, tests | Regex parsing risk; no auto-exec |
| Android STT / Whisper WAV / Duplex prototype | Implemented/Prototype; CI/Device Unverified | `InAppAudioRecorder`, `WhisperNative`, `DuplexVoiceController`, tests | Device/provider dependent |
| Biometric lock | Implemented; CI/Device Unverified | `BiometricLockManager`, tests | OS/device behavior |
| Privacy audit / panic wipe | Implemented; CI/Device Unverified | `PrivacyAuditCenter`, tests | Device/storage behavior |
| Backup/data extraction disabled | Implemented (config); CI/Device Unverified | Manifest + xml rules | — |
| 4K-honest budgeting + overflow trim | Implemented; CI/Device Unverified | `ContextBudgetManager`, `MainUiState`, `RunawayGuard`, tests | Static defaults, not model-aware |
| Model import sanity check | Implemented; CI/Device Unverified | `ModelFileStore` header/min-size, tests | No hash/manifest |
| Lifecycle trim/idle unload | Implemented; CI/Device Unverified | `MainActivity` `onTrimMemory/onLowMemory`, idle unload | No FGS/RAM preflight/thermal |
| Launcher icon + target/version bump | Implemented; CI Unverified | icon resources, manifest, build.gradle targetSdk35/v2 | CI packaging |
| MainViewModel live MVVM | Code present, not wired | `MainViewModel` no refs in MainActivity | Dead-parallel/false confidence |
| Instrumented/device tests | Missing | No `androidTest` | Proposed |
| Multimodal end-to-end / model catalog / semantic memory / native tool-calling | Proposed / Out of scope | No implementation | Approval needed |

## Key findings

### Critical
- **CI red on current head (root causes being fixed iteratively):** conflict markers in UserMemoryStore/PersonalNotesStore removed (5372b14); InferenceEngineManager.kt constructor corruption from sampler-constants edit fixed; further CI errors being diagnosed via CI_ERRORS.md artifact pushed to branch.
- **Git baseline hygiene risk:** local/remote divergence and dirty tree observed during audit (synced before doc correction).

### High
- **Dead parallel architecture:** `MainViewModel` (1219 LOC) not wired into live `MainActivity` (4147 LOC); test confidence can be misleading.
- **Static 4K budgeting:** safer defaults now, but not model-capability-aware; large attachments/models remain risky.
- **LiteRT-LM 0.20.0 bump reverted:** 0.20.0 caused CI compile failure (exact log unavailable in sandbox); repo pinned back to 0.19.1 pending toolchain-compatible verification.
- **Memory/thermal/process-death risk:** large model use on 4–6GB devices lacks RAM preflight/FGS/thermal strategy.

### Medium
- **Speech/TTS provider caveat:** app has no INTERNET permission, but Android providers may use network depending on device/provider/config.
- **Basic model integrity only:** min-size/header check, no SHA-256/manifest.
- **Hand-rolled JSON persistence:** fragile over time despite tests.
- **Regex intent parsing:** confirmation mitigates but misparse possible.

## Recommended next milestone

**M1 — Trustworthy Green Baseline:** clean git state, CI green, roadmap/audit taxonomy corrected, CI failure root cause recorded. No new feature scope until M1 exit criteria met. See `DEVELOPMENT_ROADMAP.md`.
