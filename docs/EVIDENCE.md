# Evidence ledger

_Updated: 2026-11-27. Status is intentionally interim: Phase 0 (tasks 1–3) is green on branch `hardening/9of10`; tasks 4–11 are still in progress. Nothing here claims device verification or a merged `main` HEAD._

Green baseline: [CI run 45911625131](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45911625131) — commit `7bbfe70d99aa93ffca93a92a5caed71ad09cc48f`, workflow `Build APK / Build debug APK`, conclusion `success` (archive check + `testDebugUnitTest` + `assembleDebug`).

| Aspect | Mark | Evidence URL | Verified commit |
|---|---:|---|---|
| Product/vision | 8/10 | [README capability and honest constraints](https://github.com/sanjib20jgec-arch/Pockagent/blob/7bbfe70d99aa93ffca93a92a5caed71ad09cc48f/README.md) | `7bbfe70` |
| Architecture | 4/10 | [MainActivity (4391 LOC)](https://github.com/sanjib20jgec-arch/Pockagent/blob/7bbfe70d99aa93ffca93a92a5caed71ad09cc48f/app/src/main/java/com/example/pocketagent/MainActivity.kt), [MainViewModel not yet wired](https://github.com/sanjib20jgec-arch/Pockagent/blob/7bbfe70d99aa93ffca93a92a5caed71ad09cc48f/app/src/main/java/com/example/pocketagent/MainViewModel.kt) | `7bbfe70` |
| Testing | 5/10 | [Green JVM test + assemble run](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45911625131); 51 JVM test classes, 0 instrumented tests | `7bbfe70` |
| CI/CD | 6/10 | [Non-mutating workflow](https://github.com/sanjib20jgec-arch/Pockagent/blob/7bbfe70d99aa93ffca93a92a5caed71ad09cc48f/.github/workflows/build.yml), [owner handoff](https://github.com/sanjib20jgec-arch/Pockagent/blob/7bbfe70d99aa93ffca93a92a5caed71ad09cc48f/docs/ci-handoff.md); fresh 20-run window: **Not yet earned** | `7bbfe70` |
| Security/privacy | 8/10 | [Manifest (no INTERNET)](https://github.com/sanjib20jgec-arch/Pockagent/blob/7bbfe70d99aa93ffca93a92a5caed71ad09cc48f/app/src/main/AndroidManifest.xml), [hardened WebView](https://github.com/sanjib20jgec-arch/Pockagent/blob/7bbfe70d99aa93ffca93a92a5caed71ad09cc48f/app/src/main/java/com/example/pocketagent/HtmlArtifactSandbox.kt), [MIT license](https://github.com/sanjib20jgec-arch/Pockagent/blob/7bbfe70d99aa93ffca93a92a5caed71ad09cc48f/LICENSE); SHA-256 model check: **Not yet earned** (Task 5) | `7bbfe70` |
| Docs | 7/10 | This ledger + [README](https://github.com/sanjib20jgec-arch/Pockagent/blob/7bbfe70d99aa93ffca93a92a5caed71ad09cc48f/README.md), [audit](https://github.com/sanjib20jgec-arch/Pockagent/blob/7bbfe70d99aa93ffca93a92a5caed71ad09cc48f/PROJECT_AUDIT.md), [roadmap](https://github.com/sanjib20jgec-arch/Pockagent/blob/7bbfe70d99aa93ffca93a92a5caed71ad09cc48f/DEVELOPMENT_ROADMAP.md); final audit: **Not yet earned** (Task 11) | `7bbfe70` |
| UI/UX | 7/10 | [Existing design system](https://github.com/sanjib20jgec-arch/Pockagent/blob/7bbfe70d99aa93ffca93a92a5caed71ad09cc48f/design-system/pocketagent/MASTER.md); page tokens, accessibility checklist and screenshots: **Not yet earned** (Tasks 9–10) | `7bbfe70` |
| Release | 5/10 | [Gradle 8.14.4 wrapper](https://github.com/sanjib20jgec-arch/Pockagent/blob/7bbfe70d99aa93ffca93a92a5caed71ad09cc48f/gradle/wrapper/gradle-wrapper.properties), [third-party inventory](https://github.com/sanjib20jgec-arch/Pockagent/blob/7bbfe70d99aa93ffca93a92a5caed71ad09cc48f/THIRD_PARTY_LICENSES.md), `versionCode 13` / `1.6.1`; signing template, tag and release notes: **Not yet earned** (Task 11) | `7bbfe70` |

## Status rules

- `Implemented` means code/config exists; `CI Verified` means a linked green run exists for the exact commit; `Device Verified` requires `connectedDebugAndroidTest` evidence.
- Marks are raised only when the evidence column contains a live link to the artifact that earns the mark.
- Main-branch HEAD is still `090537c` (failing, with a `ci-debug` commit). The green commit above is on `hardening/9of10`; owner merges are a handoff step, not an agent action.

## Planned evidence (not yet earned)

| Task | Artifact that will replace the placeholder above |
|---|---|
| 5 | SHA-256 sidecar tests + checksum startup test |
| 6 | Legacy-fixture migration tests for three stores |
| 7 | ADR `0001-mainviewmodel-state-owner.md` |
| 8 | `MainActivity` LOC report + review findings |
| 9 | Persisted page overrides + contrast test + accessibility checklist |
| 10 | Emulator matrix runs (API 28/35) + Roborazzi baselines |
| 11 | Fresh 20-run window, lint/detekt results, final audit and handoff |
