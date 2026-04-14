# Contributing to Kernel AI Assistant

## PR & Review Workflow

All changes go through a PR — direct pushes to `main` are blocked by branch protection.

### Standard Flow

1. **Implement** on a feature branch (`feat/`, `fix/`, `docs/` prefix)
2. **Open PR** targeting `main` — title should reference the issue number (e.g. `fix: enable episodic RAG (#233)`)
3. **CI must pass** — "Build & Test" check is required before merge
4. **Unit tests** — significant new logic (parsers, use cases, repositories, ViewModels) should include unit tests. Use JUnit 5 + MockK. See `core/skills/src/test/` and `core/memory/src/test/` for examples. Copilot code-reviewer will flag missing coverage on critical paths.
4. **Code review** — run the Copilot code-reviewer agent on the PR
5. **Address findings:**
   - **Major issues** (bugs, logic errors, data loss risk, crashes) → fix on the branch, then run a scoped re-review on the fix commits only
   - **Minor issues** (cosmetic, trivial, low-risk) → fix and go straight to merge, no re-review needed
6. **Merge** — always squash merge: `gh pr merge --squash --admin --delete-branch`

### Branch Naming

| Prefix | Use |
|--------|-----|
| `feat/` | New features |
| `fix/` | Bug fixes |
| `docs/` | Documentation only |
| `refactor/` | Refactors with no behaviour change |
| `chore/` | Build, deps, tooling |

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):
```
fix: short description (#issue-number)
feat: short description (#issue-number)
docs: short description
```

Always include the co-authored-by trailer for Copilot-assisted commits:
```
Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

### Issues & Roadmap

- All significant work should have a GitHub issue
- Issues on the roadmap get the `roadmap` label and are assigned to the active milestone
- Sub-issues are linked via GitHub's sub-issue relationship (not just cross-references)
- `docs/ROADMAP.md` and `docs/SPECIFICATION.md` are kept in sync with issue state — update them in the same PR as the code change where possible

### Milestones

| Milestone | Focus |
|-----------|-------|
| Phase 3: Resident Agent + Native Skills | Active — Gemma-4 E4B, skill framework, memory tools, Jandal personality |
| Phase 4: Dreaming Engine | Planned — overnight distillation, semantic cache, self-healing identity |

### Memory & Specification Docs

- `docs/SPECIFICATION.md` — authoritative technical reference; update whenever architecture or behaviour changes
- `docs/ROADMAP.md` — living roadmap; mark issues done when closed, add new issues when raised
