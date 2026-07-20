# Test Evidence Workflow

Agent guide for test evidence generation, publishing, and the dashboard lifecycle.

## Overview

Feature PRs in this repo follow a **generate → report → decide → publish** lifecycle for test evidence:

```text
PR CI generates normalised evidence artifacts
  → agent reports PR number in PR notes
  → reviewer/user decides whether to publish a durable snapshot
  → if yes: run Publish PR test evidence workflow → evidence lands on test-results branch
  → dashboard auto-refreshes via repository_dispatch
```

The **PR-number-first** workflow (`publish-pr-test-evidence.yml`) is the default path for CI evidence.
It requires only a PR number — the workflow resolves the CI run, artifact, and commit SHA automatically.

**Key principle:** Agents generate evidence metadata and ensure CI produces artifacts. They do **not** publish durable evidence unless explicitly instructed by the reviewer or issue.

> **Reminder:** On-device evidence (physical device runs) is a separate path. See [On-device / physical evidence](#on-device--physical-evidence).


## Feature PR lifecycle

### 1. Implementation

While implementing a feature, ensure:

- Normal CI validation runs for the PR (lint, unit tests, debug build).
- CI test evidence artifacts are generated when the PR reaches review-ready state.
  - This happens automatically if `scripts/generate_ci_test_evidence.py` runs as part of CI.
  - The evidence artifact is tagged with the commit SHA and PR number.
- For on-device features, ensure the `adb_skill_test.py` harness run is documented so manual validation can be reproduced.

### 2. PR notes / summary
In the PR description or final summary comment, agents should:

- Report the **PR number** — e.g. `#1234`
- Confirm whether CI test evidence artifacts were generated (CI runs this automatically)
- Note whether CI passed or had failures

**Do not** manually hunt for run IDs, commit SHAs, or artifact names for routine CI evidence publishing.
The [PR-number-first workflow](#pr-number-first-ci-evidence-publishing) resolves these automatically from the PR number.

The reviewer/user decides whether a given CI snapshot is worth publishing. A simple note is sufficient:


When evidence has been published, the PR notes should include a metadata checklist:

> **Evidence metadata:**
> 
> - GitHub PR number used for evidence: #1160
> - Closing issue: #1154
> - Evidence path: `results/pr/1160/...`
> - Verified evidence JSON \`pr\` field matches GitHub PR number: yes

> CI: ✅ passed — evidence artifacts available. Run \`Publish PR test evidence\` workflow with PR number only to publish.

> **Note on commit SHA:** CI evidence artifacts use the **merge commit SHA**, not the PR head SHA.
GitHub Actions sets `github.sha` to a merge commit for `pull_request` events. The evidence artifact
`ci-test-evidence-<sha>` and the evidence JSON's `commit` field both contain this merge SHA.
The PR-number-first workflow resolves the correct SHA automatically from the artifact name.
The lower-level `publish-test-evidence.yml` workflow still requires passing this SHA manually.

### 3. Stop for review

After opening or updating the PR, **stop** unless explicitly instructed to publish evidence. The reviewer or user decides whether the current test results are meaningful enough to warrant a durable published snapshot on the `test-results` branch.

## PR-number-first CI evidence publishing

The **default path** for publishing CI test evidence is the
[`publish-pr-test-evidence.yml`](../../.github/workflows/publish-pr-test-evidence.yml) workflow.
It resolves the CI run, artifact, and commit SHA from the PR number automatically.

### How to use

1. Go to **Actions → Publish PR test evidence → Run workflow**.
2. Enter the **PR number** (required). Optionally provide overrides if auto-resolution fails.
3. Run the workflow.
4. Check the workflow summary for what was published and where.

### Inputs

| Input | Required | Description |
|-------|----------|-------------|
| `pr` | Yes | PR number to publish latest CI evidence for |
| `run_id` | No | Override — CI run ID (auto-resolved if omitted) |
| `commit` | No | Override — commit SHA (auto-resolved from artifact name) |
| `allow_commit_mismatch` | No | Skip commit SHA consistency check (default: false) |

### What the workflow does

1. Resolves PR metadata (branch, head SHA, title) via `gh pr view`.
2. Finds the latest successful CI run for the PR branch (`gh run list --workflow CI --branch <branch> --status success`).
3. Finds the `ci-test-evidence-<sha>` artifact in that run.
4. Extracts the evidence commit SHA from the artifact name (the merge check SHA, not the PR head SHA).
5. Downloads the artifact and publishes via `scripts/publish_test_evidence.py`.
6. Triggers a `test-evidence-published` `repository_dispatch` to rebuild the dashboard.
7. Emits a step summary showing PR number, head SHA, CI run ID, artifact name, evidence commit, and published path.

### Override usage

If auto-resolution fails (no successful CI run, no artifact, artifact expired), provide overrides:

```yaml
run_id: 27204003987      # from a CI run URL
commit: 2798a1dd0a08...  # from the artifact name ci-test-evidence-<sha>
```

The workflow summary will mark overrides with ⚠️.

### Failure modes

| Scenario | Behaviour |
|----------|-----------|
| PR number invalid | Step 1 fails with clear error |
| No successful CI run for branch | Step 2 fails: "No successful CI run found" |
| No `ci-test-evidence-*` artifact | Step 3 fails: "No evidence artifact found" |
| Artifact expired | Step 3 fails: "artifact has expired" (retention: 30 days) |
| Commit mismatch (override only) | Validated by `publish_test_evidence.py`; use `allow_commit_mismatch` to bypass |

### Agent guidance

- **Default path for routine CI evidence:** PR number only. Do not ask the user for run IDs or commit SHAs.
- **Fallback:** Use the [manual publish](#manual-fallback-publish-test-evidence) workflow or the Python script directly, explaining why.
- **On-device evidence:** Never through this workflow. See [On-device / physical evidence](#on-device--physical-evidence).
- **PR number is the GitHub PR number, not the issue number:** Before generating or publishing evidence,
  mechanically discover the PR number with \`gh pr view --json number --jq .number\`.
  See [PR number vs issue number](#pr-number-vs-issue-number) below.

### PR number vs issue number

For all evidence generation and publishing, **\`pr\` means the actual GitHub Pull Request number**,
not the GitHub Issue number from \`Closes #N\`, parent issue, child issue, or epic issue.

| Context | Correct | Incorrect |
|---------|---------|-----------|
| PR URL | \`/pull/1160\` | \`/pull/1154\` (issue number) |
| Closing reference | \`Closes #1154\` | (not relevant to evidence) |
| Evidence JSON \`pr\` field | \`"pr": 1160\` | \`"pr": 1154\` |
| Publish path | \`results/pr/1160/...\` | \`results/pr/1154/...\` |
| Dashboard PR grouping | grouped under #1160 | grouped under #1154 |

**Mechanical discovery — always derive the PR number, never copy it:**

\`\`\`bash
PR_NUMBER="$(gh pr view --json number --jq .number)"
PR_HEAD_SHA="$(gh pr view --json headRefOid --jq .headRefOid)"
\`\`\`

Use \`$PR_NUMBER\` for:
- \`--pr\` argument to evidence generation and publishing commands.
- Evidence JSON \`pr\` field.
- Publish path \`results/pr/<PR_NUMBER>/...\`.
- PR summary grouping and dashboard grouping.

Use the issue number only in:
- \`Closes #<issue>\` in PR bodies.
- Related issue references and prose describing scope.

**The story behind this rule:** PR #1160 exposed the gap — it closed issue #1154, and an agent
confused the issue number with the actual PR number, publishing evidence under \`results/pr/1154/\`
instead of \`results/pr/1160/\`. The publisher now includes a guardrail (\`_validate_pr_number()\`)
that compares \`--pr\` against the current branch's open PR when \`gh\` is available.
Pass \`--allow-pr-mismatch\` only for exceptional recovery cases (re-publishing evidence
after a PR is closed).

## Evidence publishing

Durable publishing is **reviewer/user-controlled**.
The default path is the [PR-number-first workflow](#pr-number-first-ci-evidence-publishing).

### Default path: PR-number-first workflow (CI evidence)

For routine CI evidence publishing, use the
[Publish PR test evidence](../../.github/workflows/publish-pr-test-evidence.yml) workflow:

1. Navigate to **Actions → Publish PR test evidence → Run workflow**.
2. Enter the **PR number** only.
3. Run the workflow.
4. Check the workflow summary — it reports what was published, the commit SHA, artifact name, and run ID.

The dashboard auto-refreshes via `repository_dispatch` on success.

**Agents:** Do not ask the reviewer for run IDs, artifact names, or commit SHAs for routine CI evidence.
If the PR-number-first workflow fails, escalate to the manual fallback and explain why.

### Manual fallback: publish-test-evidence workflow

For advanced cases (release-scoped evidence, on-device evidence, or when the PR-number-first workflow
cannot resolve the CI run), use the lower-level
[publish-test-evidence.yml](../../.github/workflows/publish-test-evidence.yml) workflow:

1. Navigate to **Actions → Publish test evidence → Run workflow**.
2. Provide: `source` (`ci` or `on_device`), PR number or release, commit SHA, and CI run ID (for CI).
3. Workflow downloads the CI evidence artifact by run ID, validates, and publishes to `test-results`.
4. Dashboard auto-refreshes on success.

**When to use fallback:**

- Release-scoped evidence (requires `--release` flag).
- The PR-number-first workflow cannot find a successful CI run or artifact.
- The user explicitly provides a run ID or commit override.
- On-device evidence (must be published locally — the workflow prints the command to run).

### On-device evidence (local/manual)
For **on-device evidence**, the agent (when explicitly instructed):

1. Checks `adb devices` and confirms `ANDROID_SERIAL`.
2. Builds and installs the app if required (`./gradlew installDebug`).
3. Runs `scripts/adb_skill_test.py` with the relevant phase(s).
4. Finds the generated report in `scripts/test-reports/`.
5. Normalises the report to match the evidence schema.
6. Publishes locally with `scripts/publish_test_evidence.py` using appropriate flags.
7. Optionally triggers dashboard refresh — if `gh` auth is available and the user has explicitly instructed publication, the agent may manually trigger the "Publish test dashboard" workflow after local publish.
8. Reports the commands used, report path, published path, and dashboard outcome.

**Agent guidance for on-device evidence:**

- Before starting on-device work, **ask whether on-device testing is required** if it is not already explicit
  in the issue or user request. On-device testing requires a physical device, USB/wireless ADB connection,
  and the app to be installed — it is not a CI step.
- **Ask which device tier** is relevant: S21 (tracked/exynos signal device) or S23U (reference/flagship device).
- If on-device testing is required, **ask whether the resulting evidence should also be published** to
  `test-results` for dashboard visibility.
- **Do not imply** on-device evidence is covered by CI or the PR-number-first workflow.
- **Do not publish** on-device evidence without a real physical-device run and explicit scope, commit,
  and release metadata.

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

For acoustic wake-word runs, use `scripts/acoustic_wake_reliability_runner.py` rather than hand-authoring or re-normalising evidence. After cleanup it writes `scripts/private-acoustic-runs/<run-id>/sanitized/evidence-<target>.json` plus `run-summary.md` and the exact recursively sanitised files named by case/top-level `artifact_refs`. Publish that complete `sanitized/` directory with `scripts/publish_test_evidence.py --input-dir … --source on_device --pr <pull-request-number> --commit <tested-commit-sha>` so every relative reference remains resolvable. Diagnostic, smoke, interrupted, and feasibility records remain visible on the dashboard but cannot report release-gate success.

**User responsibilities:**

- Connect and unlock the physical device.
- Approve Android permission / device prompts (USB debugging, install confirmations).
- Keep the device available and screen awake during the run.
- Decide whether a given snapshot is worth publishing as durable dashboard evidence.

### CI vs on-device distinction
The schema (`docs/testing/test-evidence-schema.md`) distinguishes `ci` from `on_device` evidence via the `source` field in the evidence metadata. Agents must:

- Keep them separate in documentation and reporting.
- Never conflate CI pass rates (build + lint + unit tests) with on-device pass rates (model inference on physical hardware).
- Treat device IDs as registry-backed identities: the metrics summariser canonicalises public aliases (for example `s21`) through `scripts/testdata/devices.yaml`, and dashboard device aggregates retain model, SoC, and tier metadata.

**Evidence reporting guidance:**

- Distinguish "CI evidence published" from "on-device evidence published" in summaries.
- Call out missing on-device evidence **neutrally** when it was not requested.
- Call out required-but-missing on-device evidence as **incomplete work**.

## Dashboard and test-results branch
| Component | Location | Purpose |
|-----------|----------|---------|
| Evidence storage | `test-results` branch | Durable evidence snapshots, organised by source/PR/release |
| Dashboard builder | `scripts/build_test_dashboard.py` | Static site generator consuming `test-results/results/` |
| Dashboard deployment | GitHub Pages (`github-pages` env) | Published via `publish-test-dashboard.yml` workflow |
| Evidence publisher | `scripts/publish_test_evidence.py` | Validates and writes evidence to `test-results` |
| Evidence publishing workflow | `.github/workflows/publish-test-evidence.yml` | Manual dispatch → publish → trigger dashboard rebuild |
| PR-number-first workflow | `.github/workflows/publish-pr-test-evidence.yml` | PR-number-only dispatch → resolve run/artifact → publish → dashboard rebuild |
| Dashboard publishing workflow | `.github/workflows/publish-test-dashboard.yml` | Manual dispatch OR push to main OR repository_dispatch |

## Current non-goals

- **No merge gates** — evidence publishing is not required to merge.
- **No auto-publish on every push** — reviewer decides when to publish.
- **No interactive charting or external analytics service** — the static dashboard provides reviewer metrics, gate counts, classifications, clock-domain-safe timelines, and artifact links without client-side telemetry.
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
