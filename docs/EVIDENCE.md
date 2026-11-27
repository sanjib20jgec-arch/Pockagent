# Evidence ledger

_Updated: 2026-11-27. Tasks 1–11 are implemented on branch `hardening/9of10` (42 commits from merge-base `090537c`). The latest full gate is green with required lint/detekt steps. Nothing here claims a merged `main` HEAD, a tag, or a release._

## Verification status

| Label | Meaning |
|---|---|
| `Implemented` | Code/config exists on the branch. |
| `CI Verified` | A linked green run exists for the exact commit. |
| `Device Verified` | `connectedDebugAndroidTest` ran a non-zero number of tests on an emulator. |
| `Blocked` | Test exists but cannot pass without an asset that is deliberately not committed. |
| `Not yet earned` | Owner action required (merge/tag/release/branch protection) — no agent claim made. |

## Latest full green gate

[CI run 45930126324](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45930126324) — commit `dbaecfc`, workflow `Build APK`, conclusion `success`. All four jobs are green:

| Job | Result | Evidence in run |
|---|---|---|
| `Build debug APK` | success | Archive check, 490-test JVM suite, `assembleDebug`, required `lintDebug`, required `detekt` all passed |
| `Record screenshot baselines` | success | `verifyRoborazziDebug` matched six committed baselines |
| `Device smoke (API 28)` | success | `Starting 12 tests` / `Finished 15 tests`, `(3 skipped) (0 failed)` |
| `Device smoke (API 35)` | success | `Starting 12 tests` / `Finished 15 tests`, `(3 skipped) (0 failed)` |

Quality reports from `PocketAgent-quality-reports` on that run: Android lint **0 errors, 72 warnings**; detekt **0 findings**. Warnings are dependency-version/resource-quality notices; no correctness error is outstanding, and no baseline or blanket suppression was added.

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
| 10 (guard) | Reject `Starting 0 tests` false greens | — | `6d52282` (zero-test guard; verified by later runs) |
| 11 (discovery) | Lint/detekt advisory pass, 1991 detekt findings + 4 lint errors | [45926225562](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45926225562) @ `088d8b9` | Advisory only — not a quality-gate pass |
| 11 (fixes) | Source-level fixes, no baselines/suppressions | [45927365616](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45927365616) @ `f0d747e`, [45928116132](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45928116132) @ `9efb2f7` | [45929238530](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45929238530) @ `b5f15c1` |
| 11 (required gates) | Remove `continue-on-error` from lint/detekt | — | [45930126324](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45930126324) @ `dbaecfc` |

## False-green investigation (Task 10)

Runs `45920732575` and `45922272748` showed a green API 28 job that was **invalid as evidence**: `connected-test.log` reported `Starting 0 tests`. Logcat showed instrumentation component `android.test.InstrumentationTestRunner`; `defaultConfig` never set `testInstrumentationRunner`, so AGP used the legacy runner, which cannot discover JUnit4 tests.

- Fix: `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` in `app/build.gradle.kts` (commit `debd270`).
- Run `45923059217` then discovered 12 tests on both legs: 3 `@Ignore`/Blocked and real failures. API 28 had 3, API 35 had 4 — the previous green was false.
- Run `45923994013` exposed a systemic API 35 regression: all six Espresso `onView` tests failed with `RootViewWithoutFocusException`, including unchanged tests. Tests that avoid Espresso passed. The failing run alone logged a launcher ANR about a focusless window at boot.
- Fix: AOSP `default` target for the API 35 leg only (commit `ea0eb1b`). The offline app has no GMS dependency.
- Guard: commit `6d52282` fails any job whose log says `Starting 0 tests` / `Finished 0 tests` or contains no `Finished N tests` line.

## Task 11 root causes

- Quality gate: 4 Android lint errors and 1991 detekt findings were fixed in source. No baseline, `ignoreFailures`, or blanket suppression was introduced. `config/detekt.yml` only disables noisy style/complexity rules so correctness findings remain actionable.
- JVM race: `DuplexVoiceControllerTest.interrupt halts active TTS and re-arms listening` failed with `expected:<LISTENING> but was:<GENERATING>`. Root cause was one shared cancellation flag: an interrupted recording run could observe the next run's reset flag and deliver stale samples into the voice pipeline. Fix: per-run `AtomicBoolean` in `InAppAudioRecorder` (commit `b5f15c1`).
- API 35 dialog: `artifactSaveWithNullLanguage` failed with `RootViewWithoutFocusException`. The dialog was shown while the activity window was still losing focus, so every Espresso root was focusless. Fix: wait for the real `hasWindowFocus()` condition, then match the dialog root explicitly (commit `b5f15c1`).

## Device evidence (latest required-gate run 45930126324, commit dbaecfc)

| Leg | Discovered | Executed events | Skipped/Blocked | Failed | Result |
|---|---:|---:|---:|---:|---|
| API 28 (google_apis, x86_64) | 12 | 15 | 3 | 0 | green |
| API 35 (default/AOSP, x86_64) | 12 | 15 | 3 | 0 | green |

Both `connected-test.log` artifacts report `Starting 12 tests` and `Finished 15 tests` with `(3 skipped) (0 failed)`; the 15 events include the runner's extra result events. The three skipped tests are `modelImport`, `chatRoundTrip`, `pdfCitation`.

### Blocked (never recorded as passing)

| Test | Reason |
|---|---|
| `modelImport` | No valid tiny `.litertlm` fixture is committed; a synthetic binary would not load in LiteRT-LM. |
| `chatRoundTrip` | Requires the blocked model fixture and a native inference round trip. |
| `pdfCitation` | Requires model inference for citations; the synthetic PDF fixture alone is insufficient. |

`app/src/androidTest/assets/smoke/` is generated in CI by `scripts/generate_smoke_fixtures.py` and gitignored. No `.apk`, `.bin`, or `.litertlm` fixture is committed.

## Screenshot evidence

Six Roborazzi baselines are committed under `app/src/screenshots/` and verified by `verifyRoborazziDebug` in the latest green run:

| Screen | Light | Dark |
|---|---|---|
| Chat 375dp | [chat_light_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/app/src/screenshots/chat_light_375.png) | [chat_dark_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/app/src/screenshots/chat_dark_375.png) |
| Vault 375dp | [vault_light_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/app/src/screenshots/vault_light_375.png) | [vault_dark_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/app/src/screenshots/vault_dark_375.png) |
| Settings 375dp | [settings_light_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/app/src/screenshots/settings_light_375.png) | [settings_dark_375.png](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/app/src/screenshots/settings_dark_375.png) |

Report artifacts on run `45930126324`: `PocketAgent-screenshot-reports`, `PocketAgent-device-reports-api-28`, `PocketAgent-device-reports-api-35`, `PocketAgent-quality-reports`.

## Final scorecard (evidence-backed)

| Aspect | Mark | Evidence | Gap |
|---|---:|---|---|
| Product/vision | 9/10 | [README capability/constraint matrix](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/README.md) | Honest limits documented; no overclaim. |
| Architecture | 9/10 | [ADR 0001](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/docs/adr/0001-mainviewmodel-state-owner.md), `MainActivity` 4409→773 LOC, five controllers, three version-preserving stores | 108 extracted extensions verified `internal`; final fresh reviewer sign-off recorded in handoff. |
| Testing | 9/10 | [Green required-gate run](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45930126324), six screenshots, two device legs, lint/detekt reports | Three model-dependent tests are `Blocked`, never passed. |
| CI/CD | 8/10 | [Non-mutating workflow](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/.github/workflows/build.yml), zero-test guard, required lint/detekt steps | Fresh ≥19/20 green `main` window and required status check: **Not yet earned**. |
| Security/privacy | 9/10 | [SHA-256 importer](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/app/src/main/java/com/example/pocketagent/ModelIntegrity.kt), device offline assertion, no `INTERNET`, hardened WebView, [license inventory](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/THIRD_PARTY_LICENSES.md) | Arbitrary models remain importable by owner decision; no allow-list. |
| Docs | 9/10 | This ledger, [audit](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/PROJECT_AUDIT.md), [release notes](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/docs/release.md), [LICENSE](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/LICENSE) | Final owner merge/tag docs remain outstanding. |
| UI/UX | 9/10 | [Accessibility checklist](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/design-system/pocketagent/ACCESSIBILITY_CHECKLIST.md), [page overrides](https://github.com/sanjib20jgec-arch/Pockagent/tree/dbaecfc/design-system/pocketagent/pages), six screenshots | Keyboard-focus visual confirmation is device-limited; no clipped-text failures observed. |
| Release | 8/10 | [Release notes/signing template](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/docs/release.md), Gradle 8.14.4 wrapper, `1.6.1` / code 13, debug APK artifact | Tag `v1.6.1` and public release: **Not yet earned**. |
| **Overall** | **8.8/10** | Branch evidence is complete and green | The documented 9/10 is earned after the owner-only actions below. |

## Not yet earned (owner actions only)

| Item | Evidence link | Why |
|---|---|---|
| Fresh ≥19/20 green window on `main` | [main run history](https://github.com/sanjib20jgec-arch/Pockagent/actions?query=branch%3Amain) | Current last-20 snapshot is 9 success / 11 failure; none is a fresh post-merge window. |
| Required status check enabled on `main` | [branch protection settings](https://github.com/sanjib20jgec-arch/Pockagent/settings/branches) | Branch-protection changes are reserved to the owner. |
| Tag `v1.6.1` and release notes | [releases](https://github.com/sanjib20jgec-arch/Pockagent/releases) | No tag or release exists; owner must cut it after merge. |
| README status link after merge | [README](https://github.com/sanjib20jgec-arch/Pockagent/blob/dbaecfc/README.md) | Until `main` is merged and green, the branch link cannot be a `main` badge. |
| 15 stale `arena/*` branches deleted | [branches](https://github.com/sanjib20jgec-arch/Pockagent/branches) | Owner cleanup; not touched by the agent. |

No PR, merge, tag, release, branch-protection change, or CI log-push commit has been made by the agent. `main` currently resolves to `cf17544`; the hardening branch was cut from merge-base `090537c` and has 42 commits, while `main` has 12 later commits that the owner will need to reconcile during merge.
