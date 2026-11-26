# PocketAgent Project Audit (evidence-based)

_Updated: 2026-11-27. Baseline under audit: `main` HEAD `090537c31df21c8e050e42b7dcdf0fc187d6e489`. Hardening work is on branch `hardening/9of10`, green at `7bbfe70d99aa93ffca93a92a5caed71ad09cc48f`. This document reports verified facts, not aspirations._

## Verified snapshot

| Area | Evidence |
|---|---|
| App config | Android/Kotlin Views (no Compose/AppCompat/Material Components), `compileSdk 35`, `minSdk 28`, `targetSdk 35`, `versionCode 13` / `versionName 1.6.1` on the hardening branch, `applicationId com.example.pocketagent`, Java/Kotlin 17, NDK 27.2.12479018, CMake 3.22.1 |
| Toolchain | Committed Gradle 8.14.4 wrapper; workflow builds with `./gradlew` |
| Inference deps | `com.google.ai.edge.litertlm:litertlm-android:0.19.1`, `androidx.biometric:biometric:1.1.0`, `pdfbox-android:2.0.27.1`, `kotlinx-coroutines-android:1.8.1`; inventory in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) |
| Source/tests | 61 main Kotlin files, 51 JVM test classes, 0 `androidTest` files |
| Live architecture | `MainActivity.kt` ~4391 LOC is the live path; `MainViewModel.kt` ~1372 LOC still has no observer wiring from `MainActivity` (decision recorded in Task 7) |
| CI | Green branch run [45911625131](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45911625131) at `7bbfe70`: archive check + `testDebugUnitTest` + `assembleDebug` all passed. `main` HEAD still points at a `ci-debug` commit with a failing compile. Workflow now uses `contents: read` and contains no `git commit`/`git push`. |
| Privacy posture | No `INTERNET` permission; `RECORD_AUDIO` present; backup/data extraction disabled; biometric lock present; WebView has `javaScriptEnabled=true` with `allowFileAccess=false`, `blockNetworkLoads=true`, no JS bridge; whisper.cpp fetched by CMake with pinned `URL_HASH SHA256` |
| Repo hygiene | No tags/releases; 15 stale `arena/*` branches; MIT [LICENSE](LICENSE) added on the hardening branch; no keystore or signed APK committed |

## Status taxonomy

**Proposed / Approved / In Progress / Implemented / CI Verified / Device Verified / Completed / Blocked / Out of Scope.**
CI is not device testing. Code presence is not verified functionality.

## Current marks (interim, evidence-linked)

Full ledger: [docs/EVIDENCE.md](docs/EVIDENCE.md).

| Aspect | Mark | Basis |
|---|---:|---|
| Product/vision | 8/10 | Honest capability matrix and constraints in README |
| Architecture | 4/10 | 4391-LOC Activity, ViewModel unwired |
| Testing | 5/10 | 51 JVM test classes green in CI; no instrumented tests |
| CI/CD | 6/10 | Green run at exact commit, non-mutating workflow; no fresh 20-run window yet |
| Security/privacy | 8/10 | Permission review + hardened sandbox + pinned native fetch; SHA-256 model check pending |
| Docs | 7/10 | Docs reconciled to verified commit, ledger and licenses present |
| UI/UX | 7/10 | Existing Warm Editorial design system; no persisted page tokens or screenshots yet |
| Release | 5/10 | Wrapper, MIT license, version bump; no signing template, tag or release notes |
| **Overall** | **6.0/10** | Phase 0 complete; tasks 5–11 pending |

## Key findings

### Critical (baseline, fixed on hardening branch)

- `MainActivity.kt:2518:59`: `HtmlArtifactSandbox.onSaveArtifact` supplies `String?` where `showSaveArtifactDialog(code: String, language: String)` expected non-null. Pinned by `ArtifactLanguageTest`; fixed with `normalizeArtifactLanguage`.
- Workflow step `Publish compile errors for sandbox debugging` wrote `CI_ERRORS.md` and pushed it to the branch, invalidating the verified HEAD. Step removed; `contents: read`; failure logs are now a job summary + artifact instead.

### High

- **God Activity:** `MainActivity.kt` is still ~4391 LOC; split into focused controllers is Task 8.
- **Dead parallel ViewModel:** tests exist but the live path does not observe it; ownership decision and wiring are Task 7.
- **Hand-rolled JSON stores:** session/memory/note persistence needs a version-preserving schema migration (Task 6).
- **Model integrity:** header/min-size checks exist, but no stored SHA-256 verification (Task 5).

### Medium

- No device/emulator evidence (`androidTest` absent); smoke + screenshots are Task 10.
- Static 4K budgeting is not model-capability-aware; device/provider speech behavior remains caveat.
- Target `v1.6.1` has no tag or release notes yet.

## Exit criteria for the hardening program

1. `MainActivity.kt` < 800 LOC with ADR and all JVM tests green.
2. SHA-256 sidecar verification for arbitrary user models; legacy store data preserved.
3. Persisted UI UX Pro Max page tokens + 375/768/1024dp checklist + screenshots.
4. Fresh 20-run green window (≥19/20) and lint/detekt required steps.
5. Final evidence-backed scorecard and owner handoff checklist — no agent merges, tags or branch-protection changes.
