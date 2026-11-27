# CI handoff (owner actions only)

The hardening branch produces PR-ready commits. The following steps require repository-owner authority and are intentionally not performed by the agent.

1. Enable branch protection on `main` with the required status check **`Build APK / Build debug APK`**.
2. Use the final green branch gate as the merge reference: <https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45930126324> (commit `dbaecfc`, workflow `Build APK`, conclusion `success`). It includes required `lintDebug` and `detekt` steps plus API 28/35 device smoke.
3. Confirm the workflow no longer writes to the repository: `.github/workflows/build.yml` uses `permissions: contents: read`, has no write-back step, and fails zero-test emulator runs.
4. After merging, require a fresh ≥19/20 green window in [main run history](https://github.com/sanjib20jgec-arch/Pockagent/actions?query=branch%3Amain). The current last-20 snapshot is 9 success / 11 failure and is not valid post-hardening evidence.
5. Reconcile the 12 commits that exist on `main` after the hardening merge base before cutting the release; do not discard them silently.
6. Copy the new green `main` run URL into [docs/EVIDENCE.md](EVIDENCE.md) and verify the README status link.
