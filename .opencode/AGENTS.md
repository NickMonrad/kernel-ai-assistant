# OpenCode Repo Rules

Shared repo-local guidance for OpenCode when working in `kernel-ai-assistant`.

## Start here

1. Read the root `AGENTS.md`
2. Read the relevant role prompt in `.opencode/agents/`
3. Search first, then read surgically

## GitHub issue standards

When creating or reshaping a GitHub issue, normalize metadata immediately.

Required metadata:

- **Type** — `type:feature`, `type:bug`, `type:spike`, etc.
- **Go-state** — `go:yes`, `go:needs-research`, or `go:no`
- **Priority** — `priority:high`, `priority:medium`, `priority:low`, or sprint-specific labels
- **Size** — `size:XS`, `size:S`, `size:M`, `size:L`, `size:XL`
- **Milestone / phase** — set the roadmap milestone and any applicable phase label
- **Roadmap** — add `roadmap` when it belongs in planned product work
- **Domain labels** — `skills`, `voice`, `memory`, `ui`, `architecture`, etc. as appropriate

Structuring rules:

- If the work spans multiple meaningful tracks, make it a **parent/epic**
- Parent issues should decompose into child issues
- Include a UI/UX child issue whenever interface work is a real part of the feature
- Use `go:needs-research` when architecture is still open; use `go:yes` when implementation-ready
