# Delegating work to OMP agents through Paseo

How any coding agent working in this repo (Claude Code, Codex, OMP itself) hands a self-contained task to a separate OMP agent. Read this when a task should run in parallel or in the background, or when the owner asks for "an OMP session" or "a sub-agent".

## When to delegate

This guide covers how to delegate. For when, follow `.omp/AGENTS.md`, "Agent working model". In addition, the owner has asked that a coordinating session working with them directly (for example, Claude Code acting as an orchestrator) hand self-contained changes (an issue, a fix, a PR) to OMP agents rather than doing them inline, so the coordinating conversation stays focused. Quick reads, single edits, and decisions the owner still has to make are not delegated.

## Prefer OMP agents started with the Paseo CLI

The `paseo` CLI creates and controls agents. It's installed on the owner's PC; check that `paseo` is on `PATH` before relying on it.

```bash
paseo run --provider omp/anthropic/claude-haiku-5-5 --new-workspace worktree --base main \
  --new-branch <type>/<short-name> --mode full --title "<short title>" --no-wait "<brief>"
```

**Model rule (owner's standing instruction):**
- Delegated agents use **Claude Haiku 5.5**: `--provider omp/anthropic/claude-haiku-5-5`.
- **Code reviews** use **Claude Opus 5.5**: `--provider omp/anthropic/claude-opus-5-5 --thinking high`.
- Always pass the model explicitly. Without it, Paseo uses its own default model, which the owner does not want.
- The rule applies to agents created through Paseo (`paseo run` or the Paseo MCP `create_agent` tool); it does not change the OMP `task`-tool rule in `.omp/AGENTS.md`.

| Command | What it does |
|---|---|
| `paseo ls` | List agents, with status and worktree |
| `paseo send <id> "<message>"` | Send a follow-up instruction to an existing agent |
| `paseo logs <id>` | Show an agent's activity |
| `paseo attach <id>` | Stream a running agent's output |
| `paseo stop <id>` | Interrupt a running agent (no-op when idle) |
| `paseo wait <id>` | Wait for an agent to become idle; add `--timeout <seconds>` to cap the wait |
| `paseo inspect <id>` | Show detailed information about an agent |

`paseo run --help` lists the model, thinking and mode options.

Review example (read-only, Claude Opus 5.5):

```bash
paseo run --provider omp/anthropic/claude-opus-5-5 --thinking high --mode full --new-workspace worktree --worktree-mode checkout-pr --pr-number <N> --title "Review PR #<N>" --no-wait "<brief>"
```

Always give a new agent its own worktree (`--new-workspace worktree --base main` for new work, `--worktree-mode checkout-pr` for an existing PR), so it never edits another agent's checkout.

OMP agents can run their own subagents. `.omp/AGENTS.md` discourages nested delegation by default. Grant it in the brief only when the task has genuinely independent parts.

Access mode: the default mode is not confirmed (`paseo run --help` lists `--mode` values without a default, and `paseo provider diagnostic omp` does not report modes), so pass `--mode full` explicitly. Review briefs must say read-only: do not edit, commit, push or comment.

In checkout-pr worktrees the local branch name can differ from the PR branch (e.g. a -3 suffix). Briefs that let an agent push must tell it to push with `git push origin HEAD:<pr-branch>`, where `<pr-branch>` comes from `gh pr view <N> --json headRefName -q .headRefName`. Never create a new remote branch.

## Existing sessions: "closed" often means asleep

Claude sessions with the OMP session-control connector can also message existing OMP sessions. The connector's tools are `list_sessions`, `get_session`, `send_prompt`, `get_transcript` and `interrupt`, on the owner's hosts (currently `cachyos` and `plex`; these are specific to the owner's setup). The connector cannot create sessions; use `paseo run` for that.

A session listed as `closed` may only be asleep. Its status won't tell you which. Check by sending a harmless ping ("reply OK, take no other action") and reading the transcript. A woken session keeps its old worktree and context, so reuse one only when that context fits the new task.

## What a brief must contain

The agent knows only what the brief says. Include:

1. The issue number, or the goal and acceptance criteria.
2. The files or area to start from, and anything already ruled out.
3. What "done" looks like: tests to run, and whether to open a draft PR and against which base.
4. A pointer to `.omp/AGENTS.md` for commit, PR and review conventions, rather than restating them. OMP agents load it automatically; other agents must be told to read it.
5. Devices: follow the Device Handling Rules in `.docs/agents/risk-based-evidence-policy.md`. OMP agents read it themselves; for non-OMP agents, paste those rules into the brief.
6. Secrets: never read, print or copy `keystore.properties`, the upload keystore or the Play service-account key.
7. Scope boundaries: what the agent must not touch.
8. End the brief with: Do not merge — wait for review.

## Fallback

If Paseo or OMP isn't available, use the delegating tool's own isolated background agent, such as Claude Code's Agent tool with a worktree, and say so.
