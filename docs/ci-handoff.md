# CI handoff (owner actions only)

The hardening branch produces PR-ready commits. The following steps require repository-owner authority and are intentionally not performed by the agent.

1. Enable branch protection on `main` with the required status check **`Build APK / Build debug APK`**.
2. Verify the green baseline run before merging: <https://github.com/sanjib20jgec-arch/Pockagent/actions/runs/45911023822> (commit `6a2dfc0`, workflow `Build APK`, conclusion `success`).
3. Confirm the workflow no longer writes to the repository: `.github/workflows/build.yml` uses `permissions: contents: read` and has no `git commit`/`git push` step. Failure logs are published as a job summary and the `PocketAgent-gradle-failure-log` artifact instead.
4. After merging the hardening PRs, copy the green run URL into `docs/EVIDENCE.md` (Task 4) and re-check that `main` HEAD has no `ci-debug` commit.
