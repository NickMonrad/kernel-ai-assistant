## Description
Closes #

## Scope
<!-- Summary of changes. Link related issues. -->

## Risk Tier
<!-- Low / Medium / High. See .docs/agents/risk-based-evidence-policy.md -->

## Evidence
<!-- Paste or link evidence manifest. See .docs/agents/evidence-manifest.md for format. -->

### Pre-merge Checklist

**For docs/template/copy-only PRs:**
- [ ] `git diff --check` — no whitespace errors
- [ ] Markdown lint passes (if configured — not currently configured in this repo)
- [ ] No Android build required
- [ ] No Android unit tests required
- [ ] No device testing required
- [ ] ROADMAP.md / SPECIFICATION.md / README.md reviewed (if needed)

**For code/build/behaviour changes:**
- [ ] `./gradlew assembleDebug` passes (or targeted module build)
- [ ] `./gradlew lint` passes (or baseline updated)
- [ ] `./gradlew testDebugUnitTest` passes (or targeted module tests)
- [ ] No new lint warnings or deprecations introduced
- [ ] Targeted unit tests added/updated for changed code
- [ ] Existing tests still pass
- [ ] Device test evidence attached (if applicable per risk tier)
- [ ] Device(s) used and Android version noted
- [ ] Screenshots attached for UI changes (before/after)
- [ ] Permission path covered: first-run, denied, revoked, repair (if applicable)
- [ ] ROADMAP.md / SPECIFICATION.md / README.md updated (if needed)

### Limitations
- [ ] Known limitations declared
- [ ] Follow-up issues created for deferred work
- [ ] Backward-compatibility impact assessed

<!-- Do not request GitHub Copilot Review. Human/ChatGPT review plus repo evidence is the expected review path. -->
