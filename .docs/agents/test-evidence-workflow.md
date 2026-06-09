# Test Evidence Workflow

Agent guide for test evidence generation, publishing, and the dashboard lifecycle.

## Overview

Feature PRs in this repo follow a **generate → report → decide → publish** lifecycle for test evidence:

```text
PR CI generates normalised evidence artifacts
  → agent reports PR number, commit SHA, CI run ID in PR notes
  → reviewer/user decides whether to publish a durable snapshot
  → if yes: run Publish test evidence workflow → evidence lands on test-results branch
  → dashboard auto-refreshes via repository_dispatch
```

**Key principle:** Agents generate evidence metadata and ensure CI produces artifacts. They do **not** publish durable evidence unless explicitly instructed by the reviewer or issue.

## Feature PR lifecycle

### 1. Implementation

While implementing a feature, ensure:

- Normal CI validation runs for the PR (lint, unit tests, debug build).
- CI test evidence artifacts are generated when the PR reaches review-ready state.
  - This happens automatically if `scripts/generate_ci_test_evidence.py` runs as part of CI.
  - The evidence artifact is tagged with the commit SHA and PR number.
- For on-device features, ensure the `adb_skill_test.py` harness run is documented so manual validation can be reproduced.

### 2. PR notes / summary

In the PR description or final summary comment, agents **must mechanically discover and report:**

- **PR number** — e.g. `#1234`
- **PR head commit SHA** — the full SHA of the latest commit on the PR branch
- **CI run ID** — the GitHub Actions run ID that generated the evidence artifacts
- **CI status** — passed, failed, or in progress
- **Whether CI evidence artifact exists** — `ci-test-evidence-<sha>` in the run's artifact list
- **The exact artifact name and commit SHA** — used by the publish workflow to download evidence; the commit SHA in the artifact name is the **merge commit SHA** (`github.sha` for `pull_request` events), not the PR head SHA

The reviewer/user decides whether a given CI snapshot is worth publishing. Gather all the metadata so they can act without hunting across GitHub's UI.

> **Note on commit SHA:** CI evidence artifacts use the **merge commit SHA**, not the PR head SHA. GitHub Actions sets `github.sha` to a merge commit for `pull_request` events. The evidence artifact `ci-test-evidence-<sha>` and the evidence JSON's `commit` field both contain this merge SHA. Always report both SHAs and pass the **merge SHA** as `inputs.commit` to the publish workflow. The publisher validates `--commit` against the evidence JSON's `commit` field; use `--allow-commit-mismatch` only for manually-created evidence.
>
> **Future improvement:** [#1153](https://github.com/NickMonrad/kernel-ai-assistant/issues/1153) will add a PR-aware publishing workflow so the normal CI path becomes "provide PR number only", removing the need to fill in run IDs and commit SHAs manually.
### 3. Stop for review

After opening or updating the PR, **stop** unless explicitly instructed to publish evidence. The reviewer or user decides whether the current test results are meaningful enough to warrant a durable published snapshot on the `test-results` branch.

## Evidence publishing

Durable publishing is currently **reviewer/user-controlled**:

For **CI evidence**, the reviewer or user:

1. Navigates to the "Publish test evidence" workflow in GitHub Actions.
2. Provides: source (`ci`), PR number, commit SHA, CI run ID.
3. Workflow downloads the CI evidence artifact by run ID, validates, and publishes to the `test-results` branch.
4. The dashboard auto-refreshes via `repository_dispatch` after a successful publish.

For **on-device evidence**, the agent (when explicitly instructed):

1. Checks `adb devices` and confirms `ANDROID_SERIAL`.
2. Builds and installs the app if required (`./gradlew installDebug`).
3. Runs `scripts/adb_skill_test.py` with the relevant phase(s).
4. Finds the generated report in `scripts/test-reports/`.
5. Normalises the report to match the evidence schema.
6. Publishes locally with `scripts/publish_test_evidence.py` using appropriate flags.
7. Optionally triggers dashboard refresh — if `gh` auth is available and the user has explicitly instructed publication, the agent may manually trigger the "Publish test dashboard" workflow after local publish.
8. Reports the commands used, report path, published path, and dashboard outcome.

**User responsibilities for on-device evidence:**
- Connect and unlock the physical device.
- Approve Android permission / device prompts during the run.
- Keep the device available and awake during the run.
- Decide whether the snapshot should be published as durable dashboard evidence.

### When NOT to publish

- **Every WIP commit** — publishing on every push creates noise on `test-results` and wastes CI minutes.
  Publish only when the results represent a meaningful state (review-ready, pre-merge, release).
- **Before CI completes** — evidence artifacts may not exist or may be incomplete.
- **When tests are known to be broken** — fix the tests first, then publish a corrected snapshot.

### Publishing is not a merge gate

Evidence publishing is entirely optional. A PR can be merged without publishing evidence to `test-results`. The dashboard is a **historical record and trend view**, not a CI gate.

## Evidence types

### CI / static evidence

Generated automatically by GitHub Actions during PR CI runs:

- Normalised JSON evidence artifacts (`ci-test-evidence-<sha>`).
- Structured data consumed by the dashboard.
- Always associated with a specific commit SHA and PR number.

**Agents should confirm CI evidence artifacts are generated**, not manually create them.

### On-device / physical evidence

Generated by running the ADB harness against a physical device:

- Captures real model output, tool-call generation behaviour, and per-device pass rates.
- Results vary by SoC, inference backend, and model version.
- Cannot be replicated by CI (no physical device in GitHub Actions).

**Agent responsibilities:**

1. **Check device connectivity** — verify `adb devices` lists the target and `ANDROID_SERIAL` is set.
2. **Build and install** — if the app is not already deployed, run `./gradlew installDebug`.
3. **Run the harness** — execute `scripts/adb_skill_test.py` with the relevant phases.
4. **Find the report** — locate the generated JSON in `scripts/test-reports/` (use `ls -t` for the latest).
5. **Normalise** — transform the ADB report to match the evidence schema (`docs/testing/test-evidence-schema.md`).
6. **Publish** — run `scripts/publish_test_evidence.py` locally **only when explicitly instructed**. The "Publish test evidence" GitHub Actions workflow only supports `source=ci`, not on-device.
7. **Trigger dashboard** — if `gh` auth is available and the user has explicitly instructed publication, the agent may manually trigger the "Publish test dashboard" workflow after local publish.
8. **Report** — summarise commands run, report path, published path, and dashboard outcome.

**User responsibilities:**

- Connect and unlock the physical device.
- Approve Android permission / device prompts (USB debugging, install confirmations).
- Keep the device available and screen awake during the run.
- Decide whether a given snapshot is worth publishing as durable dashboard evidence.

### CI vs on-device distinction

The schema (`docs/testing/test-evidence-schema.md`) distinguishes `ci` from `on_device` evidence via the `source` field in the evidence metadata. Agents must:

- Keep them separate in documentation and reporting.
- Never conflate CI pass rates (build + lint + unit tests) with on-device pass rates (model inference on physical hardware).
- Note that dashboard filtering by source device/tier is planned but not yet implemented.

## Dashboard and test-results branch

| Component | Location | Purpose |
|-----------|----------|---------|
| Evidence storage | `test-results` branch | Durable evidence snapshots, organised by source/PR/release |
| Dashboard builder | `scripts/build_test_dashboard.py` | Static site generator consuming `test-results/results/` |
| Dashboard deployment | GitHub Pages (`github-pages` env) | Published via `publish-test-dashboard.yml` workflow |
| Evidence publisher | `scripts/publish_test_evidence.py` | Validates and writes evidence to `test-results` |
| Evidence publishing workflow | `.github/workflows/publish-test-evidence.yml` | Manual dispatch → publish → trigger dashboard rebuild |
| Dashboard publishing workflow | `.github/workflows/publish-test-dashboard.yml` | Manual dispatch OR push to main OR repository_dispatch |

## Current non-goals

- **No merge gates** — evidence publishing is not required to merge.
- **No auto-publish on every push** — reviewer decides when to publish.
- **No charts/analytics** in the dashboard beyond tabular pass/fail data.
- **No PR comments** from the dashboard or evidence pipeline.
- **No self-hosted device runner** — on-device testing remains manual.
- **No automatic local dashboard dispatch** — local/on-device evidence does not auto-refresh the dashboard by itself. If `gh` auth is available and the user has explicitly instructed publication, the agent may manually trigger the Publish test dashboard workflow after local publish.

## Related documents

| Document | Links to |
|----------|----------|
| [docs/testing/test-evidence-schema.md](../../docs/testing/test-evidence-schema.md) | Schema reference, `ci` vs `on_device` definitions |
| [docs/testing/README.md](../../docs/testing/README.md) | Testing documentation index |
| [docs/automated-testing.md](../../docs/automated-testing.md) | Operational testing overview, CI vs on-device section |
| [.docs/agents/validation.md](./validation.md) | Pre-PR validation checklist |
| [.docs/agents/repo-map.md](./repo-map.md) | Repo documentation structure |
