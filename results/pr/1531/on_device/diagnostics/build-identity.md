# Tested build identity

- Feature PR: #1531 (`fix(#1530): show contextual chat weather permission flow`)
- Feature/head SHA: `f240e65cae1b32c10a39030fb39b28f39ca02bc9`
- Tested artifact type: local debug APK (`com.kernel.ai.debug`)
- Tested APK filename: `app-debug.apk`
- Tested APK SHA-256: `7bce3d920838ee078506450e8008725520c3fa6a5a0895b72b3484cccc63de5a`
- APK was built from the feature/head worktree at the SHA above and installed with `adb install -r -d`.
- The install preserved app data; the run used permission reset commands rather than clearing application data.
- No GitHub synthetic PR merge SHA was used for the physical run.
- The APK file itself is not included in this evidence PR; the digest is retained for identity checking.
