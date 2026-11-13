# Delegating work to OMP agents through Paseo

How any coding agent working in this repo (Claude Code, Codex, OMP itself) hands a self-contained task to a separate OMP agent. Read this when a task should run in parallel or in the background, or when the owner asks for "an OMP session" or "a sub-agent".

## When to delegate

- The work is a self-contained change: an issue, a fix, a PR. The delegating agent only needs the result.
- Large changes that would otherwise fill the main conversation's context.
- Not for quick reads, single edits, or decisions the owner still has to make.

The canonical delegation rules still apply; see `.omp/AGENTS.md`, "Agent working model".

## Prefer OMP agents started with the Paseo CLI

The `paseo` CLI (`~/.local/bin/paseo` on the owner's PC) creates and controls agents:

```bash
paseo run --provider omp --new-workspace worktree --base main \
  --new-branch <type>/<short-name> --title "<short title>" --no-wait "<brief>"
```

| Command | What it does |
|---|---|
| `paseo ls` | List agents, with status and worktree |
| `paseo send <id> "<message>"` | Send a follow-up instruction to an existing agent |
| `paseo logs <id>` | Show an agent's activity |
| `paseo attach <id>` | Stream a running agent's output |
| `paseo stop <id>` | Interrupt a running agent (no-op when idle) |

`paseo run --help` lists the model, thinking and mode options.

Always give a new agent its own worktree (`--new-workspace worktree --base main`), so it never edits another agent's checkout.

OMP agents can run their own subagents. `.omp/AGENTS.md` discourages nested delegation unless it is requested, so if the task has independent parts that would benefit, say so explicitly in the brief, for example "you may use subagents for the tests and the docs".

## Existing sessions: "closed" often means asleep

Claude sessions with the OMP session-control connector can also message existing OMP sessions. The connector's tools are `list_sessions`, `get_session`, `send_prompt`, `get_transcript` and `interrupt`, on hosts `cachyos` and `plex`. The connector cannot create sessions; use `paseo run` for that.

A session listed as `closed` may only be asleep. Its status won't tell you which. Check by sending a harmless ping ("reply OK, take no other action") and reading the transcript. A woken session keeps its old worktree and context, so reuse one only when that context fits the new task.

## What a brief must contain

The agent knows only what the brief says. Include:

1. The issue number, or the goal and acceptance criteria.
2. The files or area to start from, and anything already ruled out.
3. What "done" looks like: tests to run, and whether to open a draft PR and against which base.
4. Repo conventions: commit format `type(#issue): description`, never merge, end with "Do not merge — wait for review."
5. Device rules:
   - Physical-device work only on the S23 Ultra test device, always as `adb -s RFCW11265XH`.
   - Never use `./gradlew installDebug`. It installs on every connected phone, and the others are personal.
   - Never touch the owner's Honor or the S21.
6. Secrets: never read, print or copy `keystore.properties`, the upload keystore or the Play service-account key.

## Fallback

If Paseo or OMP isn't available, use the delegating tool's own isolated background agent, such as Claude Code's Agent tool with a worktree, and say so.
