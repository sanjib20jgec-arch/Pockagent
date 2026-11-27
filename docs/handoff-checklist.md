# Owner handoff checklist

The agent produced PR-ready commits only on `hardening/9of10`. The following actions require repository-owner authority and were intentionally not performed.

## 1. Merge the hardening work in order

- Branch: [`hardening/9of10`](https://github.com/sanjib20jgec-arch/Pockagent/tree/hardening/9of10)
- Merge base: `090537c31df21c8e050e42b7dcdf0fc187d6e489`
- Branch state: 42 commits, green at `dbaecfc38baa970e41fbcf9fd243fdca3a5c1298` in [run 45930126324](https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45930126324).
- `main` currently has 12 later commits (`cf175444` at the time of writing). Reconcile those commits before or during the merge; do not discard them silently.
- Merge order follows the task commits: baseline/language → CI → toolchain/license → docs → model integrity → store migration → ViewModel seam → controllers → design system → device evidence → quality gates → final evidence.
- After merge, confirm the archive check, 490-test JVM suite, APK, screenshots, required lint/detekt, and both device legs are green on `main`.

## 2. Enable the required status check

In [branch protection settings](https://github.com/sanjib20jgec-arch/Pockagent/settings/branches), require the status check **`Build APK / Build debug APK`** on `main`. The workflow itself is already non-mutating (`contents: read`, no write-back step).

## 3. Establish the fresh green window

The current last-20 run snapshot on `main` is **9 success / 11 failure** and is not valid post-hardening evidence. After merge, check [main run history](https://github.com/sanjib20jgec-arch/Pockagent/actions?query=branch%3Amain) and require **≥19 successes in the last 20 runs** before release. Fix any flake; do not lower the threshold.

## 4. Create the release

- Tag: `v1.6.1`
- Release notes source: [docs/EVIDENCE.md](EVIDENCE.md)
- Signing inputs and R8 decision: [docs/release.md](release.md)
- Do not commit keystores, passwords, signed APKs, model binaries, or emulator fixtures.

## 5. Verify the README status link

After merge and a green `main` run, update/verify the status line in [README.md](README.md) so it points to a green `main` commit rather than the hardening branch run.

## 6. Delete stale branches

Delete these 15 stale `arena/*` branches after the merge is confirmed:

```text
arena/019473df-pockagent
arena/06541b59-pockagent
arena/0b7903d8-pockagent
arena/148384d6-pockagent
arena/2bd26a6e-pockagent
arena/5a536c2c-pockagent
arena/8a5b9936-pockagent
arena/925d477f-pockagent
arena/9a587974-pockagent
arena/9d7c4b10-pockagent
arena/c5dead91-pockagent
arena/caeb447d-pockagent
arena/d28fb48d-pockagent
arena/59b6ae5d-pockagent
arena/85d065bd-pockagent
```

## Agent boundary

No PR was opened by the agent. No merge, tag, release, branch-protection change, or CI log-push commit was made. The three model-dependent smoke tests remain `@Ignore`/`Blocked` until a valid tiny `.litertlm` fixture is supplied through an explicit review step.
