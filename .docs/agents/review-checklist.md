# Review Checklist

Load this during code review. Select the applicable items — not all items apply to every review.
For subsystem-specific guidance, see the individual review gates (`.docs/agents/review-gates-*.md`).

## Structure & Scope
- [ ] Issue number referenced in PR body (`Closes #N`)
- [ ] Parent epic referenced (if applicable)
- [ ] Branch name follows convention (`feature/`, `fix/`, `docs/`, `chore/`)
- [ ] Scope is clear and out-of-scope items are documented
- [ ] Risk tier is declared (Low / Medium / High)
- [ ] No GitHub Copilot Review requested or used as merge signal

## Build & Code Quality
- [ ] Applicable checks from `.docs/agents/validation.md` and the risk policy pass (docs-only changes do not require Android builds)
- [ ] Build, lint and unit-test CI step outcomes inspected individually when CI runs (`continue-on-error` may hide failures)
- [ ] No new lint warnings or deprecations introduced
- [ ] No broad formatting-only diffs
- [ ] No secrets, credentials, or hardcoded tokens

## Tests
- [ ] Targeted unit tests added/updated for changed code
- [ ] Existing tests still pass (regression)
- [ ] Test evidence attached matches risk tier
- [ ] Device model and Android version noted (if device-tested)

## Security & Memory Safety
- [ ] No generic `fetch()` in Wasm skills
- [ ] SMS/email composers preserve safe, user-mediated `ACTION_SENDTO` behaviour
- [ ] No `Dispatchers.Main` for inference
- [ ] No concurrent E4B init (holds `gemma4InitMutex`)
- [ ] LiteRT session isolation verified (if applicable)
- [ ] Permission paths covered: first-run, denied, revoked, repair (if new permission)

## Docs
- [ ] ROADMAP.md reviewed / updated (if user-visible or roadmap-relevant change)
- [ ] SPECIFICATION.md reviewed / updated (if architecture change)
- [ ] AGENTS.md reviewed / updated (if agent-workflow change)
- [ ] UX_PATTERNS.md reviewed / updated (if new UI pattern)

## Limitations
- [ ] Known limitations declared in PR body
- [ ] Required follow-up issues tracked when remaining work blocks acceptance
- [ ] Compatibility assessed where released/external consumers actually depend on changed contracts
