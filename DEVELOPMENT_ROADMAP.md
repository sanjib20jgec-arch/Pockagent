# PocketAgent Development Roadmap (evidence-based)

_Updated: 2026-11-27. Replaces the `arena/925d477f` snapshot. Baseline: `main` HEAD `090537c` (failing); hardening branch `hardening/9of10` green at `7bbfe70`. See [docs/EVIDENCE.md](docs/EVIDENCE.md)._

## Status taxonomy

| Status | Meaning |
|---|---|
| **Proposed** | Idea/design only; not approved for implementation. |
| **Approved** | Approved for implementation; no code yet. |
| **In Progress** | Actively being implemented. |
| **Implemented** | Code/config present in repo and/or wired; not yet verified by CI/device. |
| **CI Verified** | Passing CI evidence on the exact commit. |
| **Device Verified** | Verified on real device/emulator with recorded evidence. |
| **Completed** | Acceptance criteria met with evidence (CI/device/review as required). |
| **Blocked** | Cannot proceed due to dependency, failure, or unresolved risk. |
| **Out of Scope** | Not in approved scope for this roadmap phase. |

**Rules:** CI is not device testing. Code presence is not verified functionality. No “Completed” claim without acceptance evidence.

## Guiding constraints

- Offline-first/privacy-first: no `INTERNET` permission, no telemetry, user data stays local.
- Small-device reality: 4–6GB RAM targets; small-context on-device models; graceful degradation.
- Honest capability UX: never imply context window, modality or live capability the runtime cannot provide.
- Verification discipline: Implemented / CI Verified / Device Verified / Completed are distinct.
- Confirmation-only actions: system intents require review; autonomous execution is Out of Scope.
- Agent authority: PR-ready commits only. Merges, tag `v1.6.1`, release notes and branch protection are owner actions.

## Phase 0 — Trustworthy green baseline (Completed on branch)

| ID | Item | Status | Evidence |
|---|---|---|---|
| M1-1 | Fix P0 nullable artifact language | Completed | [ArtifactLanguageTest](app/src/test/java/com/example/pocketagent/ArtifactLanguageTest.kt), [CI run 45911023822](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45911023822) |
| M1-2 | Remove CI log-push/write path | Completed | `.github/workflows/build.yml` has `contents: read`, no `git commit`/`git push`; [handoff](docs/ci-handoff.md) |
| M1-3 | Reproducible toolchain + licenses + `v1.6.1` | Completed | Gradlew 8.14.4, [LICENSE](LICENSE), [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md), `versionCode 13` |
| M1-4 | Reconcile README/audit/roadmap/evidence | Completed | [EVIDENCE.md](docs/EVIDENCE.md), [PROJECT_AUDIT.md](PROJECT_AUDIT.md), this file |
| M1-5 | Green baseline at exact commit | CI Verified | [Run 45911625131](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45911625131) @ `7bbfe70` |

Phase 0 exit: green CI run on a non-mutating workflow with docs anchored to that commit. Device verification is still absent.

## Remaining hardening program (planned PRs from `hardening/9of10`)

| Task | Planned PR / branch slice | Status | Acceptance criteria | Evidence / Gap |
|---|---|---|---|---|
| 5 | `feat/model-sha256` | Proposed | SHA-256 computed on import, sidecar stored, verified on startup; arbitrary user models still importable | Tests pending; no allow-list |
| 6 | `feat/store-migration` | Proposed | Legacy sessions/memories/notes load and are rewritten with `"schema_version":1`; no data loss | Three legacy-fixture tests pending |
| 7 | `arch/viewmodel-owner` | Proposed | ADR recorded; `MainViewModel` observed by `MainActivity` | Observer tests pending |
| 8 | `refactor/activity-split` | Proposed | `MainActivity.kt` < 800 LOC; controllers own input/voice/chat/dialogs/UI | Slice builds green; review gate pending |
| 9 | `design/tokens-a11y` | Proposed | Persisted chat/vault/settings overrides; ≥4.5:1 contrast; checklist | UI UX Pro Max searches + contrast tests pending |
| 10 | `test/device-evidence` | Proposed | Emulator API 28/35 matrix green; screenshots at 375dp | `androidTest` absent; fixtures generated in CI |
| 11 | `docs/final-9of10` | Proposed | Lint/detekt required; fresh 20-run window ≥19/20; final audit + handoff | Marks raised only with linked evidence |

## Earlier phases (status carried forward)

| ID | Item | Status | Evidence | Gap / Next |
|---|---|---|---|---|
| P1-1..P1-3 | Honest 4K budgeting, system reserve, overflow trim | Implemented; CI Verified on branch | `ContextBudgetManager`, `MainUiState`, `RunawayGuard`, tests | Model-capability aware budgeting; device validation |
| P1-4 | LiteRT-LM 0.20.0 bump | Blocked/Reverted | Pinned back to 0.19.1 after CI compile failure | Retry only with verified toolchain-compatible release |
| P1-7 | Lifecycle memory handling | Implemented; CI Verified on branch | `onTrimMemory/onLowMemory`, idle unload | Foreground service, RAM preflight, thermal |
| P2-3 | Lint/detekt gate | Proposed | — | Task 11: non-blocking first, then required |
| P2-7 | Adaptive launcher icon | Implemented; CI Verified on branch | mipmap-anydpi-v26 + drawable foreground | Resource packaging covered by assembleDebug |
| P2-8 | Release hygiene | In Progress | Wrapper, MIT license, third-party inventory, version bump | Signing template, R8 decision, tag/release |
| P2-9 | JSON hardening/migration | In Progress | Task 6 plan approved | Preserve legacy data |

## Out of scope (current)

- Autonomous device control / AccessibilityService automation.
- Claims of cloud-free behavior for third-party speech/TTS providers.
- Any feature requiring `INTERNET` permission without explicit product/privacy approval.
- Compose rewrite, multimodal/Omni expansion, and new feature implementation during this hardening program.

## Evidence gaps / risks (must remain visible until closed)

- `main` HEAD is not CI verified; green commit is on a branch awaiting owner merge.
- No device verification exists (0 instrumented tests).
- `MainActivity` remains a ~4391-LOC Activity; `MainViewModel` remains unwired.
- Model budgets are static 4K defaults, not capability-aware.
- No stored model checksum, no signing pipeline, no tag/release yet.
