# PocketAgent Development Roadmap

_Updated: 2026-11-24. Grounded in current repo state; items marked [x] were addressed in this pass (code-level), [~] partial, [ ] pending._

## Guiding constraints

- **Offline-first / privacy-first**: no INTERNET permission; no telemetry; user data stays local.
- **Small-device reality**: target devices include 4-6GB RAM phones; 3.23GB Omni model has high peak RAM risk. Design for graceful degradation, not just flagship behavior.
- **Honest capability UX**: never imply a context window, modality, or live capability that the loaded model/runtime cannot provide.
- **Validate in Android/CI**: this sandbox cannot run Gradle Android builds or device tests; CI/device validation is required for runtime changes.

## Phase 0 - Baseline / truth (this pass)

- [x] Refresh README / audit / roadmap to match code reality.
- [x] Record known model/runtime constraints (4K context class, Omni state bleed risk, RAM pressure).
- [ ] Add model capability matrix doc (context, modalities, RAM, license, recommended device tier).

## Phase 1 - Correctness & safety (P0)

### 1.1 Context budgeting & prompt honesty
- [x] Set honest defaults for 4K-class models: `4096/1024/384` token budget, `6000` attachment chars, `1024` max response tokens.
- [x] Reserve system budget even when system instruction is passed out-of-band.
- [x] Trim newest turn when it alone exceeds available input budget.
- [x] Reduce runaway char backstop to `12000`.
- [ ] Make budgets model-aware (read/registry per model: context window, max output, reserved system).
- [ ] Surface truncation/context state clearly in UI (tokens/chars used, what was trimmed, why).
- [ ] Add device tests for overflow, long attachment, and multi-turn pruning.

### 1.2 Engine/session correctness
- [x] Bump LiteRT-LM to `0.20.0`.
- [~] Add conservative `SamplerConfig` (topK/topP/temperature) for small-model stability; constants added, ConversationConfig wiring deferred pending 0.20.0 API verification.
- [~] Conversation cache keyed by session/model/system/attachment hash.
- [ ] Verify cross-session state isolation on LiteRT-LM 0.20.0 with Omni model on device.
- [ ] Add explicit "new chat = clean state" integration test.
- [ ] Add fallback strategy if state bleed is observed (recreate engine/conversation, feature flag).

### 1.3 Lifecycle / memory pressure
- [x] Add `onTrimMemory`/`onLowMemory` handling.
- [x] Add 120s idle engine unload.
- [ ] Add foreground service for long generation (Android 14+ FGS type compliance).
- [ ] Add RAM preflight before model load; warn/block on insufficient memory.
- [ ] Add thermal/battery-aware throttling or user warning.
- [ ] Add model unload/reload UX and crash-recovery telemetry-free diagnostics.

### 1.4 Model import integrity
- [x] Basic min-size/header sanity check for `.litertlm` imports.
- [ ] Model registry metadata: expected size/hash, context, modalities, license, quantization.
- [ ] Optional SHA-256 verification when metadata is known.
- [ ] Clear error UX for corrupt/incompatible/unsupported-capability model files.

## Phase 2 - Verification & release hygiene (P1)

### 2.1 Test confidence
- [x] Update/add JVM tests for changed budget/import behavior.
- [ ] Add lint/detekt/static checks to CI.
- [ ] Add instrumented smoke tests: import, load, ask, stop, rotate, process death, background/trim memory.
- [ ] Separate "live path" tests from dead-path/ViewModel-only tests; either wire ViewModel into live UI or reduce duplication.
- [ ] Add golden tests for prompt composition and truncation reporting.

### 2.2 Architecture
- [ ] Decide MainActivity vs MVVM path: either migrate live UI to MainViewModel incrementally or remove dead parallel implementation.
- [ ] Extract inference/session/model/storage services from MainActivity.
- [ ] Replace hand-rolled JSON stores with safer serialization (kotlinx.serialization/Moshi) behind stable interfaces.
- [ ] Make action protocol explicit/structured; avoid prose-only regex action extraction.

### 2.3 Release hygiene
- [ ] Real `applicationId` decision (migration impact), versioning scheme, changelog.
- [ ] `targetSdk` 35/36 as appropriate; review permission/FGS/storage behavior.
- [ ] Adaptive launcher icon, app name/resources, `strings.xml` extraction.
- [ ] R8/proguard for release, signing config via CI secrets, reproducible build notes.
- [ ] Gradle wrapper/checksum or documented pinned Gradle bootstrap; dependency/license audit.
- [ ] Remove or clearly label `PocketAgent.zip` artifact policy; avoid stale binary artifacts in git.

## Phase 3 - Model capability & Omni UX (P2)

### 3.1 Model pool / capability-aware UX
- [ ] Model registry: chat model, vision/audio model, low-RAM fallback.
- [ ] RAM/device preflight and recommended model selection.
- [ ] In-app model catalog/download/resume with hash/license display (without adding broad INTERNET permission unless explicitly designed).
- [ ] Per-model capability badges: text, image, audio-in, audio-out, context, speed.

### 3.2 Multimodal
- [ ] Image input path (picker/camera, resize to model limits, preview/remove).
- [ ] Audio input via Omni model (bounded recording) with ASR quality comparison against Whisper.
- [ ] TTS strategy: detect network voices, prefer local/offline voice; evaluate Omni TTS feasibility as separate capability.
- [ ] Honest modality gating when selected model lacks capability.

### 3.3 Agent / retrieval
- [ ] Native tool-calling schema where runtime supports it; confirmation sheet + action log + undo.
- [ ] Semantic retrieval (EmbeddingGemma/embedding model) behind same citation interface.
- [ ] Memory review queue; no silent auto-fact writes without user control.

### 3.4 Performance & observability (local-only)
- [ ] Tok/s, TTFT, load time, RAM, thermal state in diagnostics screen.
- [ ] Benchmark harness (macrobenchmark/device lab) with fixed prompts.
- [ ] Exportable local diagnostics bundle (user-controlled, no telemetry).

## Execution notes (this pass)

Completed in-repo: context budget honesty fixes, sampler config, LiteRT-LM bump, lifecycle memory handling, basic import sanity checks, test updates, docs refresh.

Pending environment-dependent validation: Android build, unit tests on CI, lint, instrumented/device tests, Omni state-bleed verification, RAM/thermal behavior on target devices.
