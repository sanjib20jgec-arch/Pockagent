# Evidence ledger

_Updated: 2026-11-27. Tasks 1–10 are implemented and CI-verified on branch `hardening/9of10`. Task 11 (lint/detekt, final scorecard, owner handoff) is in progress. Nothing here claims a merged `main` HEAD, a tag, or a release._

## Verification status

| Label | Meaning |
|---|---|
| `Implemented` | Code/config exists on the branch. |
| `CI Verified` | A linked green run exists for the exact commit. |
| `Device Verified` | `connectedDebugAndroidTest` ran a non-zero number of tests on an emulator. |
| `Blocked` | Test exists but cannot pass without an asset that is deliberately not committed. |
| `Not yet earned` | Owner action required (merge/tag/release/branch protection) — no agent claim made. |

Latest full green gate: [CI run 45925286889](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45925286889) — commit `ea0eb1b`. Four jobs green: `Build debug APK`, `Record screenshot baselines`, `Device smoke (API 28)`, `Device smoke (API 35)`. A follow-up guard commit `6d52282` adds a zero-test detector; its own run is recorded below.

## Task-by-task CI evidence

| Task | Change | RED gate | GREEN gate |
|---|---|---|---|
| 1 | Language normalization + non-mutating CI | [45910703951](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45910703951) | [45911023822](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45911023822) @ `6a2dfc0` |
| 2 | Read-only CI permissions, owner handoff doc | — | [45911297115](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45911297115) @ `77ceb97` |
| 3 | Gradle 8.14.4, `1.6.1` / code 13, MIT + third-party inventory | — | [45911625131](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45911625131) @ `7bbfe70` |
| 4 | README, audit, roadmap, initial evidence ledger | — | [45912097696](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45912097696) @ `9061c13` |
| 5 | Streaming SHA-256 import + sidecar + startup tamper rejection | [45912332225](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45912332225) | [45912637643](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45912637643) @ `c9bace9` |
| 6 | Version-tagged store migration for three stores | [45913107217](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45913107217), [45913569438](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45913569438) | [45913880612](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45913880612) @ `5ee4b41` |
| 7 | `MainViewModel` state owner + ADR + observer seam | [45914175535](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45914175535) @ `8a1b2fb` | [45914778041](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45914778041) @ `bf23e94` |
| 8 | `MainActivity` decomposition into five controllers | [45915102436](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45915102436) @ `b83eb50` | [45916815153](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45916815153) @ `88c386f` |
| 8 (review) | Internalize 108 extracted extensions | [45917889757](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45917889757) @ `c1d6201` | [45918128694](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45918128694) @ `0e03771` |
| 9 | Semantic `UiTokens`, motion, page overrides, contrast assertions | — | [45917624706](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45917624706) @ `5262581` |
| 10 | Roborazzi baselines + API 28/35 emulator smoke | [45919105134](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45919105134) @ `18cbd6a` | [45925286889](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45925286889) @ `ea0eb1b` |
| 10 (guard) | Reject `Starting 0 tests` false greens | — | `6d52282` (pushed; see note below) |

## False-green investigation (Task 10)

Runs `45920732575` and `45922272748` showed a green API 28 job that was **invalid as evidence**: `connected-test.log` reported `Starting 0 tests`. Logcat showed instrumentation component `android.test.InstrumentationTestRunner`; `defaultConfig` never set `testInstrumentationRunner`, so AGP used the legacy runner, which cannot discover JUnit4 tests.

- Fix: `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` in `app/build.gradle.kts` (commit `debd270`).
- Run `45923059217` then discovered 12 tests on both legs: 3 `@Ignore`/Blocked and real failures. API 28 had 3, API 35 had 4 — the previous "green" was false.
- Run `45923994013` exposed a systemic API 35 regression: all six Espresso `onView` tests failed with `RootViewWithoutFocusException` (`has-window-focus=false`), including unchanged tests. Tests that avoid Espresso passed. The failing run alone logged `ANR in ... nexuslauncher ... Application does not have a focused window` at boot.
- Fix: AOSP `default` target for the API 35 leg only (commit `ea0eb1b`). The offline app has no GMS dependency.
- Guard: commit `6d52282` fails any job whose log says `Starting 0 tests` / `Finished 0 tests` or contains no `Finished N tests` line. Prior green evidence above remains valid because those runs logged `Starting 12 tests` / `Finished 15 tests`.

## Device evidence (run 45925286889, commit ea0eb1b)

| Leg | Discovered | Executed | Skipped/Blocked | Failed | Result |
|---|---:|---:|---:|---:|---|
| API 28 (google_apis, x86_64) | 12 | 12 | 3 | 0 | green |
| API 35 (default/AOSP, x86_64) | 12 | 12 | 3 | 0 | green |

Both `connected-test.log` artifacts report `Starting 12 tests` and `Finished 15 tests` with `(3 skipped) (0 failed)`; the 15 events include the runner's extra result events. The three skipped tests are `modelImport`, `chatRoundTrip`, `pdfCitation`.

### Blocked (never recorded as passing)

| Test | Reason |
|---|---|
| `modelImport` | No valid tiny `.litertlm` fixture is committed; a synthetic binary would not load in LiteRT-LM. |
| `chatRoundTrip` | Requires the blocked model fixture and a native inference round trip. |
| `pdfCitation` | Requires model inference for citations; the synthetic PDF fixture alone is insufficient. |

`app/src/androidTest/assets/smoke/` is generated in CI by `scripts/generate_smoke_fixtures.py` and gitignored. No `.apk`, `.bin`, or `.litertlm` fixture is committed.

## Screenshot evidence

Six Roborazzi baselines are committed under `app/src/screenshots/` and verified by `verifyRoborazziDebug` in the green run above:

| Screen | Light | Dark |
|---|---|---|
| Chat 375dp | [chat_light_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/chat_light_375.png) | [chat_dark_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/chat_dark_375.png) |
| Vault 375dp | [vault_light_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/vault_light_375.png) | [vault_dark_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/vault_dark_375.png) |
| Settings 375dp | [settings_light_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/settings_light_375.png) | [settings_dark_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/screenshots/settings_dark_375.png) |

Report artifact: `PocketAgent-screenshot-reports` on run `45925286889`. Device report artifacts: `PocketAgent-device-reports-api-28`, `PocketAgent-device-reports-api-35`.

## Scorecard (evidence-backed, Task 11 finalization pending)

| Aspect | Mark | Evidence | Gap |
|---|---:|---|---|
| Product/vision | 8/10 | [README](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/README.md) | Honest constraints documented. |
| Architecture | 8/10 | [ADR 0001](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/docs/adr/0001-mainviewmodel-state-owner.md), `MainActivity` 4409→773 LOC, five controller files @ `0e03771` | Final reviewer sign-off pending. |
| Testing | 8/10 | JVM suite, Roborazzi baselines, two device legs above | Three Blocked model tests; lint/detekt pending. |
| CI/CD | 8/10 | Green run above, [non-mutating workflow](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/.github/workflows/build.yml), [owner handoff](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/docs/ci-handoff.md), zero-test guard | Fresh 20-run window on `main`: **Not yet earned**. |
| Security/privacy | 9/10 | No `INTERNET`, hardened WebView, MIT license, [SHA-256 importer](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/main/java/com/example/pocketagent/ModelIntegrity.kt), [device offline assertion](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/app/src/androidTest/java/com/example/pocketagent/SmokeTests.kt) | No allow-list; arbitrary models remain importable. |
| Docs | 8/10 | This ledger, [audit](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/PROJECT_AUDIT.md), [roadmap](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/DEVELOPMENT_ROADMAP.md) | Final audit update pending. |
| UI/UX | 8/10 | [Accessibility checklist](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/design-system/pocketagent/ACCESSIBILITY_CHECKLIST.md), six baselines above | Keyboard-focus visual confirmation is device-limited. |
| Release | 7/10 | Gradle 8.14.4, `1.6.1` / code 13, [third-party inventory](https://github.com/sanjib20jgec-arch/Pockagent/blob/ea0eb1b/THIRD_PARTY_LICENSES.md), signed debug APK artifact | Tag/release/signing template: **Not yet earned**. |

## Not yet earned (owner actions only)

| Item | Why |
|---|---|
| Fresh 20-run green window on `main` | `main` HEAD is still the failing baseline `090537c`; no merge has been made. |
| Required status check enabled on `main` | Branch protection change reserved to the owner. |
| Tag `v1.6.1` and release notes | Owner must cut the release after merge. |
| README badge verification | Badge cannot be green until `main` is green. |
| 15 stale `arena/*` branches deleted | Owner cleanup; not touched by the agent. |

No PR, merge, tag, release, branch-protection change, or CI log-push commit has been made by the agent.
