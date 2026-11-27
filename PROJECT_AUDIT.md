# PocketAgent Project Audit (evidence-based)

_Updated: 2026-11-27. Audited baseline: merge-base `090537c31df21c8e050e42b7dcdf0fc187d6e489`. Hardening branch `hardening/9of10` is green at `dbaecfc38baa970e41fbcf9fd243fdca3a5c1298` with 42 commits. `main` currently resolves to `cf1754447dbcc41601a394d5fc0e52bef86c87ae`; no merge, tag, release, or branch-protection change was made by the agent._

## Verified snapshot

| Area | Evidence |
|---|---|
| App config | Android Views (no Compose/AppCompat/Material Components), `compileSdk 35`, `minSdk 28`, `targetSdk 35`, `versionCode 13` / `versionName 1.6.1`, Java/Kotlin 17, NDK 27.2.12479018, CMake 3.22.1 |
| Toolchain | Committed Gradle 8.14.4 wrapper; workflow builds with `./gradlew` and runs required lint/detekt gates |
| Dependencies | LiteRT-LM 0.19.1, biometric 1.1.0, PDFBox-Android 2.0.27.1, coroutines 1.8.1, serialization 1.7.3, Roborazzi 1.79.0; inventory in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) |
| Source/tests | 68 main Kotlin files, 55 JVM test classes, 1 instrumented smoke class, 6 screenshot baselines |
| Architecture | `MainActivity.kt` 773 LOC; 5 focused controller files; `MainViewModel` is the state owner; 3 stores use version-preserving migration |
| CI | [Run 45930126324](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45930126324) green at `dbaecfc`: archive check, 490-test JVM suite, APK, screenshots, required lint/detekt, API 28 + API 35 device smoke |
| Quality | Android lint 0 errors / 72 warnings; detekt 0 findings; no baselines or blanket suppressions |
| Privacy posture | No `INTERNET` permission; backup/data extraction disabled; biometric lock; hardened WebView; SHA-256 model sidecar verified on startup; no model allow-list |
| Release | MIT [LICENSE](LICENSE), [third-party inventory](THIRD_PARTY_LICENSES.md), [release/signing template](docs/release.md), unsigned debug APK artifact; no tag/release |

## Status taxonomy

**Proposed / Approved / In Progress / Implemented / CI Verified / Device Verified / Completed / Blocked / Out of Scope.**
CI is not device testing. Code presence is not verified functionality. A blocked test is never recorded as passing.

## Final scorecard

Full ledger: [docs/EVIDENCE.md](docs/EVIDENCE.md).

| Aspect | Mark | Basis |
|---|---:|---|
| Product/vision | 9/10 | Honest capability/constraint matrix in [README](README.md) |
| Architecture | 9/10 | 4409→773 LOC Activity, [ADR 0001](docs/adr/0001-mainviewmodel-state-owner.md), 5 controllers, serialization migration, JVM tests |
| Testing | 9/10 | 490 JVM tests, 12 discovered instrumented tests on API 28/35, 6 screenshots, required quality gates; 3 model tests remain `Blocked` |
| CI/CD | 8/10 | Green run at exact commit, read-only non-mutating workflow, zero-test guard, required lint/detekt; fresh 20-run `main` window and required check **Not yet earned** |
| Security/privacy | 9/10 | Permission review, hardened sandbox, pinned native fetch, SHA-256 model integrity, license audit, no new `INTERNET` |
| Docs | 9/10 | Docs reconciled to verified branch HEAD, evidence ledger, LICENSE, release notes |
| UI/UX | 9/10 | Persisted Warm Editorial + Swiss Minimalism page tokens, accessibility checklist across 375/768/1024dp, light/dark screenshots |
| Release | 8/10 | Wrapper, `1.6.1`/code 13, MIT license, signing/R8 template; tag and release **Not yet earned** |
| **Overall** | **8.8/10** | Branch evidence is complete; the documented 9/10 requires the owner actions in [docs/handoff-checklist.md](docs/handoff-checklist.md) |

## Key findings resolved

- Compile blocker: nullable artifact language normalized; pinned by `ArtifactLanguageTest`.
- Workflow mutation: failure-log push step removed; `contents: read`; HEAD is unchanged after runs.
- God Activity: split into `InputImportController`, `VoiceController`, `ChatGenerationController`, `DialogRouter`, `ChatScreenBuilder`; all 108 extracted extensions are `internal`.
- Legacy JSON stores: schema tag added while legacy sessions/memories/notes still load and are rewritten.
- Model integrity: arbitrary user models stay importable; SHA-256 sidecar computed on import and verified on startup.
- False device greens: JUnit4 instrumentation runner configured and CI now fails zero-test emulator runs.
- Quality gates: all lint errors and detekt findings fixed in source; both steps are required.

## Exit criteria for the hardening program

1. `MainActivity.kt` < 800 LOC with ADR and all JVM tests green — **met**.
2. SHA-256 sidecar verification; legacy store data preserved — **met**.
3. Persisted UI UX Pro Max page tokens + 3-width checklist + screenshots — **met**.
4. Fresh 20-run green window (≥19/20) and required check on `main` — **owner action**.
5. Final evidence-backed scorecard and owner handoff checklist — **met**; no agent merges, tags, or protection changes.
